# MPP to match the gstreamer-rockchip base our fork builds on (a0d45af,
# 2026-08-26), whose mppjpegdec needs MPP's MppSysCfg API. meta-rockchip still pins
# b29e4b7 (2024-06-27), which lacks it, so that GStreamer does not compile.
#
# c5b8620 is mpp-dev as of the same day as a0d45af. Checked against it: every MPP
# function the GStreamer tip calls is declared, the license checksums and install
# paths are unchanged. SRCREV alone is enough, since the base SRC_URI is nobranch=1.
# Verified on the board 2026-09-29: WebRTC at 30 fps in a browser, and mpph264enc
# holds its bitrate. Drop this when meta-rockchip moves both pins itself.

SRCREV = "c5b8620973c7e22224fcc78ad5016740979c2e0a"
