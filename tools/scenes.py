"""Renders the home backgrounds for the Figma concept: soft glows, no hard edges, dithered to avoid banding.
Usage: python3 scenes.py <day.png> <night.png> <output dir>. Sky colours are sampled from the Figma captures."""
import sys

import numpy as np
from PIL import Image, ImageDraw

W, H = 1920, 1080
YY, XX = np.mgrid[0:H, 0:W].astype(np.float32)


def corners(path):
    img = np.asarray(Image.open(path).convert("RGB")).astype(np.float32)
    h, w, _ = img.shape
    pick = lambda x, y: img[int(y * h), int(x * w)]
    return pick(0.01, 0.01), pick(0.99, 0.01), pick(0.01, 0.55), pick(0.99, 0.55)


def sky(tl, tr, bl, br):
    u, v = (XX / W)[..., None], np.clip(YY / (H * 0.62), 0, 1)[..., None]
    return (tl * (1 - u) + tr * u) * (1 - v) + (bl * (1 - u) + br * u) * v


def glow(img, x, y, sx, sy, colour, amount):
    g = np.exp(-(((XX - x) / sx) ** 2 + ((YY - y) / sy) ** 2) / 2)[..., None]
    return img + (np.array(colour, np.float32) - img) * g * amount


def disc(img, x, y, r, colour):
    a = np.clip(r - np.hypot(XX - x, YY - y) + 0.5, 0, 1)[..., None]
    return img * (1 - a) + np.array(colour, np.float32) * a


RANGES = [
    ((0, 760), (150, 700), (280, 730), (494, 562), (640, 690), (844, 534), (1030, 694), (1200, 605),
     (1370, 690), (1590, 566), (1790, 680), (1920, 626)),
    ((0, 830), (260, 760), (500, 690), (700, 770), (940, 640), (1180, 760), (1420, 690), (1680, 780), (1920, 720)),
    ((0, 900), (340, 830), (720, 880), (1120, 810), (1500, 870), (1920, 815)),
]


def mountains(img, colours):
    for points, colour in zip(RANGES, colours):
        mask = Image.new("L", (W * 2, H * 2), 0)
        polygon = [(x * 2, y * 2) for x, y in points] + [(W * 2, H * 2), (0, H * 2)]
        ImageDraw.Draw(mask).polygon(polygon, fill=255)
        a = np.asarray(mask.resize((W, H), Image.LANCZOS)).astype(np.float32)[..., None] / 255
        img = img * (1 - a) + np.array(colour, np.float32) * a
    return img


def finish(img, seed):
    floor = np.array((11, 16, 33), np.float32)
    t = np.clip((YY - 600) / 330, 0, 1)[..., None]
    img = img * (1 - t * t * (3 - 2 * t)) + floor * (t * t * (3 - 2 * t))
    left = np.clip(1 - XX / 1250, 0, 1)[..., None] * np.clip(1 - YY / 900, 0, 1)[..., None]
    img = img * (1 - left * 0.34)
    img += np.random.default_rng(seed).uniform(-1.2, 1.2, img.shape)
    return Image.fromarray(np.clip(img, 0, 255).astype(np.uint8))


def afternoon(c):
    img = sky(*c)
    img = glow(img, 1090, 470, 560, 300, (205, 125, 112), 0.62)
    img = glow(img, 1090, 520, 240, 170, (250, 190, 150), 0.5)
    img = glow(img, 1090, 505, 80, 80, (255, 222, 176), 0.7)
    img = disc(img, 1090, 505, 44, (255, 232, 196))
    return finish(mountains(img, ((50, 58, 98), (36, 46, 84), (22, 31, 60))), 1)


def morning(c):
    img = sky((58, 76, 128), (96, 124, 176), (150, 132, 150), (232, 176, 138))
    img = glow(img, 960, 540, 620, 280, (252, 200, 150), 0.7)
    img = glow(img, 960, 520, 90, 90, (255, 240, 205), 0.8)
    img = disc(img, 960, 520, 42, (255, 246, 222))
    return finish(mountains(img, ((74, 84, 122), (50, 60, 100), (28, 36, 68))), 2)


def night(c):
    img = sky(*c)
    img = glow(img, 1430, 300, 560, 300, (70, 92, 160), 0.6)
    img = glow(img, 1320, 195, 160, 160, (150, 165, 215), 0.55)
    img = disc(img, 1320, 195, 46, (236, 240, 255))
    rng = np.random.default_rng(3)
    for _ in range(90):
        x, y = rng.uniform(0, W), rng.uniform(0, 540)
        if np.hypot(x - 1320, y - 195) > 190:
            img = disc(img, x, y, rng.choice((0.8, 1.1, 1.6)), (205, 214, 240))
    return finish(mountains(img, ((30, 44, 82), (21, 33, 66), (14, 22, 46))), 4)


if __name__ == "__main__":
    day_path, night_path, out = sys.argv[1:4]
    afternoon(corners(day_path)).save(f"{out}/scene_afternoon.webp", quality=88, method=6)
    morning(None).save(f"{out}/scene_morning.webp", quality=88, method=6)
    night(corners(night_path)).save(f"{out}/scene_night.webp", quality=88, method=6)
