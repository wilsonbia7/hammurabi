//! Translate the screen, on a phone: text anywhere on it (a post, comments, the words on a meme,
//! a video's or a game's subtitles) is read with PP-OCR (`ocr`) and given to the engine
//! (`Fed::screen`) paragraph by paragraph, once it stands still, when it says something
//! (`lang`) in another language than the one translated into.
//!
//! The app sends what Android's screen capture gives, a few pictures a second
//! (`Native.screenFrame`). A picture that changed is read at most every `READ_EVERY`. A paragraph
//! is given on once it reads alike in the same place twice on end, which text being scrolled does
//! not, or once the picture it was read in stays as it was; and not again while it stays on the
//! screen, nor for `GIVEN_KEEP_S` after.

use std::collections::HashMap;
use std::hash::{DefaultHasher, Hash, Hasher};
use std::path::Path;
use std::time::Instant;

use crossbeam_channel::Sender;
use hammurabi::screen::ScreenEvent;
use hammurabi::screen::reader::Line;

use crate::lang::{Langs, fold};
use crate::ocr::{Found, Ocr, Rect};

/// Seconds between readings of a picture that keeps changing, as a video playing does.
const READ_EVERY: f64 = 0.5;
/// What was read is taken as read right once the picture stays as it was this long.
const CONFIRM_S: f64 = 0.3;
/// A paragraph given on is not given again while it stays on screen, nor for this long after.
const GIVEN_KEEP_S: f64 = 45.0;
/// At most this many paragraphs are given on at once: those nearest the middle of the screen.
const GIVE_MAX: usize = 3;
/// Rows are looked for in the picture made at most this long on its long side (PaddleOCR's
/// `limit_side_len`): enough for a phone's text, and four times less work than the whole.
const FIND_SIDE: f32 = 960.0;
/// Rows lower than this many pixels are small print, and higher than this share of the picture
/// are no row of text.
const ROW_MIN_H: f32 = 12.0;
const ROW_MAX_H: f32 = 0.2;
/// At most this many rows are read in one picture: those nearest its middle.
const ROWS_MAX: usize = 80;
/// The picture made this many pixels wide is compared with the one read last, to see a change:
/// a pixel of it that changed by more than `THUMB_LEVELS`, in at least `THUMB_PIXELS` pixels.
const THUMB_W: usize = 96;
const THUMB_LEVELS: u8 = 6;
const THUMB_PIXELS: usize = 2;
/// Pictures on end the recognizer fails on before reading stops with the error.
const FAILS_MAX: u32 = 50;

// Lengths in row heights: the height of the middle of a row's letters (`Found::core`), about
// half its font's size.
/// Pieces of one row are joined across a gap up to this,
const JOIN_GAP: f32 = 1.2;
/// and rows stack into a paragraph across a gap up to this (lines of text set 1.3 apart leave
/// 1.4 between them), and once a paragraph has two rows, up to `GAP_GROWTH` times the gap
/// between them: what comes under a paragraph stands further off than its rows from each other,
const ROW_GAP: f32 = 1.6;
const GAP_GROWTH: f32 = 1.3;
/// when one is at most this much higher than the other,
const ROW_RATIO: f32 = 1.35;
/// and their left edges are this near, or their middles this (rows of centred text).
const ALIGN: f32 = 1.2;
const ALIGN_MID: f32 = 0.4;
/// A paragraph read again stands where it stood when its top and left edge moved at most this,
const STAY: f32 = 0.6;
/// and it reads this alike (0 to 1).
const ALIKE: f32 = 0.8;

pub struct PageRead {
    tx: Sender<ScreenEvent>,
    ocr: Ocr,
    langs: Langs,
    t0: Instant,
    ready: bool,
    fails: u32,
    /// The picture, one byte a pixel, and made smaller to find rows in.
    grey: Vec<u8>,
    small: Vec<u8>,
    thumb: Thumb,
    /// When a picture was last read.
    read_at: Option<f64>,
    /// The paragraphs to translate in the picture read last.
    blocks: Vec<Block>,
    /// Some of them are not given on yet: they wait to be read again, or for the picture to stay.
    waiting: bool,
    /// What was given on (`Block::key`), and when it was last on screen.
    given: Vec<(String, f64)>,
    /// The rows read in the picture before, by what they look like: a row that did not change,
    /// though it moved, is not read again.
    rows_read: HashMap<u64, (Rect, Option<Line>)>,
    /// Scratch for the rows found in a picture.
    found: Vec<Found>,
}

