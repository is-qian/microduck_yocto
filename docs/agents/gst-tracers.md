# GStreamer tracers — measuring the media pipeline on the board

The dev image ships both tracer plugins; the shipping image has only the hooks.
What they are, how to point them at `mediad` (which takes more than setting
`GST_TRACERS`), and what they measured on the Zero 3W.

## What is on the board

| Package | Plugin | Tracers |
|---|---|---|
| `gstreamer1.0-tracers` | `coretracers` | `latency`, `stats`, `rusage`, `leaks`, `log`, `factories`, `dots` |
| `gstreamer1.0-plugins-rs-tracers` | `rstracers` | `buffer-lateness`, `queue-levels`, `pipeline-snapshot`, `pad-push-timings`, `pcap-writer` |

Both are dev-image only. The tracer *hooks* are compiled into libgstreamer for
both images (`gstreamer1.0_%.bbappend`): oe-core builds it with
`-Dtracer_hooks=false`, under which `GST_TRACERS` is accepted and silently does
nothing. If a tracer prints nothing, check `gst-inspect-1.0 coretracers` before
anything else.

Which one answers which question:

- **`latency`** — how long a buffer takes source → sink (`flags=pipeline`),
  through each element (`flags=element`), and what each element *claims* in a
  LATENCY query (`flags=reported`). Logs at TRACE on category `GST_TRACER`, so
  it needs `GST_DEBUG=GST_TRACER:7`. Works in any process, including `mediad`.
- **`buffer-lateness`** — how *old* a buffer is when it leaves each pad
  (pipeline clock minus buffer clock time). At a live source's `src` pad this is
  the time from capture timestamp to push: the camera's own delay, which
  `latency` cannot see because it starts counting at that push.
- **`pipeline-snapshot`** — a `.dot` of every pipeline in the process on
  `SIGUSR1`. Includes `webrtcsink`'s per-consumer session pipelines.
- **`queue-levels`** — fill level of every queue over time; for a stall.

## The catch with the CSV tracers

`buffer-lateness`, `queue-levels` and `pad-push-timings` write their CSV in
`dispose()`, which runs at `gst_deinit()`. `gst-launch-1.0` calls it on exit.
**`mediad` never does** (no `gst::deinit` anywhere in it), so inside `mediad`
these tracers collect and never write. Use them on a `gst-launch-1.0` replica of
the part you are measuring, with `mediad` stopped if it holds the device. The
tracers also have a `write-log` action signal, but only the application can emit
it.

`latency` and `pipeline-snapshot` have no such problem: one logs as it goes, the
other writes on the signal.

## Quick checks with gst-launch

```sh
# per-element latency, hardware encoder
GST_TRACERS='latency(flags=element)' GST_DEBUG=GST_TRACER:7 GST_DEBUG_FILE=/tmp/lat.log \
  gst-launch-1.0 videotestsrc num-buffers=90 \
    ! video/x-raw,format=NV12,width=1280,height=720,framerate=30/1 \
    ! mpph264enc ! h264parse ! fakesink sync=true

# camera delay: buffer age at each pad (stop mediad first, it holds /dev/video0)
systemctl stop mediad
GST_TRACERS='buffer-lateness(file=/tmp/lateness.csv)' \
  gst-launch-1.0 -e v4l2src device=/dev/video0 num-buffers=300 \
      extra-controls='c,exposure=1200,analogue_gain=2816' \
    ! video/x-raw,format=UYVY,width=1280,height=720,framerate=30/1 \
    ! queue ! mpph264enc ! h264parse ! fakesink sync=true
systemctl start mediad

# graph on demand
GST_TRACERS=pipeline-snapshot GST_DEBUG_DUMP_DOT_DIR=/tmp/dots gst-launch-1.0 ... &
kill -USR1 $!
```

`-e` and `num-buffers` matter for `buffer-lateness`: the file is written on a
clean exit, not when the process is killed.

The caps above are `mediad`'s capture caps (`CAPTURE_FORMAT`, 1280x720@30), and
the exposure is what its AE settles on indoors. The replica runs at **20 fps,
not 30**: `mediad` raises the capture pool to 4 buffers with an allocation probe
(`raise_capture_buffers`), which `gst-launch` cannot do, and with fewer buffers
`v4l2src` drops to 20 fps — the case `mediad/src/pipeline.rs` documents. That
costs frames, not per-frame age, but it is not identical to `mediad`.

## Tracing mediad itself

Three things stand between `GST_TRACERS` and output:

1. **The log bridge.** `mediad` replaces GStreamer's log function and forwards
   everything below INFO as `tracing::debug!` on target `gst`
   (`bridge_gstreamer_log`). With the unit's `RUST_LOG=info` the tracer's TRACE
   records are dropped. Add `gst=debug`.
2. **The sandbox.** `ProtectSystem=strict` and `PrivateTmp=yes`, so a dot
   directory must be the unit's `RuntimeDirectory`, `/run/mediad`. It is removed
   when `mediad` stops: copy the dots out first.
