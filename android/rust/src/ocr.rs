//! Text recognition for subtitles read off a phone's screen, where the desktop uses Windows' own
//! (`screen::ocr`): PaddleOCR's PP-OCRv4 mobile models on ONNX Runtime. The detector finds the
//! rows of text in the band, the recognizer reads each (Latin and Chinese letters), and where
//! each letter came out gives the edges of the words the subtitle reader wants.

use std::path::Path;

use anyhow::{Context, anyhow};
use hammurabi::screen::reader::Line;
use ort::session::Session;
use ort::value::Tensor;

/// The model files in the folder `Ocr::new` reads (as RapidOCR ships them).
pub const DET: &str = "ch_PP-OCRv4_det_infer.onnx";
pub const REC: &str = "ch_PP-OCRv4_rec_infer.onnx";

/// A pixel of the detector's map is text from this likelihood (PaddleOCR's `thresh`).
const THRESH: f32 = 0.3;
/// A region is text when its box averages this (`box_thresh`).
const BOX_THRESH: f32 = 0.5;
/// How far a region is grown back out to the edges of its letters (`unclip_ratio`).
const UNCLIP: f32 = 1.6;
/// Regions lower than this many pixels are specks.
const MIN_H: usize = 3;
/// The recognizer reads rows this tall.
const REC_H: usize = 48;
/// The widest row read, in the recognizer's pixels: 66 times its height.
const REC_W_MAX: usize = 3200;
/// Rows read with a lower mean confidence are dropped (RapidOCR's `text_score`).
const MIN_SCORE: f32 = 0.5;

/// A box in the picture, in pixels.
#[derive(Clone, Copy, Debug, PartialEq)]
struct Rect {
    x0: f32,
    y0: f32,
    x1: f32,
    y1: f32,
}

pub struct Ocr {
    det: Session,
    rec: Session,
    /// The recognizer's outputs: 0 is CTC's blank, the last a space.
    chars: Vec<String>,
}

impl Ocr {
    /// The models in `dir`, each run on `threads` cores.
    pub fn new(dir: &Path, threads: usize) -> anyhow::Result<Self> {
        let env = ort::init().with_name("hammurabi-ocr").build()?;
        let open = |file: &str| -> anyhow::Result<Session> {
            Session::builder(&env)?
                .with_intra_threads(threads.max(1))
                .map_err(|e| anyhow!("{e}"))?
                .commit_from_file(dir.join(file))
                .with_context(|| format!("loading {file}"))
        };
        let det = open(DET)?;
        let rec = open(REC)?;
        let list = rec
            .metadata()?
            .custom("character")
            .context("the recognizer has no character list")?;
        let mut chars = vec![String::new()];
        chars.extend(list.lines().map(str::to_string));
        chars.push(" ".into());
        Ok(Self { det, rec, chars })
    }

    /// The rows of text in a picture of one byte a pixel, `width` by `height`.
    pub fn read(&mut self, grey: &[u8], width: usize, height: usize) -> anyhow::Result<Vec<Line>> {
        self.read_in(grey, grey, width, height)
    }

    /// The rows of text found in `find` as read in `read`, two pictures of the same size: the
    /// reader's prepared picture keeps only the subtitle's letters but thins some strokes away
    /// (a t loses its bar), which Windows' recognizer reads through and this one does not.
    pub fn read_in(
        &mut self,
        find: &[u8],
        read: &[u8],
        width: usize,
        height: usize,
    ) -> anyhow::Result<Vec<Line>> {
        anyhow::ensure!(
            find.len() >= width * height && read.len() >= width * height,
            "picture smaller than its size"
        );
        if width < 8 || height < 8 {
            return Ok(Vec::new());
        }
        let mut lines = Vec::new();
        for mut r in self.detect(find, width, height)? {
            if !std::ptr::eq(find, read) {
                // A full stop or a question mark's dot can be thinned out of `find` too: half a
                // row's height more on each side reads them in `read`.
                let more = 0.5 * (r.y1 - r.y0);
                r.x0 = (r.x0 - more).max(0.0);
                r.x1 = (r.x1 + more).min(width as f32);
            }
            if let Some(line) = self.recognize(read, width, r)? {
                lines.push(line);
            }
        }
        Ok(join_rows(lines))
    }