impl PageRead {
    /// Reads with the recognizer's models from `models`, run on `threads` cores, for translating
    /// into `target`.
    pub fn new(
        tx: Sender<ScreenEvent>,
        models: &Path,
        threads: usize,
        target: &str,
    ) -> anyhow::Result<Self> {
        Ok(Self {
            tx,
            ocr: Ocr::new(models, threads)?,
            langs: Langs::new(target),
            t0: Instant::now(),
            ready: false,
            fails: 0,
            grey: Vec::new(),
            small: Vec::new(),
            thumb: Thumb::default(),
            read_at: None,
            blocks: Vec::new(),
            waiting: false,
            given: Vec::new(),
            rows_read: HashMap::new(),
            found: Vec::new(),
        })
    }

    /// The language translated into changed.
    pub fn set_target(&mut self, target: &str) {
        self.langs = Langs::new(target);
        self.blocks.clear();
        self.waiting = false;
    }

    /// One picture, `width` by `height`, as Android gives it: rows of `stride` bytes, four bytes a
    /// pixel (red, green, blue, alpha). `mask` (left, top, right, bottom) covers our own captions,
    /// which are not read. Fails, and tells the engine, when the picture is not that size or the
    /// recognizer failed on `FAILS_MAX` on end.
    pub fn frame(
        &mut self,
        rgba: &[u8],
        width: usize,
        height: usize,
        stride: usize,
        mask: Option<[f32; 4]>,
    ) -> anyhow::Result<()> {
        let now = self.t0.elapsed().as_secs_f64();
        let result = self.frame_at(rgba, width, height, stride, mask, now);
        if let Err(e) = &result {
            let _ = self.tx.send(ScreenEvent::Error(format!("{e:#}")));
        }
        result
    }

    fn frame_at(
        &mut self,
        rgba: &[u8],
        w: usize,
        h: usize,
        stride: usize,
        mask: Option<[f32; 4]>,
        now: f64,
    ) -> anyhow::Result<()> {
        anyhow::ensure!(
            w >= 8 && h >= 8 && stride >= w * 4 && rgba.len() >= stride * (h - 1) + w * 4,
            "a picture smaller than its size"
        );
        if !self.ready {
            self.ready = true;
            let _ = self.tx.send(ScreenEvent::Ready(
                hammurabi::i18n::t(hammurabi::i18n::T::TheMainDisplay).to_string(),
            ));
        }
        let since = self.read_at.map_or(f64::INFINITY, |t| now - t);
        if !self.thumb.changed(rgba, w, h, stride) {
            if self.waiting && since >= CONFIRM_S {
                // The picture stands as it was read: so does what was read in it.
                let blocks = std::mem::take(&mut self.blocks);
                let stood = vec![true; blocks.len()];
                self.give(&blocks, &stood, h as f32, now);
                self.blocks = blocks;
                self.waiting = false;
            }
            return Ok(());
        }
        if since < READ_EVERY {
            return Ok(());
        }
        self.thumb.read();
        self.read_at = Some(now);
        let blocks = match self.read(rgba, w, h, stride, mask) {
            Ok(b) => b,
            Err(e) => {
                // Read again with the next picture; only many on end stop the reading.
                self.fails += 1;
                if self.fails >= FAILS_MAX {
                    return Err(e);
                }
                log::warn!("screen: a picture could not be read: {e:#}");
                return Ok(());
            }
        };
        self.fails = 0;
        let stood: Vec<bool> = blocks
            .iter()
            .map(|b| self.blocks.iter().any(|p| p.stands_as(b)))
            .collect();
        self.give(&blocks, &stood, h as f32, now);
        self.waiting = blocks
            .iter()
            .zip(&stood)
            .any(|(b, &s)| !s && !self.was_given(&b.key));
        self.blocks = blocks;
        Ok(())
    }

    fn was_given(&self, key: &str) -> bool {
        self.given.iter().any(|(k, _)| alike(k, key) >= ALIKE)
    }

    /// Gives on the paragraphs that `stood` still and were not given lately; keeps those on screen
    /// from being given again. `height`: the picture's.
    fn give(&mut self, blocks: &[Block], stood: &[bool], height: f32, now: f64) {
        for b in blocks {
            for g in self
                .given
                .iter_mut()
                .filter(|g| alike(&g.0, &b.key) >= ALIKE)
            {
                g.1 = now;
            }
        }
        self.given.retain(|g| now - g.1 < GIVEN_KEEP_S);
        let mut fresh: Vec<&Block> = blocks
            .iter()
            .zip(stood)
            .filter(|&(b, &s)| s && !self.was_given(&b.key))
            .map(|(b, _)| b)
            .collect();
        let mid = height / 2.0;
        let off = |b: &Block| ((b.y0 + b.y1) / 2.0 - mid).abs();
        fresh.sort_by(|a, b| off(a).total_cmp(&off(b)));
        fresh.truncate(GIVE_MAX);
        fresh.sort_by(|a, b| a.y0.total_cmp(&b.y0));
        for b in fresh {
            // Two paragraphs alike in one picture go once.
            if self.was_given(&b.key) {
                continue;
            }
            log::info!("screen: {}", b.text);
            let _ = self.tx.send(ScreenEvent::Line(b.text.clone()));
            self.given.push((b.key.clone(), now));
        }
    }

