# webrtcbin needs libnice's GStreamer *elements*, not just the library.
#
# gstwebrtcbin.c's _have_nice_elements() looks up the "nicesrc" and "nicesink"
# element factories before it will preroll, and without them the failure names
# nothing that points here (measured on the board 2026-09-10):
#
#   GstWebRTCBin: Your GStreamer installation is missing a plug-in.
#   gstwebrtcbin.c(154): _have_nice_elements(): libnice elements are not available
#
# The image already had libnice 0.1.23 -- pulled in automatically as a shlib
# dependency of libgstwebrtcnice -- and still had no ICE, because this recipe's
#
#     PACKAGECONFIG ??= "gnutls"
#
# leaves out `gstreamer`, so libnice was configured -Dgstreamer=disabled and
# libgstnice.so was never built at all. Enabling it is the whole fix: the recipe's
# FILES:${PN} already claims ${libdir}/gstreamer-1.0/*.so, so the plugin lands in
# the libnice package rather than a separate one. There is no gstreamer1.0-nice
# package in wrynose meta-oe to depend on -- that split is another distro's.
#
# No dependency cycle: this option DEPENDS on gstreamer1.0, and
# gstreamer1.0-plugins-bad's webrtc option DEPENDS on libnice, so the chain is
# plugins-bad -> libnice -> gstreamer1.0 and core does not depend on plugins-bad.
PACKAGECONFIG:append:microduck = " gstreamer"
