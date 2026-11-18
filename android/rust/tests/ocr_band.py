"""Pictures for the ignored OCR tests (src/ocr.rs, src/screen.rs): a two-row subtitle, white with
a black edge, over a blurred noisy scene. Needs Pillow and NumPy.

    python3 tests/ocr_band.py OUT_DIR
    HAMMURABI_OCR_DIR=<models> HAMMURABI_OCR_BAND=OUT_DIR/band.grey \\
    HAMMURABI_OCR_FRAME=OUT_DIR/frame.rgba cargo test -- --ignored
"""
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROWS = ["Where did you put the keys?", "We have to leave before dawn."]


def picture(width, height, top):
    rng = np.random.default_rng(1)
    scene = (rng.random((height, width, 3)) * 120 + 60).astype(np.uint8)
    img = Image.fromarray(scene).filter(ImageFilter.GaussianBlur(6))
    draw = ImageDraw.Draw(img)
    font = ImageFont.truetype("DejaVuSans-Bold.ttf", 40)
    for i, row in enumerate(ROWS):
        x = (width - draw.textlength(row, font=font)) / 2
        draw.text((x, top + i * 55), row, font=font, fill=(255, 255, 255), stroke_width=3, stroke_fill=(0, 0, 0))
    return img


out = Path(sys.argv[1])
out.mkdir(parents=True, exist_ok=True)
(out / "band.grey").write_bytes(picture(1920, 300, 150).convert("L").tobytes())
(out / "frame.rgba").write_bytes(picture(1920, 1080, 930).convert("RGBA").tobytes())
