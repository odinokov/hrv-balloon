#!/usr/bin/env python3
"""Generate the adaptive launcher icon from a 27×27 pixel map on the 108-dp canvas.

Writes res/drawable/ic_launcher_{background,foreground,monochrome}.xml and res/mipmap-anydpi-v26/ic_launcher.xml,
plus a masked preview PNG when a path is given as the first argument.
"""
import os, struct, sys, zlib

R = os.path.join(os.path.dirname(__file__), '..', 'app', 'src', 'main', 'res')
U = 4   # dp per pixel
N = 27  # pixels per side (108 dp); launchers show the central 72 dp, the 66-dp circle is the safe zone
SKY = ['#17153A', '#2B2160', '#4A2D78', '#6F3A84', '#9A4A86', '#C75E7A', '#E8806A', '#F9C286']
CORAL, CREAM, DARK, WA, WB = '#E2574B', '#FFF1D6', '#3A2A22', '#9C6B3C', '#7A4F28'
GROUND, GRASS, HILL = '#3F7D4A', '#7FB56C', '#2A5A66'

cx, cy, rad = 13, 11, 5

# ---- background: quiet sunset and rolling hills ----
bg = {}
for y in range(N):
    f = y / 18 * (len(SKY) - 1)
    for x in range(N):
        bg[(x, y)] = SKY[min(round(f), len(SKY) - 1)]
hill = [19, 19, 18, 18, 17, 17, 17, 18, 18, 19, 19, 20, 20, 20, 19, 19, 18, 18, 18, 19, 19, 20, 20, 19, 19, 18, 18]
for x, top in enumerate(hill):
    for y in range(top, N):
        bg[(x, y)] = HILL if y < 21 else GRASS if y == 21 else GROUND

# ---- foreground: checkered balloon (2×2 cells) sized for the safe zone: rows 6..20 = 24..84 dp ----
fg = {}
def inside(x, y):
    dx, dy = x - cx, y - cy
    if dy <= 0:
        return dx * dx + dy * dy <= rad * rad + rad   # midpoint-circle fill: 5-7-9-11-11-11 wide
    if dy == 5:
        return abs(dx) <= 1                           # mouth, so the outline wraps around it
    return abs(dx) <= rad - dy + 1 and dy <= 4        # cone to the throat: 11-9-7-5 wide
for y in range(cy - rad, cy + 7):
    for x in range(N):
        if inside(x, y):
            edge = not all(inside(x + ex, y + ey) for ex, ey in ((1, 0), (-1, 0), (0, 1), (0, -1)))
            fg[(x, y)] = DARK if edge else (CORAL if ((x - cx + 100) // 2 + (y - cy + 100) // 2) % 2 == 0 else CREAM)
for y in (16, 17):
    fg[(cx - 2, y)] = DARK; fg[(cx + 2, y)] = DARK                       # ropes
for y in range(18, 21):
    for x in range(cx - 2, cx + 3):
        fg[(x, y)] = DARK if y == 20 or x in (cx - 2, cx + 2) else (WA if (x + y) % 2 == 0 else WB)

def runs(cells):
    """Merge horizontally adjacent cells into (x, y, length) runs: fewer path segments for the launcher to rasterise."""
    out = []
    for x, y in sorted(cells, key=lambda c: (c[1], c[0])):
        if out and out[-1][1] == y and out[-1][0] + out[-1][2] == x:
            out[-1][2] += 1
        else:
            out.append([x, y, 1])
    return out

def vector(path, grid, mono=False):
    by = {}
    for (x, y), c in grid.items():
        by.setdefault('#000000' if mono else c, []).append((x, y))
    paths = ''.join(
        f'    <path android:fillColor="{c}" android:pathData="{" ".join(f"M{x * U},{y * U}h{n * U}v{U}h-{n * U}z" for x, y, n in runs(cells))}"/>\n'
        for c, cells in by.items()
    )
    with open(path, 'w') as f:
        f.write('<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
                '    android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">\n'
                + paths + '</vector>\n')

os.makedirs(f'{R}/drawable', exist_ok=True)
os.makedirs(f'{R}/mipmap-anydpi-v26', exist_ok=True)
vector(f'{R}/drawable/ic_launcher_background.xml', bg)
vector(f'{R}/drawable/ic_launcher_foreground.xml', fg)
vector(f'{R}/drawable/ic_launcher_monochrome.xml', fg, mono=True)
with open(f'{R}/mipmap-anydpi-v26/ic_launcher.xml', 'w') as f:
    f.write('<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
            '    <background android:drawable="@drawable/ic_launcher_background"/>\n'
            '    <foreground android:drawable="@drawable/ic_launcher_foreground"/>\n'
            '    <monochrome android:drawable="@drawable/ic_launcher_monochrome"/>\n'
            '</adaptive-icon>\n')

if len(sys.argv) > 1:   # preview: circular mask over the central 72 dp, 6× nearest-neighbour
    S = 6
    W = N * U * S
    raw = bytearray()
    for py in range(W):
        raw += b'\x00'
        for px in range(W):
            xd, yd = px / S, py / S
            if (xd - 54) ** 2 + (yd - 54) ** 2 > 36 ** 2:
                raw += b'\x1e\x1e\x1e'
                continue
            cell = (int(xd // U), int(yd // U))
            c = fg.get(cell) or bg[cell]
            raw += bytes(int(c[i:i + 2], 16) for i in (1, 3, 5))
    def chunk(t, b):
        return struct.pack('>I', len(b)) + t + b + struct.pack('>I', zlib.crc32(t + b) & 0xffffffff)
    with open(sys.argv[1], 'wb') as f:
        f.write(b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', W, W, 8, 2, 0, 0, 0))
                + chunk(b'IDAT', zlib.compress(bytes(raw), 9)) + chunk(b'IEND', b''))
