#!/usr/bin/env python3
"""Generate synthetic PNGs and verify the installed screen-space checker."""

import argparse
import os
from pathlib import Path
import struct
import subprocess
import tempfile
import zlib

PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"
WIDTH = 320
HEIGHT = 180
MATCH_PIXEL = (64, 64, 64, 255)
DIFFERENCE_PIXEL = (255, 32, 192, 255)


def chunk(kind, data):
    checksum = zlib.crc32(kind + data) & 0xFFFFFFFF
    return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", checksum)


def write_rgba_png(path, pixels, text=None):
    rows = bytearray()
    for y in range(HEIGHT):
        rows.append(0)
        for x in range(WIDTH):
            rows.extend(pixels[y * WIDTH + x])

    header = struct.pack(">IIBBBBB", WIDTH, HEIGHT, 8, 6, 0, 0, 0)
    data = bytearray(PNG_SIGNATURE)
    data.extend(chunk(b"IHDR", header))
    if text is not None:
        data.extend(chunk(b"tEXt", b"purpose\0" + text.encode("ascii")))
    data.extend(chunk(b"IDAT", zlib.compress(rows, level=9)))
    data.extend(chunk(b"IEND", b""))
    path.write_bytes(data)


def paeth(left, above, upper_left):
    estimate = left + above - upper_left
    distances = (abs(estimate - left), abs(estimate - above), abs(estimate - upper_left))
    return (left, above, upper_left)[distances.index(min(distances))]


def read_rgba_png(path):
    data = path.read_bytes()
    if not data.startswith(PNG_SIGNATURE):
        raise AssertionError(f"Checker output is not a PNG: {path}")

    offset = len(PNG_SIGNATURE)
    compressed = bytearray()
    header = None
    ended = False
    while offset < len(data):
        if len(data) - offset < 12:
            raise AssertionError(f"Truncated PNG chunk in {path}")
        length = struct.unpack_from(">I", data, offset)[0]
        kind = data[offset + 4:offset + 8]
        start = offset + 8
        end = start + length
        if end + 4 > len(data):
            raise AssertionError(f"Truncated {kind!r} chunk in {path}")
        payload = data[start:end]
        expected_crc = struct.unpack_from(">I", data, end)[0]
        actual_crc = zlib.crc32(kind + payload) & 0xFFFFFFFF
        if expected_crc != actual_crc:
            raise AssertionError(f"Bad {kind.decode('ascii')} checksum in {path}")
        if kind == b"IHDR":
            header = struct.unpack(">IIBBBBB", payload)
        elif kind == b"IDAT":
            compressed.extend(payload)
        elif kind == b"IEND":
            ended = True
            offset = end + 4
            break
        offset = end + 4

    if not ended or offset != len(data) or header is None:
        raise AssertionError(f"Incomplete PNG output: {path}")
    width, height, depth, color_type, compression, filtering, interlace = header
    if (depth, color_type, compression, filtering, interlace) != (8, 6, 0, 0, 0):
        raise AssertionError(f"Unexpected PNG output format in {path}: {header}")

    decoded = zlib.decompress(compressed)
    stride = width * 4
    if len(decoded) != height * (stride + 1):
        raise AssertionError(f"Unexpected decoded PNG size in {path}")
    pixels = bytearray(width * height * 4)
    previous = bytearray(stride)
    cursor = 0
    for y in range(height):
        filter_type = decoded[cursor]
        cursor += 1
        row = bytearray(decoded[cursor:cursor + stride])
        cursor += stride
        if filter_type > 4:
            raise AssertionError(f"Unsupported PNG row filter {filter_type} in {path}")
        for index in range(stride):
            left = row[index - 4] if index >= 4 else 0
            above = previous[index]
            upper_left = previous[index - 4] if index >= 4 else 0
            if filter_type == 1:
                predictor = left
            elif filter_type == 2:
                predictor = above
            elif filter_type == 3:
                predictor = (left + above) // 2
            elif filter_type == 4:
                predictor = paeth(left, above, upper_left)
            else:
                predictor = 0
            row[index] = (row[index] + predictor) & 0xFF
        pixels[y * stride:(y + 1) * stride] = row
        previous = row
    return width, height, pixels


