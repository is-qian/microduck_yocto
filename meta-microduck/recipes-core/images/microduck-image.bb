SUMMARY = "microduck robot OS"
LICENSE = "MIT"

require recipes-core/images/core-image-base.bb

# meta-rockchip: produces the .wic plus a Rockchip update.img in deploy/.
inherit rockchip-image

IMAGE_FEATURES += "ssh-server-openssh"

IMAGE_INSTALL:append = " \
    kernel-modules \
    ca-certificates \
    networkmanager \
    bluez5 \
    i2c-tools \
    usbutils \
    htop \
"

# Notes on what is deliberately absent:
#
# - systemd-timesyncd: not a separate package. timesyncd is in oe-core
#   systemd's default PACKAGECONFIG, so it ships with systemd. It still matters
#   a lot -- this board has no battery-backed RTC, and updaterd's preflight
#   check exists because a 1970 clock fails TLS with an opaque error. Verify
#   with `timedatectl`, do not assume.
#
# - no *-dev packages, no build-essential, no gdb. Cross-compile against the
#   SDK (`bitbake microduck-image -c populate_sdk`) instead of shipping headers
#   to the robot.
