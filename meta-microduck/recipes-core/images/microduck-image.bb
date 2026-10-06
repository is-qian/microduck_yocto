SUMMARY = "microduck robot OS"
LICENSE = "MIT"

require recipes-core/images/core-image-base.bb

# meta-rockchip: produces the .wic in deploy/.
inherit rockchip-image extrausers

# The operator account, the same as the Radxa images': microduck / microduck,
# sudo with that password. Everything the daemon's tooling assumes defaults to
# it -- duckctl ssh, scripts/dev-push.sh and provision-board.sh all log in as
# `microduck@` and sudo from there. robot lets robotctl read the daemons'
# sockets without root (scripts/install.sh adds its operator the same way);
# audio, video, dialout and i2c are the devices a person debugging a duck pokes
# at by hand. The hash is `openssl passwd -6 -salt microduckbeta microduck`,
# fixed so the image stays reproducible; '$' is escaped for bitbake.
MICRODUCK_OPERATOR_HASH = "\$6\$microduckbeta\$lxVesOQbjM8IXwxq5s/GpwW0KnK1YuspITBn7crCGbeuodZd.PBnJJIS5E32lQK4/mugkqFe36SAr6iyo82Gu0"
EXTRA_USERS_PARAMS = "useradd -m -s /bin/bash -G robot,sudo,audio,video,dialout,i2c,plugdev,input -p '${MICRODUCK_OPERATOR_HASH}' microduck;"

ROOTFS_POSTPROCESS_COMMAND += "microduck_operator_sudo;"
microduck_operator_sudo() {
	install -d -m 0750 ${IMAGE_ROOTFS}${sysconfdir}/sudoers.d
	echo '%sudo ALL=(ALL:ALL) ALL' > ${IMAGE_ROOTFS}${sysconfdir}/sudoers.d/10-sudo-group
	chmod 0440 ${IMAGE_ROOTFS}${sysconfdir}/sudoers.d/10-sudo-group
}

# The card image to download and flash is the compressed .wic.zst plus its .bmap:
# `bmaptool copy` writes only the blocks in use, instead of dd copying all 13 GB.
# Plain `wic` stays in the list. oe-core deletes a type that is not listed as soon
# as it has been converted (image.bbclass, rm_tmp_images), and rockchip's
# gen_rkparameter reads the .wic after that. build-hfjob.sh leaves it out of the
# bucket instead.
#
# wic.zst.sha256sum is oe-core's sha256sum conversion: `sha256sum -c` on it
# checks a download before it goes anywhere near a card. bmaptool checks the
# data again while flashing, against the per-range sums in the .bmap.
IMAGE_FSTYPES += "wic.zst wic.zst.sha256sum wic.bmap"

# Two rockchip outputs nothing here uses, 14.5 GB per build between them:
# - .ufs.wic, a second 13 GB copy with 4K sectors for UFS storage (we boot from SD)
# - update.img, for Rockchip's own flashing tool (we use dd/bmaptool)
# These are no-ops rather than an IMAGE_POSTPROCESS_COMMAND:remove, because
# rockchip-image.bbclass appends them inside ";"-joined words such as
# "do_image_wic_ufs;gen_rkparameter;", which :remove cannot match.
do_image_wic_ufs() {
	:
}
gen_rkupdateimg() {
	:
}

IMAGE_FEATURES += "ssh-server-openssh"

IMAGE_INSTALL:append = " \
    microduck-daemon \
    microduck-board \
    rauc \
    u-boot-rockchip-env \
    microduck-data \
    packagegroup-microduck-media \
    alsa-utils-aplay \
    ldd \
    rustypot-wizard \
    sudo \
    kernel-modules \
    ca-certificates \
    networkmanager \
    bluez5 \
    ${MICRODUCK_RADIO_PACKAGES} \
    wireless-regdb-static \
    i2c-tools \
    usbutils \
    htop \
"

# rauc is listed here rather than arriving on its own: meta-rauc pulls it in by
# bbappending packagegroup-base, and this image is built from core-image-base,
# which does not use that packagegroup.

# u-boot-rockchip-env is /etc/fw_env.config and /etc/u-boot-initial-env, which
# RAUC's uboot backend needs; the fw_printenv/fw_setenv it runs arrive through
# meta-rauc's own RDEPENDS on u-boot-fw-utils. See recipes-bsp/u-boot/.

# MICRODUCK_RADIO_PACKAGES is per machine (microduck-common.inc has the
# default): the Zero 3W's is aic8800-bt, the beta board's wq9201s-bt.
#
# aic8800-bt is the HCI attach unit, in the shipping image rather than the dev
# one: BLE is how a robot with no network gets provisioned (configd over btd), so
# a controller that never attaches is a product fault, not a debug inconvenience.
# The chip answers only through hciattach with the port speed set before the line
# discipline attaches -- see the unit for the measurements that established that.

# rustypot-wizard is the servo setup tool people reach for on a robot, and ldd
# is what its cargo-dist installer (and every other cargo-dist installer) runs
# to read the glibc version: without it the installer reports glibc "too old"
# and finds no download for aarch64-unknown-linux-gnu. Seen on the beta board,
# 2026-10-06.

# alsa-utils-aplay is aplay and arecord, and the robot's voice depends on it:
# robotd and sounds play every wav by running `aplay -D <device>` (robotd's
# sound.rs), so without it a robot accepts `robotctl quack` and stays silent.
# Found missing on the first beta board, 2026-10-05.

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
