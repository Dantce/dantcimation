"""Draws the impact-frame textures that ship with Dantcimation.

Every texture is white on transparent, with hard edges and few pixels: a billboard's colour
tints it (so one drawing serves as black, red or white), and nearest-neighbour scaling keeps
it as blocky as the world it is put in.

    python3 tools/dantcimation/impact_textures.py

writes into src/client/resources/assets/bbs/assets/textures/dantcimation/, which the game
reads as assets:textures/dantcimation/<name>.png.
"""

import math
import os
import random

from PIL import Image

OUT = os.path.join(os.path.dirname(__file__), "..", "..", "src", "client", "resources", "assets", "bbs", "assets", "textures", "dantcimation")


def teeth(count, low, high, seed):
    """One random length per tooth, around the full circle."""
    rng = random.Random(seed)

    return [rng.uniform(low, high) for _ in range(count)]


def tooth(lengths, angle, sharpness=1.0):
    """How far the tooth at this angle reaches: its length at the tip, nothing at its sides."""
    turn = (angle / (2 * math.pi)) % 1.0 * len(lengths)
    index = int(turn) % len(lengths)
    across = turn - int(turn)

    return lengths[index] * (1.0 - abs(2.0 * across - 1.0)) ** sharpness


def draw(name, width, height, inside):
    image = Image.new("RGBA", (width, height), (255, 255, 255, 0))

    for y in range(height):
        for x in range(width):
            nx = (x + 0.5) / width * 2.0 - 1.0
            ny = (y + 0.5) / height * 2.0 - 1.0

            if inside(nx, ny, math.atan2(ny, nx)):
                image.putpixel((x, y), (255, 255, 255, 255))

    image.save(os.path.join(OUT, name + ".png"))


def spikes():
    """Jagged spikes reaching in from the edges of a 16:9 frame; the middle is left open."""
    lengths = teeth(46, 0.06, 0.52, 11)

    def inside(nx, ny, angle):
        return max(abs(nx), abs(ny)) > 1.0 - 0.07 - tooth(lengths, angle, 1.3)

    draw("impact_spikes", 96, 54, inside)


def burst():
    """A many-pointed star: the flash behind a hit."""
    lengths = teeth(16, 0.30, 0.80, 5)

    def inside(nx, ny, angle):
        return math.hypot(nx, ny) < 0.22 + tooth(lengths, angle, 1.15)

    draw("impact_burst", 48, 48, inside)


def lines():
    """Thin speed lines pointing at the middle, which they do not reach."""
    rng = random.Random(23)
    wedges = [(rng.uniform(0, 2 * math.pi), rng.uniform(0.010, 0.034), rng.uniform(0.34, 0.66)) for _ in range(34)]

    def inside(nx, ny, angle):
        radius = math.hypot(nx, ny)

        for at, half, start in wedges:
            off = abs((angle - at + math.pi) % (2 * math.pi) - math.pi)

            if radius > start and off < half * min(1.0, (radius - start) * 4.0 + 0.25):
                return True

        return False

    draw("impact_lines", 96, 54, inside)


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    spikes()
    burst()
    lines()