3. **No consumer, no encoder.** `webrtcsink` builds the encoder in a
   per-consumer *session pipeline*. With nobody connected, half the media path
   does not exist. A consumer on the board itself is enough; H.264 caps
   downstream stop `webrtcsrc` from decoding, so it costs no CPU:

   ```sh
   gst-launch-1.0 -e webrtcsrc connect-to-first-producer=true ! video/x-h264 ! fakesink sync=false
   ```

A drop-in under `/run` keeps the change off the rootfs and gone at reboot:

```sh
mkdir -p /run/systemd/system/mediad.service.d
cat > /run/systemd/system/mediad.service.d/tracers.conf <<'EOF'
[Service]
Environment="GST_TRACERS=latency(flags=pipeline+element+reported);pipeline-snapshot(dot-dir=/run/mediad,dot-ts=false)"
Environment="GST_DEBUG=*:WARNING,GST_TRACER:7"
Environment="RUST_LOG=info,gst=debug"
EOF
systemctl daemon-reload && systemctl restart mediad
# connect the consumer above, let it run ~20 s, then:
kill -USR1 $(pidof mediad); cp /run/mediad/*.dot /tmp/
journalctl -u mediad -b -o cat > /tmp/mediad-journal.log
# and undo it:
rm -r /run/systemd/system/mediad.service.d && systemctl daemon-reload && systemctl restart mediad
```

`GST_DEBUG` keeps every other category at WARNING; at `*:7` the journal drowns.
Twenty-five seconds with one consumer is ~29 000 tracer lines.

## Reading the output

Off the board, on the workstation:

```sh
scripts/gst-tracer-summary.py mediad-journal.log   # latency records
scripts/gst-tracer-summary.py lateness.csv         # buffer-lateness CSV
```

It prints mean / p50 / p95 / max per element or pad, and drops samples near
2^64 ns: elements that hold or resend buffers (`rtprtxsend`, `rtpgccbwe`) make
the tracer's unsigned subtraction wrap, and one such sample turns a mean into
nonsense. Read p50 for those elements.

Upstream's plotting scripts are in `gst-plugins-rs/utils/tracers/scripts/`
(`buffer_lateness.py`, `queue_levels.py`, `pad_push_timings.py`; matplotlib, so
they need a display).

Graphviz is not on `lemur`, and `docker` is not on its allowlist. A WebAssembly
build through `npx` works, SVG only:

```sh
npx -y @hpcc-js/wasm-graphviz-cli -T svg pipeline.dot > pipeline.svg
npx -y @resvg/resvg-js-cli --fit-width 2400 pipeline.svg pipeline.png   # if a PNG is needed
```

Keep the SVG: the session pipeline is 43 elements, nested bins included, and
unreadable as a PNG, while a browser zooms an SVG.

## Measured — 2026-10-02, Zero 3W, IMX219, slot A of the gst-tracers bundle

`mediad` 0.13.0, UYVY 1280x720@30, one consumer on the board. Raw data and
graphs: `microduck_os/gst-traces/2026-10-02/` (beside the bare repo).

**The shape** (from the snapshots):

```
main:    v4l2src ─ capsfilter ─ tee ┬ queue0 ─ webrtcsink0 [ clocksync0 ─ appsink2 ] ──▶ session
                                    ├ queue1 (leaky) ─ appsink0                        raw frames
                                    └ queue2 ─ valve ─ videorate ─ videoscale ─ … ─ mpph264enc ─ appsink1
session: appsrc (video_0) ─ … ─ mpph264enc2 ─ h264parse2 ─ rtph264pay ─ … ─ webrtcbin ─ nicesink
```

**Where a frame's time goes**, p50:

| Stage | p50 | Notes |
|---|---|---|
| capture timestamp → leaves `v4l2src` | **21.8 ms** | `buffer-lateness`, replica; 20.9–22.2 over 300 frames |
| `v4l2src` → `webrtcsink` input | 0.9 ms | capsfilter, tee, queue0 |
| `clocksync0` inside `webrtcsink` | **11.8 ms** | holds each frame against the clock before any consumer sees it |
| session: `appsrc` → `webrtcbin` | 11.4 ms | mean 20.5, **p95 73 ms** |
| of which `mpph264enc2` | 6.8 ms | mean 12.1, **p95 59 ms**; standalone it is 9.4 ms with p95 9.8 |
| **capture → `webrtcbin`** | **~46 ms** | before RTP/SRTP/ICE (~1–2 ms) and the network |

Reported latency: `v4l2src` answers min 33.3 ms (one frame); the pipeline as a
whole reports 53.3 ms.

Open, not chased:

- **What the 21.8 ms is from.** `v4l2src` stamps buffers from the driver's
  V4L2 timestamp. Whether rkisp takes that at start or end of frame was not
  checked, and the two differ by most of a frame's readout.
- **The encoder's tail inside `webrtcsink`.** p95 59 ms there against 9.8 ms
  standalone, same element, same resolution. Bitrate changes from congestion
  control or contention for the RGA are candidates; neither was tested.
- **`clocksync0`'s 11.8 ms** is `webrtcsink` behaviour, not `mediad`'s. Whether
  it can be cut is a `webrtcsink` question.