    fn detect(&mut self, grey: &[u8], w: usize, h: usize) -> anyhow::Result<Vec<Rect>> {
        // Sides a multiple of 32, padded with black.
        let (pw, ph) = (w.div_ceil(32) * 32, h.div_ceil(32) * 32);
        let plane = pw * ph;
        let mut x = vec![-1.0f32; 3 * plane];
        for y in 0..h {
            for (i, &v) in grey[y * w..][..w].iter().enumerate() {
                let v = f32::from(v) / 127.5 - 1.0;
                for c in 0..3 {
                    x[c * plane + y * pw + i] = v;
                }
            }
        }
        let out = self
            .det
            .run(ort::inputs! { "x" => Tensor::from_array(([1usize, 3, ph, pw], x))? })?;
        let (_, prob) = out[0].try_extract_tensor::<f32>()?;
        Ok(regions(prob, pw, w, h))
    }

    fn recognize(&mut self, grey: &[u8], w: usize, r: Rect) -> anyhow::Result<Option<Line>> {
        let (x0, y0) = (r.x0 as usize, r.y0 as usize);
        let (bw, bh) = (
            (r.x1 as usize).saturating_sub(x0),
            (r.y1 as usize).saturating_sub(y0),
        );
        if bw < 2 || bh < 2 {
            return Ok(None);
        }
        let nw = ((REC_H * bw) as f32 / bh as f32).round() as usize;
        let nw = nw.clamp(8, REC_W_MAX);
        let plane = REC_H * nw;
        let mut x = vec![0f32; 3 * plane];
        // Bilinear, from the box to REC_H rows of nw.
        for ty in 0..REC_H {
            let sy =
                ((ty as f32 + 0.5) * bh as f32 / REC_H as f32 - 0.5).clamp(0.0, (bh - 1) as f32);
            let (ya, fy) = (sy as usize, sy.fract());
            let yb = (ya + 1).min(bh - 1);
            for tx in 0..nw {
                let sx =
                    ((tx as f32 + 0.5) * bw as f32 / nw as f32 - 0.5).clamp(0.0, (bw - 1) as f32);
                let (xa, fx) = (sx as usize, sx.fract());
                let xb = (xa + 1).min(bw - 1);
                let px = |yy: usize, xx: usize| f32::from(grey[(y0 + yy) * w + x0 + xx]);
                let top = px(ya, xa) * (1.0 - fx) + px(ya, xb) * fx;
                let bottom = px(yb, xa) * (1.0 - fx) + px(yb, xb) * fx;
                let v = (top * (1.0 - fy) + bottom * fy) / 127.5 - 1.0;
                for c in 0..3 {
                    x[c * plane + ty * nw + tx] = v;
                }
            }
        }
        let out = self
            .rec
            .run(ort::inputs! { "x" => Tensor::from_array(([1usize, 3, REC_H, nw], x))? })?;
        let (shape, probs) = out[0].try_extract_tensor::<f32>()?;
        let (steps, classes) = (shape[1] as usize, shape[2] as usize);
        Ok(decode(probs, steps, classes, &self.chars, r))
    }
}

