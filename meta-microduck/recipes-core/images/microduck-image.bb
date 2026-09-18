SUMMARY = "microduck robot OS"
LICENSE = "MIT"

require recipes-core/images/core-image-base.bb

# meta-rockchip: produces the .wic plus a Rockchip update.img in deploy/.
inherit rockchip-image

IMAGE_FEATURES += "ssh-server-openssh"

IMAGE_INSTALL:append = " \
    microduck-daemon \
    rauc \
    microduck-data \
    packagegroup-microduck-media \
    kernel-modules \
    ca-certificates \
    networkmanager \
    bluez5 \
    aic8800-bt \
    wireless-regdb-static \
    i2c-tools \
    usbutils \
    htop \
"

# rauc is listed here rather than arriving on its own: meta-rauc pulls it in by
# bbappending packagegroup-base, and this image is built from core-image-base,
# which does not use that packagegroup.

# aic8800-bt is the HCI attach unit, in the shipping image rather than the dev
# one: BLE is how a robot with no network gets provisioned (configd over btd), so
# a controller that never attaches is a product fault, not a debug inconvenience.
# The chip answers only through hciattach with the port speed set before the line
# discipline attaches -- see the unit for the measurements that established that.

# microduck-daemon-padd is built but deliberately not installed: it is a gamepad
# client, and its gilrs/libudev-sys chain is only wanted when someone is
# teleoperating. microduck-image-dev.bb installs it.
#
# This recipe is the one that ships. Everything a robot must not carry -- a
# password-less root login above all -- belongs in microduck-image-dev.bb, which
# requires this file and only ever adds to it.
#
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
