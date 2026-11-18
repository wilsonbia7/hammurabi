//! Subtitles on screen on a phone. The app sends what Android's screen capture gives, about ten
//! pictures a second (`Native.screenFrame`); the subtitle band is cut out of each and read as the
//! desktop reads a game's window (`screen::reader`), with PP-OCR (`ocr`) in place of Windows'
//! recognizer, and what settles goes to the engine through `Fed::screen`.

use std::path::Path;
use std::time::Instant;

use crossbeam_channel::Sender;
use hammurabi::screen::reader::{Line, Reader};
use hammurabi::screen::{Area, Place, ScreenEvent, Spot};

use crate::ocr::Ocr;

/// Pictures on end the recognizer fails on before reading stops with the error.
const FAILS_MAX: u32 = 50;

pub struct ScreenRead {
    tx: Sender<ScreenEvent>,
    band: Area,
    ocr: Ocr,
    /// The band's size, and its reader.
    reader: Option<(usize, usize, Reader)>,
    /// The two pictures read last and their lines: a picture that stands still is read once.
    read: [(Vec<u8>, Vec<Line>); 2],
    /// The band, four bytes a pixel as the reader takes them (blue, green, red, alpha).
    bgra: Vec<u8>,
    t0: Instant,
    ready: bool,
    place: Place,
    fails: u32,
}

impl ScreenRead {
    /// Reads `band` of each picture, with the recognizer's models from `models`.
    pub fn new(
        tx: Sender<ScreenEvent>,
        band: Area,
        models: &Path,
        threads: usize,
    ) -> anyhow::Result<Self> {
        Ok(Self {
            tx,
            band,
            ocr: Ocr::new(models, threads)?,
            reader: None,
            read: Default::default(),
            bgra: Vec::new(),
            t0: Instant::now(),
            ready: false,
            place: Place::default(),
            fails: 0,
        })
    }

    /// One picture, `width` by `height`, as Android gives it: rows of `stride` bytes, four bytes a
    /// pixel (red, green, blue, alpha). Fails, and tells the engine, when the picture is not that
    /// size or the recognizer failed on `FAILS_MAX` on end.
    pub fn frame(
        &mut self,
        rgba: &[u8],
        width: usize,
        height: usize,
        stride: usize,
    ) -> anyhow::Result<()> {
        let result = self.read_frame(rgba, width, height, stride);
        if let Err(e) = &result {
            let _ = self.tx.send(ScreenEvent::Error(format!("{e:#}")));
        }
        result
    }