    /// The paragraphs to translate in a picture.
    fn read(
        &mut self,
        rgba: &[u8],
        w: usize,
        h: usize,
        stride: usize,
        mask: Option<[f32; 4]>,
    ) -> anyhow::Result<Vec<Block>> {
        self.grey.resize(w * h, 0);
        for y in 0..h {
            let row = &rgba[y * stride..][..w * 4];
            for (g, px) in self.grey[y * w..][..w].iter_mut().zip(row.chunks_exact(4)) {
                *g = luma(px[0], px[1], px[2]);
            }
        }
        let mask = mask.and_then(|[l, t, r, b]| {
            let m = Rect {
                x0: l.max(0.0),
                y0: t.max(0.0),
                x1: r.min(w as f32),
                y1: b.min(h as f32),
            };
            (m.x1 > m.x0 && m.y1 > m.y0).then_some(m)
        });
        if let Some(m) = mask {
            blank(&mut self.grey, w, h, m);
        }
        let f = (w.max(h) as f32 / FIND_SIDE).round().max(1.0) as usize;
        let found = if f == 1 {
            self.ocr.detect(&self.grey, w, h)?
        } else {
            let (sw, sh) = shrink(&self.grey, w, h, f, &mut self.small);
            self.ocr
                .detect(&self.small, sw, sh)?
                .into_iter()
                .map(|r| r.scaled(f as f32, w, h))
                .collect()
        };
        self.found = found;
        self.found.retain(|r| {
            r.rect.h() >= ROW_MIN_H
                && r.rect.h() <= ROW_MAX_H * h as f32
                && !mask.is_some_and(|m| overlaps(&r.rect, &m))
        });
        if self.found.len() > ROWS_MAX {
            let (cx, cy) = (w as f32 / 2.0, h as f32 / 2.0);
            let off = |r: &Found| {
                ((r.core.x0 + r.core.x1) / 2.0 - cx).abs()
                    + ((r.core.y0 + r.core.y1) / 2.0 - cy).abs()
            };
            self.found.sort_by(|a, b| off(a).total_cmp(&off(b)));
            self.found.truncate(ROWS_MAX);
        }
        let mut seen = HashMap::with_capacity(self.found.len());
        let mut lines = Vec::new();
        for i in 0..self.found.len() {
            let Found { core, rect } = self.found[i];
            let looks = looks(&self.grey, w, rect);
            let line = match self.rows_read.get(&looks) {
                Some((was, line)) => line
                    .clone()
                    .map(|l| moved(l, core.x0 - was.x0, core.y0 - was.y0)),
                // Where its letters are tells it from the rows around it; the box read is grown.
                None => self.ocr.recognize(&self.grey, w, rect)?.map(|l| Line {
                    x0: core.x0,
                    y0: core.y0,
                    x1: core.x1,
                    y1: core.y1,
                    ..l
                }),
            };
            seen.insert(looks, (core, line.clone()));
            lines.extend(line);
        }
        self.rows_read = seen;
        Ok(blocks(rows(lines))
            .into_iter()
            .filter(|b| self.langs.foreign(&b.text))
            .collect())
    }
}

/// A row of text: the pieces read on one line, left to right.
#[derive(Clone, Debug)]
struct Row {
    pieces: Vec<(f32, String)>,
    x0: f32,
    y0: f32,
    x1: f32,
    y1: f32,
}

impl Row {
    fn h(&self) -> f32 {
        self.y1 - self.y0
    }

    fn mid(&self) -> f32 {
        (self.x0 + self.x1) / 2.0
    }

    fn text(&self) -> String {
        let words: Vec<&str> = self.pieces.iter().map(|p| p.1.as_str()).collect();
        words.join(" ")
    }
}

/// A paragraph: rows stacked one under the other.
#[derive(Clone, Debug, PartialEq)]
struct Block {
    /// What goes to be translated: the rows joined, without hashtags, handles and links.
    text: String,
    /// The text to compare: its letters and digits, lower case.
    key: String,
    x0: f32,
    y0: f32,
    x1: f32,
    y1: f32,
    /// Its first row's height.
    h: f32,
}

