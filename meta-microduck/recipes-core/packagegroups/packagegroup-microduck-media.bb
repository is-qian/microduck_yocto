SUMMARY = "GStreamer with Rockchip hardware video codecs"
DESCRIPTION = "Provisioned ahead of its consumer on purpose. mediad does not exist \
yet -- microduck_daemon's README lists it as 'Not built yet' and \
docs/architecture.md:306 still has GStreamer webrtcbin vs webrtc-rs as an open \
decision. The point of building it now is to find out whether RK3566 hardware H.264 \
encode works before mediad is written around the assumption that it does."

LICENSE = "MIT"

inherit packagegroup

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
#   rockchip-libmali -- GPU userspace GLES. Nothing in the pipeline needs it: MPP
#     does the codec work and kmssrc uses libdrm. It is a licence-flagged binary
#     blob, so leaving it out keeps the image smaller and simpler.
#
#   gstreamer1.0-plugins-rs (webrtcsink) -- M8, and no upstream recipe exists.