    fn read_frame(
        &mut self,
        rgba: &[u8],
        width: usize,
        height: usize,
        stride: usize,
    ) -> anyhow::Result<()> {
        anyhow::ensure!(
            width > 0
                && height > 0
                && stride >= width * 4
                && rgba.len() >= stride * (height - 1) + width * 4,
            "a picture smaller than its size"
        );
        if !self.ready {
            self.ready = true;
            let _ = self.tx.send(ScreenEvent::Ready(
                hammurabi::i18n::t(hammurabi::i18n::T::TheMainDisplay).to_string(),
            ));
        }
        let [x0, y0, x1, y1] = self.band.of([0, 0, width as i32, height as i32]);
        let (x0, x1) = (x0.max(0) as usize, (x1.max(0) as usize).min(width));
        let (y0, y1) = (y0.max(0) as usize, (y1.max(0) as usize).min(height));
        let (bw, bh) = (x1.saturating_sub(x0), y1.saturating_sub(y0));
        if bw < 16 || bh < 16 {
            return Ok(());
        }
        self.bgra.clear();
        self.bgra.reserve(bw * bh * 4);
        for y in y0..y1 {
            for px in rgba[y * stride + x0 * 4..y * stride + x1 * 4].chunks_exact(4) {
                self.bgra.extend_from_slice(&[px[2], px[1], px[0], px[3]]);
            }
        }
        let reader = match &mut self.reader {
            Some((w, h, reader)) if (*w, *h) == (bw, bh) => reader,
            slot => &mut slot.insert((bw, bh, Reader::new(bw, bh))).2,
        };
        let (w, h) = reader.size();
        let (prepared, plain) = reader.prepare(&self.bgra);
        // The prepared picture's rows are found in it and read in the plain one (`read_in`), so
        // either changing reads them again.
        let fresh_plain = plain != self.read[1].0.as_slice();
        let fresh_prepared = fresh_plain || prepared != self.read[0].0.as_slice();
        let mut failed = None;
        if fresh_prepared {
            match self.ocr.read_in(prepared, plain, w, h) {
                Ok(lines) => self.read[0] = (prepared.to_vec(), lines),
                Err(e) => failed = Some(e),
            }
        }
        if fresh_plain && failed.is_none() {
            match self.ocr.read(plain, w, h) {
                Ok(lines) => self.read[1] = (plain.to_vec(), lines),
                Err(e) => failed = Some(e),
            }
        }
        if let Some(e) = failed {
            // Read again with the next picture; only many on end stop the reading.
            self.fails += 1;
            if self.fails >= FAILS_MAX {
                return Err(e);
            }
            log::warn!("screen: a picture could not be read: {e:#}");
            return Ok(());
        }
        self.fails = 0;
        let at = self.t0.elapsed().as_secs_f64();
        if let Some((_, line)) = reader.read(at, &self.read[0].1, &self.read[1].1) {
            log::debug!("screen: {line:?}");
            let _ = self.tx.send(ScreenEvent::Line(line));
        }
        let now = Place {
            picture: Some([0, 0, width as i32, height as i32]),
            subtitle: reader.spot().map(|s| Spot {
                area: self.band.outer(s.area),
                row: s.row * (self.band.bottom - self.band.top),
            }),
        };
        if now != self.place {
            self.place = now;
            let _ = self.tx.send(ScreenEvent::Place(now));
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A subtitle shown for a second in pictures made by `tests/ocr_band.py`, read as the app
    /// sends them (run with HAMMURABI_OCR_DIR and HAMMURABI_OCR_FRAME set): `cargo test --
    /// --ignored`.
    #[test]
    #[ignore]
    fn a_subtitle_on_screen_reaches_the_engine_once() {
        let dir = std::env::var("HAMMURABI_OCR_DIR").expect("HAMMURABI_OCR_DIR");
        let frame =
            std::fs::read(std::env::var("HAMMURABI_OCR_FRAME").expect("HAMMURABI_OCR_FRAME"))
                .unwrap();
        let blank = vec![0u8; frame.len()];
        let (w, h) = (1920, 1080);
        let (tx, rx) = crossbeam_channel::unbounded();
        let band = Area {
            left: 0.0,
            top: 0.72,
            right: 1.0,
            bottom: 1.0,
        };
        let mut read = ScreenRead::new(tx, band, Path::new(&dir), 2).unwrap();
        // Ten pictures a second: the subtitle for a second, then nothing for a second.
        for i in 0..20 {
            let picture = if i < 10 { &frame } else { &blank };
            read.frame(picture, w, h, w * 4).unwrap();
            std::thread::sleep(std::time::Duration::from_millis(100));
        }
        let events: Vec<ScreenEvent> = rx.try_iter().collect();
        assert!(matches!(events.first(), Some(ScreenEvent::Ready(_))));
        let lines: Vec<&str> = events
            .iter()
            .filter_map(|e| match e {
                ScreenEvent::Line(l) => Some(l.as_str()),
                _ => None,
            })
            .collect();
        assert_eq!(
            lines,
            ["Where did you put the keys? We have to leave before dawn."]
        );
        assert!(
            events
                .iter()
                .any(|e| matches!(e, ScreenEvent::Place(p) if p.subtitle.is_some()))
        );
    }
}
