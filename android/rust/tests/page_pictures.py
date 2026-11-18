"""Phone screenshots for the ignored test of reading the screen (src/page.rs), 886 by 1920 as the
app captures a 1080 by 2340 phone: a Facebook-like feed in Turkish with two English posts and a
Turkish one, the same feed scrolled, an Instagram-like video with a subtitle, and the feed with
one of our own captions over it. Needs Pillow and NumPy.

    python3 tests/page_pictures.py OUT_DIR
    HAMMURABI_OCR_DIR=<models> HAMMURABI_PAGE_DIR=OUT_DIR cargo test -- --ignored
"""
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

W, H = 886, 1920


def font(bold, size):
    names = ["Inter-Bold.otf", "DejaVuSans-Bold.ttf"] if bold else ["Inter-Regular.otf", "DejaVuSans.ttf"]
    for name in names:
        for folder in ["", "/usr/share/fonts/opentype/inter/", "/usr/share/fonts/truetype/dejavu/"]:
            try:
                return ImageFont.truetype(folder + name, size)
            except OSError:
                pass
    raise SystemExit("no font found")


def wrap(draw, text, f, width):
    rows, row = [], ""
    for word in text.split():
        trial = (row + " " + word).strip()
        if draw.textlength(trial, font=f) <= width:
            row = trial
        else:
            rows.append(row)
            row = word
    return rows + [row]


INK, GREY, LINE = (5, 5, 5), (101, 103, 107), (228, 230, 235)


def post(draw, y, name, when, text):
    draw.ellipse((30, y + 10, 110, y + 90), fill=(200, 205, 215))
    draw.text((130, y + 12), name, font=font(True, 34), fill=INK)
    draw.text((130, y + 56), when, font=font(False, 28), fill=GREY)
    y += 120
    f = font(False, 36)
    for row in wrap(draw, text, f, W - 60):
        draw.text((30, y), row, font=f, fill=INK)
        y += 46
    y += 20
    draw.text((30, y), "1,2 B", font=font(False, 28), fill=GREY)
    draw.text((520, y), "234 yorum · 18 paylaşım", font=font(False, 28), fill=GREY)
    y += 50
    draw.line((30, y, W - 30, y), fill=LINE, width=2)
    y += 18
    for x, label in [(90, "Beğen"), (330, "Yorum Yap"), (650, "Paylaş")]:
        draw.text((x, y), label, font=font(True, 30), fill=GREY)
    y += 60
    draw.rectangle((0, y, W, y + 16), fill=(201, 204, 209))
    return y + 30


def feed(shift):
    img = Image.new("RGB", (W, H), "white")
    draw = ImageDraw.Draw(img)
    y = 70 + shift
    draw.text((30, y), "facebook", font=font(True, 52), fill=(8, 102, 255))
    y += 100
    draw.rounded_rectangle((30, y, W - 30, y + 80), radius=40, fill=(240, 242, 245))
    draw.text((60, y + 20), "Ne düşünüyorsun?", font=font(False, 34), fill=GREY)
    y += 100
    draw.rectangle((0, y, W, y + 16), fill=(201, 204, 209))
    y += 30
    y = post(draw, y, "John Carter", "3 sa · Herkese açık",
             "I can't believe how fast this year went by. Thank you all for being part of this journey, see you next year!")
    y = post(draw, y, "Ayşe Yılmaz", "5 sa",
             "Bugün arkadaşlarla harika bir akşam geçirdik, herkese çok teşekkür ederim!")
    y = post(draw, y, "Mike Johnson", "1 g", "This is the best pizza I have ever had in my life")
    # The status bar stays put while the feed scrolls under it.
    draw.rectangle((0, 0, W, 60), fill="white")
    draw.text((30, 12), "14:32", font=font(True, 28), fill=INK)
    draw.text((W - 110, 12), "%85", font=font(True, 28), fill=INK)
    return img


def reel():
    rng = np.random.default_rng(3)
    scene = (rng.random((H // 8, W // 8, 3)) * 110 + 20).astype(np.uint8)
    img = Image.fromarray(scene).resize((W, H), Image.BILINEAR).filter(ImageFilter.GaussianBlur(10))
    draw = ImageDraw.Draw(img)
    draw.text((30, 70), "Reels", font=font(True, 44), fill="white")
    f = font(True, 46)
    rows = wrap(draw, "I can't believe we actually made it", f, W - 120)
    for i, row in enumerate(rows):
        x = (W - draw.textlength(row, font=f)) / 2
        draw.text((x, 1150 + i * 60), row, font=f, fill="white", stroke_width=3, stroke_fill="black")
    draw.text((790, 1300), "12,4 B", font=font(True, 28), fill="white")
    draw.text((805, 1450), "345", font=font(True, 28), fill="white")
    draw.ellipse((30, 1610, 90, 1670), fill=(230, 230, 230))
    draw.text((110, 1620), "traveler_mike", font=font(True, 32), fill="white")
    draw.rounded_rectangle((350, 1615, 480, 1665), radius=10, outline="white", width=2)
    draw.text((365, 1622), "Takip et", font=font(True, 28), fill="white")
    draw.text((30, 1700), "Best sunset in Bali #travel #sunset", font=font(False, 30), fill="white")
    draw.text((30, 1752), "Orijinal ses", font=font(False, 26), fill="white")
    draw.rectangle((0, 1820, W, H), fill="black")
    return img


def captioned():
    img = feed(0)
    draw = ImageDraw.Draw(img, "RGBA")
    draw.rounded_rectangle((40, 1560, W - 40, 1720), radius=16, fill=(0, 0, 0, 200))
    f = font(True, 40)
    for i, row in enumerate(["Our own caption should not", "be read again by the reader"]):
        x = (W - draw.textlength(row, font=f)) / 2
        draw.text((x, 1580 + i * 60), row, font=f, fill="white")
    return img


out = Path(sys.argv[1])
out.mkdir(parents=True, exist_ok=True)
for name, img in [("feed", feed(0)), ("feed_scrolled", feed(120)), ("reel", reel()), ("captioned", captioned())]:
    (out / f"{name}.rgba").write_bytes(img.convert("RGBA").tobytes())
    img.save(out / f"{name}.png")