impl Block {
    fn new(rows: &[Row]) -> Option<Block> {
        // A count or a time under or over a paragraph (1,2 B; 12:30) is not part of it.
        let worded = |r: &Row| r.pieces.iter().any(|p| has_word(&p.1));
        let start = rows.iter().position(worded)?;
        let end = rows.iter().rposition(worded)? + 1;
        let rows = &rows[start..end];
        let first = rows.first()?;
        let mut text = String::new();
        for r in rows {
            let t = r.text();
            // A word broken over two rows with a hyphen is one word again.
            if text.ends_with('-') && t.starts_with(|c: char| c.is_lowercase()) {
                text.pop();
            } else if !text.is_empty() {
                text.push(' ');
            }
            text.push_str(&t);
        }
        let text = clean(&text);
        if text.is_empty() {
            return None;
        }
        let key = fold(&text)
            .chars()
            .filter(|c| c.is_alphanumeric())
            .collect();
        Some(Block {
            text,
            key,
            x0: rows.iter().map(|r| r.x0).fold(f32::MAX, f32::min),
            y0: first.y0,
            x1: rows.iter().map(|r| r.x1).fold(0.0, f32::max),
            y1: rows.iter().map(|r| r.y1).fold(0.0, f32::max),
            h: first.h(),
        })
    }

    /// Whether `other`, read later, is this paragraph where it was.
    fn stands_as(&self, other: &Block) -> bool {
        let tol = STAY * self.h.max(other.h);
        (self.y0 - other.y0).abs() <= tol
            && (self.x0 - other.x0).abs() <= tol
            && alike(&self.key, &other.key) >= ALIKE
    }
}

/// The pieces the recognizer read joined into rows.
fn rows(mut lines: Vec<Line>) -> Vec<Row> {
    lines.sort_by(|a, b| (a.y0 + a.y1).total_cmp(&(b.y0 + b.y1)));
    let mut rows: Vec<Row> = Vec::new();
    for l in lines {
        let lh = l.y1 - l.y0;
        let same = rows.iter_mut().find(|r| {
            let overlap = r.y1.min(l.y1) - r.y0.max(l.y0);
            let gap = (l.x0 - r.x1).max(r.x0 - l.x1);
            overlap > 0.6 * r.h().min(lh) && gap <= JOIN_GAP * r.h().max(lh)
        });
        match same {
            Some(r) => {
                r.pieces.push((l.x0, l.text));
                r.pieces.sort_by(|a, b| a.0.total_cmp(&b.0));
                (r.x0, r.y0, r.x1, r.y1) = (
                    r.x0.min(l.x0),
                    r.y0.min(l.y0),
                    r.x1.max(l.x1),
                    r.y1.max(l.y1),
                );
            }
            None => rows.push(Row {
                pieces: vec![(l.x0, l.text)],
                x0: l.x0,
                y0: l.y0,
                x1: l.x1,
                y1: l.y1,
            }),
        }
    }
    rows
}

/// Rows stacked into paragraphs: each row goes under the nearest one above it that it lines up
/// with, close enough and about as high.
fn blocks(mut rows: Vec<Row>) -> Vec<Block> {
    rows.sort_by(|a, b| a.y0.total_cmp(&b.y0));
    // Each paragraph's rows, and the gap between its last two.
    let mut groups: Vec<(Vec<Row>, Option<f32>)> = Vec::new();
    for r in rows {
        let under = groups
            .iter()
            .enumerate()
            .filter_map(|(i, (g, last_gap))| {
                let p = g.last()?;
                let (lo, hi) = (p.h().min(r.h()), p.h().max(r.h()));
                let gap = r.y0 - p.y1;
                let near = gap <= ROW_GAP * lo
                    && last_gap.is_none_or(|g| gap <= (GAP_GROWTH * g).max(g + 0.25 * lo));
                let lined = (r.x0 - p.x0).abs() <= ALIGN * hi
                    || (r.mid() - p.mid()).abs() <= ALIGN_MID * hi;
                (r.y0 > p.y0 + 0.5 * p.h() && near && hi <= ROW_RATIO * lo && lined)
                    .then_some((i, gap))
            })
            .min_by(|a, b| a.1.total_cmp(&b.1));
        match under {
            Some((i, gap)) => {
                groups[i].0.push(r);
                groups[i].1 = Some(gap);
            }
            None => groups.push((vec![r], None)),
        }
    }
    groups.iter().filter_map(|(g, _)| Block::new(g)).collect()
}

/// Whether `text` has a word: two letters on end.
fn has_word(text: &str) -> bool {
    text.split(|c: char| !c.is_alphabetic())
        .any(|w| w.chars().count() >= 2)
}