def verify_mask(path, different_pixels):
    width, height, pixels = read_rgba_png(path)
    if (width, height) != (WIDTH, HEIGHT):
        raise AssertionError(f"Unexpected mask dimensions: {width} x {height}")
    changed = 0
    for y in range(HEIGHT):
        for x in range(WIDTH):
            expected = (DIFFERENCE_PIXEL if different_pixels(x, y) else MATCH_PIXEL)
            offset = (y * WIDTH + x) * 4
            actual = tuple(pixels[offset:offset + 4])
            if actual != expected:
                raise AssertionError(f"Unexpected mask pixel at ({x}, {y}): {actual}")
            changed += actual == DIFFERENCE_PIXEL
    return changed


def run_checker(executable, reference, candidate, output, expected_status):
    command = [
        str(executable), "--opengl", str(reference), "--vulkan", str(candidate),
        "--output", str(output),
    ]
    result = subprocess.run(command, capture_output=True, text=True, check=False)
    if result.stdout:
        print(result.stdout, end="" if result.stdout.endswith("\n") else "\n")
    if result.stderr:
        print(result.stderr, end="" if result.stderr.endswith("\n") else "\n")
    if result.returncode != expected_status:
        raise AssertionError(
            f"Checker exited {result.returncode}; expected {expected_status}: {' '.join(command)}"
        )
    print(f"Verified checker exit status {result.returncode}.")


def main():
    app_dir = Path(__file__).resolve().parents[1]
    default_checker = app_dir / "build/install/screen-space-check/bin/screen-space-check"
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--checker", type=Path, default=default_checker,
                        help=f"installed checker executable (default: {default_checker})")
    args = parser.parse_args()
    executable = args.checker.resolve()
    if not executable.is_file() or not os.access(executable, os.X_OK):
        parser.error(f"installed checker is missing or not executable: {executable}; run installDist first")

    verification_root = app_dir / "build/verification"
    verification_root.mkdir(parents=True, exist_ok=True)
    run_dir = Path(tempfile.mkdtemp(prefix="synthetic-", dir=verification_root))
    reference = run_dir / "synthetic-reference.png"
    matching = run_dir / "synthetic-identical-candidate.png"
    differing = run_dir / "synthetic-different-candidate.png"
    matching_mask = run_dir / "synthetic-identical-mask.png"
    difference_mask = run_dir / "synthetic-difference-mask.png"

    original = []
    for y in range(HEIGHT):
        for x in range(WIDTH):
            pixel = (100, 180, 80, 255) if 40 <= x < 120 and 30 <= y < 100 else (20, 30, 40, 255)
            if (x, y) == (10, 9):
                pixel = (0, 0, 0, 0)
            original.append(pixel)
    candidate = original.copy()
    for y in range(80, 100):
        for x in range(140, 180):
            candidate[y * WIDTH + x] = (20, 30, 41, 255)
    candidate[9 * WIDTH + 8] = (20, 30, 40, 254)
    candidate[9 * WIDTH + 10] = (1, 0, 0, 0)

    write_rgba_png(reference, original)
    write_rgba_png(matching, original, text="same RGBA pixels; distinct PNG metadata")
    write_rgba_png(differing, candidate)

    print("Synthetic fixtures only; these are not Minecraft captures.")
    for fixture in (reference, matching, differing):
        print(f"Fixture: {fixture}")
    run_checker(executable, reference, matching, matching_mask, 0)
    if verify_mask(matching_mask, lambda _x, _y: False) != 0:
        raise AssertionError("Identical-pixel mask contained a difference")
    print(f"Verified every identical-pair mask pixel: {matching_mask}")

    is_different = lambda x, y: (140 <= x < 180 and 80 <= y < 100) or (x, y) in {(8, 9), (10, 9)}
    run_checker(executable, reference, differing, difference_mask, 1)
    changed = verify_mask(difference_mask, is_different)
    if changed != 802:
        raise AssertionError(f"Expected 802 different pixels, received {changed}")
    print(f"Verified every difference-mask pixel: {difference_mask}")
    print(f"SYNTHETIC_CHECK_PASSED: {changed} / {WIDTH * HEIGHT} exact RGBA differences; masks checked pixel-by-pixel.")
    print(f"Generated synthetic evidence directory: {run_dir}")


if __name__ == "__main__":
    main()
