# mpph264enc has to be able to say `constrained-baseline`, or there is no video.
#
# webrtcsink's discovery pass demands that one profile, and mediad restricts
# video-caps to H.264 -- so with the stock plugin the robot advertises a codec it
# then cannot deliver: discovery reports "No caps found for stream video_0" and
# every browser session dies on not-negotiated before a frame leaves the board.
# Confirmed on hardware on 2026-09-15: the image's own plugin lists
# { baseline, main, high } and the session died; the patched build streamed.
#
# JeffyCN's recipe inherits auto-patch with PATCHPATH pointed at its own files/
# directory, which is a *different* mechanism to SRC_URI patching and does not
# look here. SRC_URI is used instead because it is the one both understand, and
# because a patch that silently fails to apply is the failure mode this whole
# area has already produced once.
#
# The recipe file is gstreamer1.0-rockchip.bb with no version, so this append is
# named to match it exactly -- a _%.bbappend would not attach to it.

FILESEXTRAPATHS:prepend := "${THISDIR}/files:"

SRC_URI += "file://0001-mpph264enc-advertise-constrained-baseline.patch"
