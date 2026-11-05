#!/usr/bin/env python3
"""Resize the owner's unchanged PNG; add a D badge exclusively to debug resources.

Requires Pillow 12.3.0. Run from any directory; --check compares committed pixels.
No generation, recoloring, background flattening or source trimming is performed.
"""
import argparse
from pathlib import Path
from PIL import Image, ImageChops, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'assets/robpublisher/icon.png'
DENSITIES = {'mdpi': 1, 'hdpi': 1.5, 'xhdpi': 2, 'xxhdpi': 3, 'xxxhdpi': 4}


def canvas(source, size, artwork):
    result = Image.new('RGBA', (size, size))
    resized = source.resize((artwork, artwork), Image.Resampling.LANCZOS)
    offset = (size - artwork) // 2
    result.alpha_composite(resized, (offset, offset))
    return result


def badge(image, bounds):
    # Draw at 4x output resolution for antialiasing. The D is a geometric glyph;
    # this needs no host font and never changes the Release artwork.
    factor = 4
    layer = Image.new('RGBA', (image.width * factor, image.height * factor))
    draw = ImageDraw.Draw(layer)
    x, y, side = [v * factor for v in bounds]
    draw.rounded_rectangle((x, y, x + side, y + side), radius=side * .22,
                           fill='#69DCFF', outline='#070D18', width=max(1, round(side * .07)))
    left, top = x + side * .28, y + side * .22
    right, bottom = x + side * .76, y + side * .78
    draw.rounded_rectangle((left, top, right, bottom), radius=side * .23, fill='#070D18')
    draw.rectangle((left, top, left + side * .18, bottom), fill='#070D18')
    draw.rounded_rectangle((left + side * .16, top + side * .12,
                           right - side * .13, bottom - side * .12),
                          radius=side * .1, fill='#69DCFF')
    image.alpha_composite(layer.resize(image.size, Image.Resampling.LANCZOS))
    return image


def generate():
    source = Image.open(SOURCE).convert('RGBA')
    assert source.size == (256, 256)
    assert source.getchannel('A').getextrema()[0] == 0
    for density, scale in DENSITIES.items():
        for variant in ('main', 'debug'):
            # Full artwork in the 66dp safe circle: 46 * sqrt(2) < 66.
            foreground = canvas(source, round(108 * scale), round(46 * scale))
            normal = canvas(source, round(48 * scale), round(44 * scale))
            circular = canvas(source, round(48 * scale), round(32 * scale))
            if variant == 'debug':
                badge(foreground, (60 * scale, 60 * scale, 16 * scale))
                badge(normal, (30 * scale, 30 * scale, 14 * scale))
                badge(circular, (28 * scale, 28 * scale, 10 * scale))
            res = ROOT / f'app/src/{variant}/res'
            yield res / f'drawable-{density}/ic_launcher_foreground.png', foreground
            yield res / f'mipmap-{density}/ic_launcher.png', normal
            yield res / f'mipmap-{density}/ic_launcher_round.png', circular


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    for path, expected in generate():
        if args.check:
            actual = Image.open(path).convert('RGBA')
            assert actual.size == expected.size and ImageChops.difference(actual, expected).getbbox(alpha_only=False) is None, path
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            expected.save(path, optimize=True)
    print('Launcher resources: OK (30 PNGs)')


if __name__ == '__main__':
    main()