/// The text regions of the detector's map (`pw` wide), within the `w` by `h` picture.
fn regions(prob: &[f32], pw: usize, w: usize, h: usize) -> Vec<Rect> {
    // Text pixels, grown by one to the right and down (PaddleOCR's dilation) so the parts of a
    // letter stay together.
    let mut text = vec![false; w * h];
    for y in 0..h {
        for x in 0..w {
            let hit = |yy: usize, xx: usize| yy < h && xx < w && prob[yy * pw + xx] > THRESH;
            text[y * w + x] = hit(y, x)
                || (x > 0 && hit(y, x - 1))
                || (y > 0 && hit(y - 1, x))
                || (x > 0 && y > 0 && hit(y - 1, x - 1));
        }
    }
    let mut seen = vec![false; w * h];
    let mut stack = Vec::new();
    let mut out = Vec::new();
    for start in 0..w * h {
        if !text[start] || seen[start] {
            continue;
        }
        seen[start] = true;
        stack.push(start);
        let (mut x0, mut y0, mut x1, mut y1) = (w, h, 0, 0);
        while let Some(i) = stack.pop() {
            let (x, y) = (i % w, i / w);
            (x0, y0, x1, y1) = (x0.min(x), y0.min(y), x1.max(x + 1), y1.max(y + 1));
            let mut visit = |j: usize| {
                if text[j] && !seen[j] {
                    seen[j] = true;
                    stack.push(j);
                }
            };
            if x > 0 {
                visit(i - 1);
            }
            if x + 1 < w {
                visit(i + 1);
            }
            if y > 0 {
                visit(i - w);
            }
            if y + 1 < h {
                visit(i + w);
            }
        }
        let (bw, bh) = (x1 - x0, y1 - y0);
        if bh < MIN_H || bw < MIN_H {
            continue;
        }
        let mut sum = 0f32;
        for y in y0..y1 {
            sum += prob[y * pw + x0..y * pw + x1].iter().sum::<f32>();
        }
        if sum / ((bw * bh) as f32) < BOX_THRESH {
            continue;
        }
        // The map marks the middle of letters; grown by area / perimeter, as PaddleOCR does.
        let d = (bw * bh) as f32 * UNCLIP / (2 * (bw + bh)) as f32;
        out.push(Rect {
            x0: (x0 as f32 - d).max(0.0),
            y0: (y0 as f32 - d).max(0.0),
            x1: (x1 as f32 + d).min(w as f32),
            y1: (y1 as f32 + d).min(h as f32),
        });
    }
    out.sort_by(|a, b| {
        (a.y0, a.x0)
            .partial_cmp(&(b.y0, b.x0))
            .unwrap_or(std::cmp::Ordering::Equal)
    });
    out
}

/// CTC's best path through the recognizer's `steps` outputs: the letters, and each word's left
/// and right edge from the steps its letters came out at.
fn decode(probs: &[f32], steps: usize, classes: usize, chars: &[String], r: Rect) -> Option<Line> {
    let step_w = (r.x1 - r.x0) / steps.max(1) as f32;
    let at = |t: usize| r.x0 + (t as f32 + 0.5) * step_w;
    let mut letters: Vec<(&str, usize)> = Vec::new();
    let mut score = 0f32;
    let mut prev = 0usize;
    for t in 0..steps {
        let row = &probs[t * classes..][..classes];
        let (best, p) = row
            .iter()
            .enumerate()
            .fold((0, f32::MIN), |m, (i, &p)| if p > m.1 { (i, p) } else { m });
        if best != 0 && best != prev && best < chars.len() {
            letters.push((&chars[best], t));
            score += p;
        }
        prev = best;
    }
    if letters.is_empty() || score / (letters.len() as f32) < MIN_SCORE {
        return None;
    }
    let mut words: Vec<(String, f32, f32)> = Vec::new();
    let mut word: Option<(String, usize, usize)> = None;
    for (c, t) in &letters {
        if c.trim().is_empty() {
            words.extend(
                word.take()
                    .map(|(s, a, b)| (s, at(a) - step_w, at(b) + step_w)),
            );
        } else {
            let w = word.get_or_insert_with(|| (String::new(), *t, *t));
            w.0.push_str(c);
            w.2 = *t;
        }
    }
    words.extend(word.map(|(s, a, b)| (s, at(a) - step_w, at(b) + step_w)));
    if words.is_empty() {
        return None;
    }
    let text = words
        .iter()
        .map(|w| w.0.as_str())
        .collect::<Vec<_>>()
        .join(" ");
    Some(Line {
        text,
        x0: r.x0,
        y0: r.y0,
        x1: r.x1,
        y1: r.y1,
        words,
    })
}

/// Pieces of one row (the detector splits a row at a wide gap) as one line, as Windows' reader
/// gives them.
fn join_rows(mut lines: Vec<Line>) -> Vec<Line> {
    lines.sort_by(|a, b| (a.y0 + a.y1).total_cmp(&(b.y0 + b.y1)));
    let mut rows: Vec<Line> = Vec::new();
    for line in lines {
        let same = rows.iter_mut().find(|r| {
            let overlap = r.y1.min(line.y1) - r.y0.max(line.y0);
            overlap > 0.5 * (r.y1 - r.y0).min(line.y1 - line.y0)
        });
        match same {
            Some(row) => {
                let mut words = std::mem::take(&mut row.words);
                words.extend(line.words);
                words.sort_by(|a, b| a.1.total_cmp(&b.1));
                *row = Line {
                    text: words
                        .iter()
                        .map(|w| w.0.as_str())
                        .collect::<Vec<_>>()
                        .join(" "),
                    x0: row.x0.min(line.x0),
                    y0: row.y0.min(line.y0),
                    x1: row.x1.max(line.x1),
                    y1: row.y1.max(line.y1),
                    words,
                };
            }
            None => rows.push(line),
        }
    }
    rows
}