/// Text to translate without what is no sentence: hashtags, handles, links, and the "more" that
/// ends a cut post.
fn clean(text: &str) -> String {
    let words: Vec<&str> = text
        .split_whitespace()
        .filter(|w| !w.starts_with('#') && !w.starts_with('@'))
        .filter(|w| {
            let l = w.to_lowercase();
            !(l.contains("http")
                || l.contains("www.")
                || l.ends_with(".com")
                || l.contains(".com/"))
        })
        .collect();
    let mut out = words.join(" ");
    for end in [
        "... devamı",
        "...devamı",
        "... devami",
        "...devami",
        "... more",
        "...more",
    ] {
        if let Some(cut) = out.strip_suffix(end) {
            out = cut.trim_end().to_string();
        }
    }
    out
}

/// How alike two keys are, 0 to 1: the share of their pairs of letters in common (Dice).
fn alike(a: &str, b: &str) -> f32 {
    if a == b {
        return 1.0;
    }
    let pairs = |s: &str| {
        let c: Vec<char> = s.chars().collect();
        let mut p: Vec<(char, char)> = c.windows(2).map(|w| (w[0], w[1])).collect();
        p.sort_unstable();
        p
    };
    let (pa, pb) = (pairs(a), pairs(b));
    if pa.is_empty() || pb.is_empty() {
        return 0.0;
    }
    let (mut i, mut j, mut both) = (0, 0, 0);
    while i < pa.len() && j < pb.len() {
        match pa[i].cmp(&pb[j]) {
            std::cmp::Ordering::Less => i += 1,
            std::cmp::Ordering::Greater => j += 1,
            std::cmp::Ordering::Equal => {
                both += 1;
                i += 1;
                j += 1;
            }
        }
    }
    2.0 * both as f32 / (pa.len() + pb.len()) as f32
}

/// The picture made small, to see whether it changed since it was read.
#[derive(Default)]
struct Thumb {
    w: usize,
    h: usize,
    now: Vec<u8>,
    read: Vec<u8>,
}

impl Thumb {
    /// Whether the picture differs from the one read last: so it does when none was read, or
    /// one of another size (the screen turned).
    fn changed(&mut self, rgba: &[u8], w: usize, h: usize, stride: usize) -> bool {
        let tw = THUMB_W.min(w);
        let th = ((h * tw) as f32 / w as f32).round().max(1.0) as usize;
        if (tw, th) != (self.w, self.h) {
            (self.w, self.h) = (tw, th);
            self.read.clear();
        }
        self.now.resize(tw * th, 0);
        for ty in 0..th {
            let (y0, y1) = (ty * h / th, ((ty + 1) * h / th).max(ty * h / th + 1));
            for tx in 0..tw {
                let (x0, x1) = (tx * w / tw, ((tx + 1) * w / tw).max(tx * w / tw + 1));
                // Every other pixel each way: enough to see a word change.
                let (mut sum, mut n) = (0u32, 0u32);
                for y in (y0..y1).step_by(2) {
                    for x in (x0..x1).step_by(2) {
                        let px = &rgba[y * stride + x * 4..][..3];
                        sum += u32::from(luma(px[0], px[1], px[2]));
                        n += 1;
                    }
                }
                self.now[ty * tw + tx] = (sum / n.max(1)) as u8;
            }
        }
        self.read.len() != self.now.len()
            || self
                .now
                .iter()
                .zip(&self.read)
                .filter(|(a, b)| a.abs_diff(**b) > THUMB_LEVELS)
                .count()
                >= THUMB_PIXELS
    }

    /// The picture now is the one read.
    fn read(&mut self) {
        self.read.clone_from(&self.now);
    }
}

fn luma(r: u8, g: u8, b: u8) -> u8 {
    ((u32::from(r) * 77 + u32::from(g) * 150 + u32::from(b) * 29) >> 8) as u8
}

/// `grey`, `w` by `h`, made `f` times smaller into `out`, each pixel the mean of those it covers.
fn shrink(grey: &[u8], w: usize, h: usize, f: usize, out: &mut Vec<u8>) -> (usize, usize) {
    let (sw, sh) = (w / f, h / f);
    out.resize(sw * sh, 0);
    let n = (f * f) as u32;
    for y in 0..sh {
        for x in 0..sw {
            let mut sum = 0u32;
            for dy in 0..f {
                sum += grey[(y * f + dy) * w + x * f..][..f]
                    .iter()
                    .map(|&v| u32::from(v))
                    .sum::<u32>();
            }
            out[y * sw + x] = (sum / n) as u8;
        }
    }
    (sw, sh)
}

/// `m` of the picture painted in one shade, that of the pixels along its top (its bottom when it
/// is at the top): our captions are not read, and leave no edge that looks like text.
fn blank(grey: &mut [u8], w: usize, h: usize, m: Rect) {
    let (x0, x1) = (m.x0 as usize, (m.x1.ceil() as usize).min(w));
    let (y0, y1) = (m.y0 as usize, (m.y1.ceil() as usize).min(h));
    if x1 <= x0 || y1 <= y0 {
        return;
    }
    let edge = if y0 > 0 { y0 - 1 } else { y1.min(h - 1) };
    let row = &grey[edge * w + x0..edge * w + x1];
    let shade = (row.iter().map(|&v| u32::from(v)).sum::<u32>() / row.len() as u32) as u8;
    for y in y0..y1 {
        grey[y * w + x0..y * w + x1].fill(shade);
    }
}

