# Two patches, and they are a pair.
#
# The first lets mpph264enc say `constrained-baseline`, without which webrtcsink's
# discovery pass finds no codec at all and every session dies on not-negotiated.
#
# The second makes it tell the truth about its level. It advertised a fixed 4.0
# whatever it encoded; 1280x720@30 is exactly level 3.1, and browsers offer
# profile-level-id=42e01f for it. With 42c028 on the wire Firefox and Chrome
# showed nothing, while a GStreamer client on the same stream was fine --
# confirmed on hardware on 2026-09-16, where forcing level=3.1 by hand restored
# the picture immediately.
#
# JeffyCN's recipe inherits auto-patch with PATCHPATH pointed at its own files/
# directory, which is a different mechanism to SRC_URI patching and does not look
# here. SRC_URI is used instead because it is the one both understand, and because
# a patch that silently fails to apply is the failure mode this area has already
# produced once.
#
# The recipe file is gstreamer1.0-rockchip.bb with no version, so this append is
# named to match it exactly -- a _%.bbappend would not attach to it.

FILESEXTRAPATHS:prepend := "${THISDIR}/files:"

SRC_URI += "file://0001-mpph264enc-advertise-constrained-baseline.patch \
            file://0002-mpph264enc-derive-the-H.264-level-from-input-caps.patch \
"
