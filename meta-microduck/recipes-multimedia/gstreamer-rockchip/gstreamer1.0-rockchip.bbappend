# Build from our fork, pollen-robotics/gstreamer_rockship (a GitHub fork of
# JeffyCN/mirrors), on the branch that carries the two mpph264enc commits we are
# about to send upstream as a PR against `gstreamer-rockchip`. They are a pair.
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
# This also moves the base from meta-rockchip's c37e7cf to a0d45af, the
# `gstreamer-rockchip` tip on 2026-08-26, since that is what the PR is against.
# Between the two, COPYING, meson.build and meson_options.txt are unchanged, so
# the base recipe's LIC_FILES_CHKSUM and PACKAGECONFIG still hold.
#
# Replacing SRC_URI wholesale is safe here -- unlike u-boot-rockchip -- because
# the base recipe (meta-rockchip 54f31b3) has exactly one entry, this git URL.
# Recheck that when bumping meta-rockchip.
#
# Once the PR is merged, drop this whole file and let meta-rockchip's SRCREV
# catch up, or pin JeffyCN/mirrors at the merge commit.
#
# The recipe file is gstreamer1.0-rockchip.bb with no version, so this append is
# named to match it exactly -- a _%.bbappend would not attach to it.

SRC_URI = "git://github.com/pollen-robotics/gstreamer_rockship.git;protocol=https;branch=mpph264enc-webrtc"
SRCREV = "7b6a6914765380da522ddc4c75e2df7c04d5c233"