fn overlaps(a: &Rect, b: &Rect) -> bool {
    a.x0 < b.x1 && b.x0 < a.x1 && a.y0 < b.y1 && b.y0 < a.y1
}

/// What the pixels of `r` look like, as a number: the same for the same row wherever it moved.
fn looks(grey: &[u8], w: usize, r: Rect) -> u64 {
    let (x0, y0, x1, y1) = (r.x0 as usize, r.y0 as usize, r.x1 as usize, r.y1 as usize);
    let mut hash = DefaultHasher::new();
    (x1.saturating_sub(x0), y1.saturating_sub(y0)).hash(&mut hash);
    for y in (y0..y1).step_by(2) {
        for x in (x0..x1).step_by(2) {
            grey[y * w + x].hash(&mut hash);
        }
    }
    hash.finish()
}

/// `line` moved by `dx`, `dy`.
fn moved(mut line: Line, dx: f32, dy: f32) -> Line {
    line.x0 += dx;
    line.x1 += dx;
    line.y0 += dy;
    line.y1 += dy;
    for w in &mut line.words {
        w.1 += dx;
        w.2 += dx;
    }
    line
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
    fn rows_stack_into_paragraphs_and_buttons_stay_apart() {
        // A post, as the detector finds its rows (the middles of the letters, about 20 pixels
        // high for text set 46 apart): its author and time, four rows of text, the reactions
        // under it, its buttons; then the next post's text.
        let lines = vec![
            line("John Carter", 140.0, 326.0, 322.0, 346.0),
            line("3 sa", 136.0, 368.0, 200.0, 386.0),
            line("I can't believe how fast", 40.0, 436.0, 700.0, 454.0),
            line("this year went by. Thank", 40.0, 482.0, 690.0, 504.0),
            line("you all for every-", 40.0, 528.0, 520.0, 550.0),
            line("thing!", 40.0, 574.0, 160.0, 594.0),
            line("1,2 B", 36.0, 634.0, 92.0, 656.0),
            line("234 yorum", 530.0, 634.0, 838.0, 654.0),
            line("Beğen", 98.0, 702.0, 182.0, 726.0),
            line("Yorum Yap", 338.0, 702.0, 486.0, 726.0),
            line("Paylaş", 656.0, 702.0, 744.0, 728.0),
            line("Second post here.", 40.0, 850.0, 400.0, 870.0),
        ];
        let b = blocks(rows(lines));
        let texts: Vec<&str> = b.iter().map(|b| b.text.as_str()).collect();
        assert_eq!(
            texts,
            [
                "John Carter 3 sa",
                "I can't believe how fast this year went by. Thank you all for everything!",
                "234 yorum",
                "Beğen",
                "Yorum Yap",
                "Paylaş",
                "Second post here.",
            ]
        );
        assert_eq!(
            b[1].key,
            "icantbelievehowfastthisyearwentbythankyouallforeverything"
        );
    }

    #[test]
    fn a_count_close_under_a_paragraph_is_left_out_of_it() {
        let b = blocks(rows(vec![
            line(
                "This is the best pizza I have ever had",
                40.0,
                1260.0,
                812.0,
                1280.0,
            ),
            line("1,2 B", 36.0, 1300.0, 92.0, 1320.0),
        ]));
        let texts: Vec<&str> = b.iter().map(|b| b.text.as_str()).collect();
        assert_eq!(texts, ["This is the best pizza I have ever had"]);
    }

    #[test]
    fn pieces_of_a_row_join_but_far_ones_do_not() {
        let r = rows(vec![
            line("over", 300.0, 100.0, 380.0, 130.0),
            line("Look", 100.0, 102.0, 180.0, 132.0),
            line("there", 200.0, 101.0, 290.0, 131.0),
            line("12:30", 800.0, 100.0, 880.0, 130.0),
        ]);
        let texts: Vec<String> = r.iter().map(Row::text).collect();
        assert_eq!(texts, ["Look there over", "12:30"]);
    }

    #[test]
    fn centred_subtitle_rows_stack() {
        let b = blocks(rows(vec![
            line("Where did you put the keys?", 200.0, 1500.0, 700.0, 1522.0),
            line("We have to leave.", 300.0, 1546.0, 600.0, 1568.0),
        ]));
        assert_eq!(b.len(), 1);
        assert_eq!(b[0].text, "Where did you put the keys? We have to leave.");
    }

    #[test]
    fn hashtags_handles_links_and_more_go() {
        assert_eq!(
            clean("@mike Best sunset in Bali #travel #sunset https://x.com/a ... more"),
            "Best sunset in Bali"
        );
        assert_eq!(clean("Bali is amazing ... devamı"), "Bali is amazing");
        assert_eq!(clean("#tags #only"), "");
    }

    #[test]
    fn alike_texts() {
        assert_eq!(alike("hello", "hello"), 1.0);
        assert!(alike("wherediduputthekeys", "wherediduputthekeysx") > 0.9);
        assert!(alike("wherediduputthekeys", "somethingelseentirely") < 0.3);
        assert_eq!(alike("a", "b"), 0.0);
    }

    #[test]
    fn a_paragraph_stands_where_it_stood() {
        let b = |y0: f32, key: &str| Block {
            text: key.into(),
            key: key.into(),
            x0: 40.0,
            y0,
            x1: 700.0,
            y1: y0 + 100.0,
            h: 32.0,
        };
        assert!(b(180.0, "icantbelieve").stands_as(&b(190.0, "icantbelieve")));
        // Scrolled.
        assert!(!b(180.0, "icantbelieve").stands_as(&b(240.0, "icantbelieve")));
        assert!(!b(180.0, "icantbelieve").stands_as(&b(180.0, "somethingelse")));
    }

    #[test]
    fn the_thumbnail_sees_a_word_change_and_not_a_still_picture() {
        let (w, h) = (400, 800);
        let mut rgba = vec![255u8; w * h * 4];
        let mut t = Thumb::default();
        assert!(t.changed(&rgba, w, h, w * 4));
        t.read();
        assert!(!t.changed(&rgba, w, h, w * 4));
        // A dark word, 40 by 12 pixels.
        for y in 300..312 {
            for x in 100..140 {
                rgba[(y * w + x) * 4..][..3].fill(20);
            }
        }
        assert!(t.changed(&rgba, w, h, w * 4));
        // The screen turned.
        t.read();
        assert!(t.changed(&rgba, h, w, h * 4));
    }

    #[test]
    fn shrinking_and_blanking() {
        let (w, h) = (4, 4);
        let grey: Vec<u8> = (0..16).map(|i| i as u8 * 10).collect();
        let mut out = Vec::new();
        assert_eq!(shrink(&grey, w, h, 2, &mut out), (2, 2));
        // (0 + 10 + 40 + 50) / 4.
        assert_eq!(out[0], 25);
        let mut g = vec![100u8; 16];
        g[4..8].fill(7);
        blank(
            &mut g,
            w,
            h,
            Rect {
                x0: 0.0,
                y0: 1.0,
                x1: 4.0,
                y1: 3.0,
            },
        );
        assert_eq!(&g[4..12], &[100; 8]);
    }

    /// The models on phone screenshots made by `tests/page_pictures.py` (run with
    /// HAMMURABI_OCR_DIR set to the models' folder and HAMMURABI_PAGE_DIR to the pictures'):
    /// `cargo test -- --ignored`.
    #[test]
    #[ignore]
    fn reads_a_feed_once_it_stands_still() {
        let dir = std::env::var("HAMMURABI_OCR_DIR").expect("HAMMURABI_OCR_DIR");
        let pics = std::path::PathBuf::from(
            std::env::var("HAMMURABI_PAGE_DIR").expect("HAMMURABI_PAGE_DIR"),
        );
        let (w, h) = (886, 1920);
        let pic = |name: &str| std::fs::read(pics.join(name)).unwrap();
        let (tx, rx) = crossbeam_channel::unbounded();
        let mut read = PageRead::new(tx, Path::new(&dir), 4, "tr").unwrap();
        let lines = |rx: &crossbeam_channel::Receiver<ScreenEvent>| -> Vec<String> {
            rx.try_iter()
                .filter_map(|e| match e {
                    ScreenEvent::Line(t) => Some(t),
                    _ => None,
                })
                .collect()
        };
        // Scrolling: the feed read twice in two places gives nothing.
        let t = Instant::now();
        read.frame_at(&pic("feed_scrolled.rgba"), w, h, w * 4, None, 0.0)
            .unwrap();
        eprintln!("one reading: {:?}", t.elapsed());
        read.frame_at(&pic("feed.rgba"), w, h, w * 4, None, 0.6)
            .unwrap();
        assert_eq!(lines(&rx), Vec::<String>::new());
        // Stopped: the same picture a moment later gives the English posts, and not the
        // Turkish one, the names or the buttons.
        read.frame_at(&pic("feed.rgba"), w, h, w * 4, None, 1.0)
            .unwrap();
        let got = lines(&rx);
        eprintln!("{got:#?}");
        assert_eq!(got.len(), 2, "{got:?}");
        assert!(
            got[0].starts_with("I can't believe how fast this year went by"),
            "{got:?}"
        );
        assert!(got[1].contains("best pizza"), "{got:?}");
        // Still there later: not again.
        read.frame_at(&pic("feed.rgba"), w, h, w * 4, None, 5.0)
            .unwrap();
        assert!(lines(&rx).is_empty());
        // A video with a subtitle: the subtitle and the post's words, but none of the app's.
        read.frame_at(&pic("reel.rgba"), w, h, w * 4, None, 6.0)
            .unwrap();
        read.frame_at(&pic("reel.rgba"), w, h, w * 4, None, 6.5)
            .unwrap();
        let got = lines(&rx);
        eprintln!("{got:#?}");
        assert!(
            got.iter().any(|l| l.contains("we actually made it")),
            "{got:?}"
        );
        assert!(
            got.iter().any(|l| l.starts_with("Best sunset in Bali")),
            "{got:?}"
        );
        assert!(
            !got.iter().any(|l| l.contains('#') || l.contains('@')),
            "{got:?}"
        );
        // Dark mode reads the same.
        let (tx, rx) = crossbeam_channel::unbounded();
        let mut read = PageRead::new(tx, Path::new(&dir), 4, "tr").unwrap();
        read.frame_at(&pic("feed_dark.rgba"), w, h, w * 4, None, 0.0)
            .unwrap();
        read.frame_at(&pic("feed_dark.rgba"), w, h, w * 4, None, 0.4)
            .unwrap();
        let got = lines(&rx);
        eprintln!("{got:#?}");
        assert_eq!(got.len(), 2, "{got:?}");
        assert!(got[0].starts_with("I can't believe how fast"), "{got:?}");
        // Our own captions, masked, are not read.
        let (tx, rx) = crossbeam_channel::unbounded();
        let mut read = PageRead::new(tx, Path::new(&dir), 4, "tr").unwrap();
        let caption = Some([40.0, 1560.0, 846.0, 1720.0]);
        read.frame_at(&pic("captioned.rgba"), w, h, w * 4, caption, 0.0)
            .unwrap();
        read.frame_at(&pic("captioned.rgba"), w, h, w * 4, caption, 0.4)
            .unwrap();
        let got = lines(&rx);
        assert!(
            !got.iter().any(|l| l.contains("Our own caption")),
            "{got:?}"
        );
    }
}

