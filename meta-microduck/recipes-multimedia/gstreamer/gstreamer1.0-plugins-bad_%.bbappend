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
PACKAGECONFIG:append = " webrtc srtp"
