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
PACKAGECONFIG:append:microduck = " webrtc srtp sctp"

# PACKAGECONFIG is necessary but not sufficient. It builds libgstwebrtc.so and
# links libnice, and webrtcbin then registers and inspects fine -- but refuses to
# preroll on the board (measured 2026-09-10):
#
#   GstWebRTCBin: Your GStreamer installation is missing a plug-in.
#   gstwebrtcbin.c(154): _have_nice_elements(): libnice elements are not available
#
# _have_nice_elements() looks up the "nicesrc"/"nicesink" element *factories*, and
# libnice in wrynose meta-oe does not build them unless its own PACKAGECONFIG gets
# `gstreamer` -- see recipes-support/libnice/libnice_%.bbappend, which is where
# that half of the fix lives. No PACKAGECONFIG here can supply it.
#
# The RDEPENDS is the other half, and it is about honesty rather than files:
# libnice was in the image all along, but only as an automatic shlib dependency of
# libgstwebrtcnice. Nothing declared that webrtcbin needs it, so nothing noticed
# that what arrived was a libnice built without its plugin. Stating it on
# -webrtc puts the requirement where the element is, so it travels with whoever
# installs webrtcbin instead of being a coincidence of our packagegroup.
RDEPENDS:${PN}-webrtc:append:microduck = " libnice"
