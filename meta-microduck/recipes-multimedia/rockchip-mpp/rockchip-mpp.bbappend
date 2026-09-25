# Move MPP forward to match the gstreamer-rockchip base our fork now uses.
#
# gstreamer1.0-rockchip.bbappend builds pollen-robotics/gstreamer_rockship on top
# of a0d45af (gstreamer-rockchip tip, 2026-08-26). Since 666203a (2026-03-06),
# mppjpegdec calls MPP's system-config API -- MppSysCfg, mpp_sys_cfg_get/ioctl/
# set_u32/get_u32/put -- which MPP only gained in 78ad332 (2024-12-18). meta-rockchip
# still pins b29e4b7 (2024-06-27), so that base never compiled:
#
#   gstmppjpegdec.c:219: error: unknown type name 'MppSysCfg'
#
# c5b8620 is mpp-dev as it stood on 2026-08-26, the same day as a0d45af -- the
# closest pairing to what upstream built that GStreamer against, rather than
# whatever mpp-dev's tip is today. Checked before building, against that exact
# commit:
#
#   - all 63 mpp_* functions gstreamer-rockchip a0d45af calls are declared in its
#     inc/, reached from <rockchip/rk_mpi.h> via rk_mpi_cmd.h -> rk_venc_cfg.h ->
#     rk_mpp_cfg.h, and the whole of inc/ is installed
#   - LICENSES/Apache-2.0 and LICENSES/MIT hash to the base recipe's
#     LIC_FILES_CHKSUM unchanged
#   - install() destinations are the same set (bin, include/rockchip, lib,
#     lib/pkgconfig), so the base recipe's FILES still cover everything
#
# SRCREV alone is enough: the base SRC_URI carries nobranch=1, so the branch name
# in it (mpp-dev-2024_06_27, which no longer exists upstream) is never checked.
#
# This changes the hardware codec library under the camera and WebRTC path, which
# was verified on b29e4b7. Re-verify the WebRTC stream on the board after the
# first image with it. Drop this file when meta-rockchip moves both pins itself.
#
# Named rockchip-mpp.bbappend, no _%: the recipe file is rockchip-mpp.bb.

SRCREV = "c5b8620973c7e22224fcc78ad5016740979c2e0a"
