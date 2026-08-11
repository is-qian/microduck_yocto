# webrtcsink (gst-plugins-rs, M8) wraps webrtcbin, and oe-core does not build it:
# PACKAGECONFIG[webrtc] is absent from the default list, so the image had no
# webrtcbin element and no libnice at all -- only dtls was enabled.
#
# Without this the Rust plugin would build and load happily and then fail at
# pipeline construction with "no element webrtcbin", a long way from the cause.
#
# srtp comes too: media encryption is not optional for WebRTC, and libsrtp is what
# provides it. reachy_mini_os reaches the same place from the other direction, by
# installing gstreamer1.0-nice and libnice10 as distro packages.
# sctp is not optional alongside webrtc -- meson enforces it:
#   ext/sctp/meson.build:23: Feature sctp cannot be disabled: webrtc option is enabled
# WebRTC data channels run over SCTP, and microduck_daemon's architecture.md plans
# `control` and `teleop` datachannels, so this is wanted rather than incidental.
# PACKAGECONFIG[sctp] carries no DEPENDS: GStreamer brings its own implementation.
PACKAGECONFIG:append = " webrtc srtp sctp"
