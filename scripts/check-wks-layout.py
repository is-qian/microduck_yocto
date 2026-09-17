#!/usr/bin/env python3
"""Check meta-microduck/files/wic/microduck-ab.wks.in for overlapping partitions.

wic catches an overlap too, but only ~50 minutes into a build. The A/B layout is
the one place where a silently shifted offset is not a cosmetic bug: a bundle
built against one layout would write over the wrong partition of another.

    python3 scripts/check-wks-layout.py

Artifact sizes below are measured, not assumed -- from the deploy directory of
the image running on the board. Re-measure and update when an artifact grows;
that is the whole point of the reservation being explicit.
"""
import re
import sys
from pathlib import Path

WKS = Path(__file__).resolve().parent.parent / "meta-microduck/files/wic/microduck-ab.wks.in"

# name -> measured bytes of the artifact that must fit (2026-09-17).
#
# Keyed by the wks --part-name, so this list moves whenever a partition is
# renamed. `boot` rather than `boot_a` because vendor U-Boot finds the kernel by
# that exact GPT name -- see the comment on the part line in the wks.
ARTIFACTS = {
    "idblock": 313344,     # idblock.img
    "uboot": 4 << 20,      # uboot.img, exactly 4 MiB
    "boot": 17004544,      # boot.img
}

UNITS = {"s": 512, "S": 512, "K": 1024, "k": 1024, "M": 1 << 20, "G": 1 << 30}


def to_bytes(text, default_unit):
    """wic's sizetype(): a bare number takes the argument's default unit."""
    if text[-1] in UNITS:
        return int(text[:-1]) * UNITS[text[-1]]
    return int(text) * UNITS[default_unit]


def main():
    parts, errors = [], []
    for line in WKS.read_text().splitlines():
        line = line.strip()
        if line.endswith("\\"):
            # wic reads the .wks one line at a time and shlex.splits it; there is
            # no continuation handling, so a wrapped `part` line is a syntax error
            # that only shows up at do_image_wic.
            errors.append(f"line continuation, which wic does not support: {line}")
            continue
        if not line.startswith("part"):
            continue
        name = re.search(r"--part-name (\S+)", line)
        if name:
            name = name.group(1)
        elif "--no-table" in line:
            name = "idblock"  # no GPT entry, so no --part-name to read
        else:
            errors.append(f"unnamed partition with a table entry: {line}")
            continue

        size = re.search(r"--fixed-size (\S+)", line)
        if not size:
            errors.append(f"{name}: no --fixed-size; its size would follow its source")
            continue
        size = to_bytes(size.group(1), "M")

        offset = re.search(r"--offset (\S+)", line)
        align = re.search(r"--align (\S+)", line)
        if offset:
            start = to_bytes(offset.group(1), "K")
        elif align:
            start = to_bytes(align.group(1), "K")  # first partition: aligned == placed
        else:
            errors.append(f"{name}: neither --offset nor --align; placement is implicit")
            continue
        parts.append((name, start, size))

    for name, start, size in parts:
        fits = ARTIFACTS.get(name)
        if fits is not None and fits > size:
            errors.append(f"{name}: artifact {fits} B does not fit reserved {size} B")

    for (an, astart, asize), (bn, bstart, _) in zip(parts, parts[1:]):
        if astart + asize > bstart:
            errors.append(
                f"{an} ends at {astart + asize} B, past the start of {bn} at {bstart} B"
            )
        if bstart < astart:
            errors.append(f"{bn} at {bstart} B is before {an} at {astart} B")

    for name, start, size in parts:
        head = ARTIFACTS.get(name)
        used = f"  ({head} B used)" if head else ""
        print(f"{name:10s} {start:>12,} .. {start + size:>12,} B  ({size >> 20} MiB){used}")
    total = parts[-1][1] + parts[-1][2]
    print(f"total {total:,} B = {total / (1 << 30):.2f} GiB")

    if errors:
        print("\n".join("ERROR: " + e for e in errors), file=sys.stderr)
        return 1
    print("layout ok")
    return 0


if __name__ == "__main__":
    sys.exit(main())
