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
    wireless-regdb-static \
    i2c-tools \
    usbutils \
    htop \
"

# wireless-regdb-static, not wireless-regdb: the -static package ships
# /lib/firmware/regulatory.db, which kernels >= 4.15 load themselves. The plain
# package installs the CRDA files instead, which need the obsolete crda userspace.
#
# Without it the QEMU boot logs:
#   platform regulatory.0: Direct firmware load for regulatory.db failed, error -2
# and cfg80211 falls back to restrictive built-in rules -- which on a wifi product
# shows up much later as missing channels or reduced TX power, a long way from its
# cause.

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