#[cfg(test)]
mod tests {
    use super::*;

    fn line(text: &str, x0: f32, y0: f32, x1: f32, y1: f32) -> Line {
        Line {
            text: text.into(),
            x0,
            y0,
            x1,
            y1,
            words: vec![(text.into(), x0, x1)],
        }
    }

    #[test]
    fn pieces_of_a_row_join_and_rows_stay_apart() {
        let rows = join_rows(vec![
            line("keys?", 900.0, 150.0, 1000.0, 200.0),
            line("Where", 600.0, 152.0, 700.0, 198.0),
            line("dawn.", 650.0, 210.0, 750.0, 250.0),
        ]);
        assert_eq!(rows.len(), 2);
        assert_eq!(rows[0].text, "Where keys?");
        assert_eq!((rows[0].x0, rows[0].x1), (600.0, 1000.0));
        assert_eq!(rows[1].text, "dawn.");
    }

    #[test]
    fn regions_grow_back_to_the_letters() {
        // A 40 x 10 blob of text in a 64 x 32 map.
        let (pw, w, h) = (64, 64, 32);
        let mut prob = vec![0f32; pw * h];
        for y in 10..20 {
            for x in 10..50 {
                prob[y * pw + x] = 0.9;
            }
        }
        let r = regions(&prob, pw, w, h);
        assert_eq!(r.len(), 1);
        // Dilation adds a pixel right and down; then grown by 41 * 11 * 1.6 / 104 = 6.9.
        assert!(
            (r[0].x0 - 3.06).abs() < 0.1 && (r[0].y1 - 27.94).abs() < 0.1,
            "{:?}",
            r[0]
        );
        // A faint region is not text.
        prob.iter_mut().for_each(|p| *p *= 0.5);
        assert!(regions(&prob, pw, w, h).is_empty());
    }

    #[test]
    fn best_path_letters_and_word_edges() {
        // Classes: blank, "a", "b", space; steps: a a _ b space b.
        let chars: Vec<String> = ["", "a", "b", " "].iter().map(|s| s.to_string()).collect();
        let pick = |c: usize| {
            let mut row = [0.0f32; 4];
            row[c] = 0.9;
            row
        };
        let probs: Vec<f32> = [1, 1, 0, 2, 3, 2].iter().flat_map(|&c| pick(c)).collect();
        let r = Rect {
            x0: 0.0,
            y0: 0.0,
            x1: 60.0,
            y1: 10.0,
        };
        let l = decode(&probs, 6, 4, &chars, r).unwrap();
        assert_eq!(l.text, "ab b");
        assert_eq!(l.words.len(), 2);
        // Steps are 10 px; "ab" came out at steps 0 and 3, "b" at step 5.
        assert_eq!((l.words[0].1, l.words[0].2), (-5.0, 45.0));
        assert_eq!((l.words[1].1, l.words[1].2), (45.0, 65.0));
    }

    /// The models on a picture made by `tests/ocr_band.py` (run with HAMMURABI_OCR_DIR set to the
    /// models' folder and HAMMURABI_OCR_BAND to the picture): `cargo test -- --ignored`.
    #[test]
    #[ignore]
    fn reads_a_subtitle_band() {
        let dir = std::env::var("HAMMURABI_OCR_DIR").expect("HAMMURABI_OCR_DIR");
        let band = std::env::var("HAMMURABI_OCR_BAND").expect("HAMMURABI_OCR_BAND");
        let grey = std::fs::read(&band).unwrap();
        let (w, h) = (1920, grey.len() / 1920);
        let mut ocr = Ocr::new(Path::new(&dir), 2).unwrap();
        let lines = ocr.read(&grey, w, h).unwrap();
        let text: Vec<&str> = lines.iter().map(|l| l.text.as_str()).collect();
        assert_eq!(
            text,
            [
                "Where did you put the keys?",
                "We have to leave before dawn."
            ]
        );
        assert!(lines[0].words.len() == 6 && lines[0].words[0].0 == "Where");
    }
}
