#!/usr/bin/env python3
"""Convert the P6 PPM frames a scene dump writes into PNGs, scaled up with nearest-neighbour.

usage: ppm2png.py SCALE SRC_DIR DST_DIR
"""
import os, struct, sys, zlib


def chunk(tag, data):
    body = tag + data
    return struct.pack('>I', len(data)) + body + struct.pack('>I', zlib.crc32(body) & 0xFFFFFFFF)


def convert(src, dst, scale):
    with open(src, 'rb') as f:
        magic, w, h, depth = f.read().split(maxsplit=3)
        w, h = int(w), int(h)
        pixels = depth[len(depth.split(b'\n', 1)[0]) + 1:] if b'\n' in depth else depth
        if magic != b'P6' or len(pixels) < w * h * 3:
            raise SystemExit(f'{src}: not a P6 PPM of {w}x{h}')
    rows = []
    for y in range(h):
        row = pixels[y * w * 3:(y + 1) * w * 3]
        wide = b''.join(row[x * 3:x * 3 + 3] * scale for x in range(w))
        rows.extend([b'\x00' + wide] * scale)
    png = b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', w * scale, h * scale, 8, 2, 0, 0, 0))
    png += chunk(b'IDAT', zlib.compress(b''.join(rows), 9)) + chunk(b'IEND', b'')
    with open(dst, 'wb') as f:
        f.write(png)


if __name__ == '__main__':
    scale, src_dir, dst_dir = int(sys.argv[1]), sys.argv[2], sys.argv[3]
    os.makedirs(dst_dir, exist_ok=True)
    for name in sorted(os.listdir(src_dir)):
        if name.endswith('.ppm'):
            convert(os.path.join(src_dir, name), os.path.join(dst_dir, name[:-4] + '.png'), scale)
            print(name[:-4] + '.png')
