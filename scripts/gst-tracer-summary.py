#!/usr/bin/env python3
"""Text summary of GStreamer tracer output, for a terminal with no display.

    scripts/gst-tracer-summary.py latency.log      # core `latency` tracer, from a GST_DEBUG log or a journal
    scripts/gst-tracer-summary.py lateness.csv     # gst-plugins-rs `buffer-lateness` CSV

Upstream's plotting scripts (gst-plugins-rs utils/tracers/scripts/) draw the same data with
matplotlib; this prints mean / p50 / p95 / max per element or pad. See docs/agents/gst-tracers.md.
"""
import csv
import re
import statistics
import sys
from collections import defaultdict

ANSI = re.compile(r"\x1b\[[0-9;]*m")
FIELD = re.compile(r"([a-z-]+)=\((?:string|guint64|boolean)\)([^,;]*)")
# An element that holds a buffer back or sends it twice (rtprtxsend, rtpgccbwe) makes the tracer's
# unsigned subtraction wrap: those samples read ~2^64 ns. They are not measurements.
WRAPPED = 1 << 62


def pct(xs, p):
    xs = sorted(xs)
    return xs[min(len(xs) - 1, int(round(p / 100 * (len(xs) - 1))))]


def stats(name, ms, extra=""):
    print("  {:<44} n={:<5}{} mean={:7.2f}  p50={:7.2f}  p95={:7.2f}  max={:7.2f} ms".format(
        name, len(ms), extra, statistics.mean(ms), pct(ms, 50), pct(ms, 95), max(ms)))


def lateness(path):
    """buffer-lateness CSV: timestamp, element:pad, pointer, buffer clock, pipeline clock, lateness, min latency."""
    pads = defaultdict(list)
    with open(path, newline="") as f:
        for row in csv.reader(f):
            if len(row) == 7:
                pads[row[1]].append((int(row[0]), int(row[5]) / 1e6))
    print("age of each buffer as it leaves the pad (pipeline clock - buffer clock time)")
    for pad, rows in pads.items():
        span = (rows[-1][0] - rows[0][0]) / 1e9
        fps = " {:5.1f} fps ".format((len(rows) - 1) / span) if span > 0 else " "
        stats(pad, [r[1] for r in rows], fps)


def latency(path):
    pipeline, element, reported = defaultdict(list), defaultdict(list), {}
    with open(path, encoding="utf-8", errors="replace") as f:
        for line in f:
            line = ANSI.sub("", line)
            for kind in ("element-reported-latency, ", "element-latency, ", "latency, "):
                i = line.find(kind)
                if i < 0 or (kind == "latency, " and i > 0 and line[i - 1] == "-"):
                    continue
                if "%s" in line[i:]:
                    break  # the record's format string, announced once at startup
                v = dict(FIELD.findall(line[i:]))
                if kind == "element-reported-latency, ":
                    reported[v["element"]] = (int(v["min"]), int(v["max"]))
                elif int(v["time"]) < WRAPPED:
                    if kind == "element-latency, ":
                        element[v["element"]].append(int(v["time"]) / 1e6)
                    else:
                        key = "{} -> {}:{}".format(v["src-element"], v["sink-element"], v["sink"])
                        pipeline[key].append(int(v["time"]) / 1e6)
                break

    print("pipeline latency (source pad push -> sink pad)")
    for k, ms in sorted(pipeline.items()):
        stats(k, ms)
    print("\nper-element latency (sink pad -> src pad), slowest p50 first")
    for k, ms in sorted(element.items(), key=lambda kv: -pct(kv[1], 50)):
        stats(k, ms)
    print("\nreported latency (non-zero answers to the LATENCY query)")
    for k, (mn, mx) in sorted(reported.items(), key=lambda kv: -kv[1][0]):
        if mn:
            print("  {:<44} min={:7.2f} ms  max={}".format(
                k, mn / 1e6, "none" if mx >= WRAPPED else "{:.2f} ms".format(mx / 1e6)))


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    (lateness if sys.argv[1].endswith(".csv") else latency)(sys.argv[1])
