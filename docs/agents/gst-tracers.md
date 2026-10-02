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
**A released `mediad` never does**: it has no SIGTERM handler, so `systemctl
stop` kills it outright and these tracers collect and never write.

microduck_daemon's `mediad-gst-deinit` branch fixes that (not merged as of
2026-10-02): on SIGTERM it takes the pipeline to NULL, shuts its runtime down,
and calls `gst::deinit()` when `GST_TRACERS` is set, so `systemctl stop mediad`
writes the CSVs. Two things the drop-in below then needs:
`RuntimeDirectoryPreserve=yes`, or systemd deletes `/run/mediad` (and the CSVs)
on that very stop; and patience, since writing an 18 MB CSV makes the stop take
~9 s instead of ~100 ms.

Without that branch, use a `gst-launch-1.0` replica of the part you are
measuring, with `mediad` stopped if it holds the device. (The tracers' `write-log`
action signal exists, but only the application can emit it.)

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

   Start it only once the journal says `registered as [Producer]`. "signalling
   server listening" comes first, and a consumer that lists producers in that
   gap finds none and then idles for good: `connect-to-first-producer` does not
   wait for one to appear.

A drop-in under `/run` keeps the change off the rootfs and gone at reboot:

```sh
mkdir -p /run/systemd/system/mediad.service.d
cat > /run/systemd/system/mediad.service.d/tracers.conf <<'EOF'
[Service]
Environment="GST_TRACERS=latency(flags=pipeline+element+reported);pipeline-snapshot(dot-dir=/run/mediad,dot-ts=false)"
Environment="GST_DEBUG=*:WARNING,GST_TRACER:7"
Environment="RUST_LOG=info,gst=debug"
LogRateLimitIntervalSec=0
EOF
systemctl daemon-reload && systemctl restart mediad
# connect the consumer above, let it run ~20 s, then:
kill -USR1 $(pidof mediad); cp /run/mediad/*.dot /tmp/
journalctl -u mediad -b -o cat > /tmp/mediad-journal.log
# and undo it:
rm -r /run/systemd/system/mediad.service.d && systemctl daemon-reload && systemctl restart mediad
```

For the CSV tracers (with the `mediad-gst-deinit` branch) the variant is
`GST_TRACERS=buffer-lateness(file=/run/mediad/lateness.csv);queue-levels(file=/run/mediad/queues.csv)`,
no `GST_DEBUG` or `RUST_LOG` lines, `RuntimeDirectoryPreserve=yes`, and
`systemctl stop mediad` to collect.

`GST_DEBUG` keeps every other category at WARNING; at `*:7` the journal drowns.
Even so, `latency` is expensive here. With one consumer it logs every element of
the session pipeline:
- journald suppressed 63 000 lines in one run (hence `LogRateLimitIntervalSec=0`);
- `mediad` used 43 s of CPU in 32 s;
- capture dropped to 26 fps;
- every downstream number grew a 150–330 ms tail that vanished without it.

**Do not run `latency` alongside the tracer you are measuring with.**
`buffer-lateness` on its own costs nothing visible.

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

Render the snapshots with Graphviz (installed on `lemur`, and `dot` is on the
lean-ctx allowlist):

```sh
dot -Tsvg pipeline.dot -o pipeline.svg
dot -Tpng -Gdpi=110 pipeline.dot -o pipeline.png
```

Without Graphviz, a WebAssembly build through `npx` does SVG:
`npx -y @hpcc-js/wasm-graphviz-cli -T svg pipeline.dot > pipeline.svg`.

Keep the SVG: the session pipeline is 43 elements, nested bins included, and
unreadable as a PNG, while a browser zooms an SVG.

## Measured — 2026-10-02, Zero 3W, IMX219, slot A of the gst-tracers bundle

UYVY 1280x720@30, one consumer on the board. Graphs and `latency` runs on
`mediad` 0.13.0. The `buffer-lateness` run below is on the `mediad-gst-deinit`
dev build. Raw data and graphs: `microduck_os/gst-traces/2026-10-02/` (beside
the bare repo).

**The shape** (from the snapshots):

```
main:    v4l2src ─ capsfilter ─ tee ┬ queue0 ─ webrtcsink0 [ clocksync0 ─ appsink2 ] ──▶ session
                                    ├ queue1 (leaky) ─ appsink0                        raw frames
                                    └ queue2 ─ valve ─ videorate ─ videoscale ─ … ─ mpph264enc ─ appsink1
session: appsrc (video_0) ─ … ─ mpph264enc2 ─ h264parse2 ─ rtph264pay ─ … ─ webrtcbin ─ nicesink
```

**A frame's age as it leaves each stage**, from `buffer-lateness` inside `mediad`
(the `mediad-gst-deinit` build, 0.14.1-dev.1187), 690 frames at 30.0 fps with
no other tracer loaded:

| Leaves | p50 | p95 | Step |
|---|---|---|---|
| `v4l2src0` | **20.8 ms** | 21.5 | the camera's own delay after its capture timestamp |
| `clocksync0` (main, in `webrtcsink`) | 33.5 | 33.7 | +12.7: tee, queue0, and webrtcsink holding the frame against the clock |
| `video_0` (session `appsrc`) | 53.9 | 54.7 | **+20.4**: the hand-off from the main pipeline into the consumer's |
| `mpph264enc2` | 61.2 | 63.4 | +7.3: hardware encode |
| `h264parse2` → `rtph264pay1` | 62.8 | 65.5 | +1.6 |
| `rtpsession0` | 74.0 | 82.2 | +11.2: rtpfunnel, then the RTP session (pacing) |
| `dtlssrtpenc0` | **74.2** | 82.3 | encrypted and on its way to the network |

So **a frame leaves the robot ~74 ms after its capture timestamp**, before any
network or receiver time.

Ages are comparable across the two pipelines because `webrtcsink` gives each
session pipeline its own clock and base time (`pipeline.set_base_time(element.base_time())`,
`net/webrtc/src/webrtcsink/imp.rs` in gst-plugins-rs).

An earlier estimate here, "~46 ms capture → webrtcbin", added up `latency`
tracer stages. It was low: the `latency` tracer measures within one pipeline, so
it never saw the 20.4 ms hand-off between them. Its per-element numbers still
hold, e.g. `mpph264enc` ~9.4 ms standalone. The 20 fps `gst-launch` replica gave
the same camera figure (21.8 ms), so the replica is a fair way to measure capture
alone.

Reported latency: `v4l2src` answers min 33.3 ms (one frame); the pipeline as a
whole reports 53.3 ms.

Open, not chased:

- **The +20.4 ms hand-off.** That is `appsink2` in the main pipeline to the
  session's `appsrc` pushing, via gst-plugins-rs `StreamProducer`. It is nearly
  constant (p95 only 0.8 ms above p50), which looks like a fixed wait rather
  than queueing. The pipeline's reported latency (53.3) is exactly the source's
  33.3 plus 20, which fits that. The cause was not found.
- **What the 20.8 ms is from.** `v4l2src` stamps buffers from the driver's
  V4L2 timestamp. Whether rkisp takes that at start or end of frame was not
  checked, and the two differ by most of a frame's readout.
- **`clocksync0`'s ~12 ms** is `webrtcsink` behaviour, not `mediad`'s. Whether
  it, or the hand-off, can be cut is a `webrtcsink` question.
