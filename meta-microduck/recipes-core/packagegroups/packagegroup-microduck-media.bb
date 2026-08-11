SUMMARY = "GStreamer with Rockchip hardware video codecs"
DESCRIPTION = "Provisioned ahead of its consumer on purpose. mediad does not exist \
yet -- microduck_daemon's README lists it as 'Not built yet' and \
docs/architecture.md:306 still has GStreamer webrtcbin vs webrtc-rs as an open \
decision. The point of building it now is to find out whether RK3566 hardware H.264 \
encode works before mediad is written around the assumption that it does."

LICENSE = "MIT"

inherit packagegroup

# packagegroup.bbclass sets allarch, which cannot depend on packages whose names
# vary by target arch. rockchip-mpp and rockchip-librga both rename Debian-style
# (librockchip-mpp1, librga2), so an allarch group fails do_package_write_rpm with
# "shouldn't depend on packages which are dynamically renamed".
PACKAGE_ARCH = "${MACHINE_ARCH}"

RDEPENDS:${PN} = " \
    gstreamer1.0 \
    gstreamer1.0-plugins-base \
    gstreamer1.0-plugins-good \
    gstreamer1.0-plugins-bad \
    gstreamer1.0-rockchip \
    rockchip-mpp \
    rockchip-librga \
    v4l-utils \
"

# gstreamer1.0-rockchip's default PACKAGECONFIG is "mpp rga kmssrc" plus x11 when
# that DISTRO_FEATURE is set. We removed x11, so rkximagesink is not built -- which
# is correct for a headless robot. kmssrc pulls in libdrm, and until now the image
# had no DRM userspace at all.
#
# Expected elements: mppvideodec, mppjpegdec, mppvideoenc/mpph264enc, rgaconvert,
# kmssrc.
#
# Deliberately absent:
#
#   rockchip-rkaiq / rockchip-rkisp -- the camera ISP stack, M6. Needs the GC2093
#     device-tree node and its tuning file, and rkaiq_3A_server ships a sysvinit
#     script that needs a systemd unit written.
#
#   gstreamer1.0-plugins-rs (webrtcsink) -- M8, and no upstream recipe exists.
#
# rockchip-libmali arrives anyway, and not by choice: mali.inc sets
# PREFERRED_PROVIDER_virtual/egl and virtual/libgles* to it, and
# gstreamer1.0-plugins-base builds an `opengl` subpackage needing EGL/GLES. So the
# licence-flagged GPU blob is in the image via GStreamer. Dropping `opengl` from
# DISTRO_FEATURES would remove both, but rk356x.inc assumes a GPU is present, so
# that is a change to make deliberately rather than in passing.
#
# TODO trim: this packagegroup grew the image 549 -> 745 MiB (+196 MiB), mostly
# because the plugins-bad meta package pulls ~100 subpackages -- dvb, decklink,
# vulkan, waylandsink, rfbsrc, festival and so on, none of which a robot wants.
# The fix is to depend on specific plugin subpackages instead of the metas, but the
# right list is not knowable until mediad exists and its pipeline is written. Doing
# it now would be guessing; the metas at least prove the whole stack builds.