#[cfg(test)]
mod dump {
    use super::*;

    /// Prints the rows and paragraphs read in the pictures of `tests/page_pictures.py`.
    #[test]
    #[ignore]
    fn dump_rows_and_blocks() {
        let dir = std::env::var("HAMMURABI_OCR_DIR").expect("HAMMURABI_OCR_DIR");
        let pics = std::path::PathBuf::from(std::env::var("HAMMURABI_PAGE_DIR").unwrap());
        let (w, h) = (886usize, 1920usize);
        let mut ocr = Ocr::new(Path::new(&dir), 4).unwrap();
        for name in ["feed.rgba", "reel.rgba"] {
            let rgba = std::fs::read(pics.join(name)).unwrap();
            let grey: Vec<u8> = rgba
                .chunks_exact(4)
                .map(|p| luma(p[0], p[1], p[2]))
                .collect();
            let mut small = Vec::new();
            let (sw, sh) = shrink(&grey, w, h, 2, &mut small);
            let t = Instant::now();
            let rects: Vec<Found> = ocr
                .detect(&small, sw, sh)
                .unwrap()
                .into_iter()
                .map(|r| r.scaled(2.0, w, h))
                .collect();
            let td = t.elapsed();
            let t = Instant::now();
            let mut lines = Vec::new();
            for r in &rects {
                lines.extend(ocr.recognize(&grey, w, r.rect).unwrap().map(|l| Line {
                    x0: r.core.x0,
                    y0: r.core.y0,
                    x1: r.core.x1,
                    y1: r.core.y1,
                    ..l
                }));
            }
            eprintln!(
                "== {name}: {} rects, find {:?}, read {:?}",
                rects.len(),
                td,
                t.elapsed()
            );
            for r in rows(lines.clone()) {
                eprintln!(
                    "row {:>5.0} {:>5.0} {:>5.0} {:>5.0} h{:>3.0} {}",
                    r.x0,
                    r.y0,
                    r.x1,
                    r.y1,
                    r.h(),
                    r.text()
                );
            }
            for b in blocks(rows(lines)) {
                eprintln!("block y{:>5.0} h{:>3.0} {}", b.y0, b.h, b.text);
            }
        }
    }
}
