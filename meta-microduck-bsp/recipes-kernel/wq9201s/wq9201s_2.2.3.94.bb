SUMMARY = "WuQi WQ9201S SDIO wifi driver (wq_wlan) and its Bluetooth attach"
DESCRIPTION = "Out-of-tree wq_wlan kernel module for the WuQi WQ9201S wifi/BT \
combo inside the beta board's WL6201S module, plus the unit that attaches its \
Bluetooth controller on UART1. Firmware is wq9201s-firmware."
# Seeed's debian/copyright: src/* is GPL-2 (or later), copyright WuQi Micro. The
# whole file is checksummed, so a change to their terms fails the build here.
LICENSE = "GPL-2.0-or-later"
LIC_FILES_CHKSUM = "file://debian/copyright;md5=3727876b7206ead678f0a134cddb542b"

inherit module systemd

FILESEXTRAPATHS:prepend := "${THISDIR}/files:"

# WuQi's WQ9201S SDK v2.2.3.94 as Seeed packages it, wq9201s-wifi-bt-dkms on the
# deb_source branch of their public seeed_armbian_extension -- the same 183 source
# files their factory image installs in /usr/src (compared blob for blob,
# 2026-10-20). Fetched, not committed. wq9201s-bt-attach and its unit are ours,
# reworked from Seeed's bt/ (GPL-2 there too).
SRC_URI = " \
    git://github.com/Seeed-Studio/seeed_armbian_extension.git;protocol=https;branch=deb_source \
    file://wq9201s-bt-attach \
    file://wq9201s-bt.service \
"
SRCREV = "1f4bd1cbf6e844291a54ca55816c1ac827b6bd02"
S = "${UNPACKDIR}/${BP}/wq9201s-wifi-bt-dkms"

# Same shape as aic8800_5.0.bb: build in the driver's directory through kbuild.
# The driver Makefile's own default target only wraps this with KERNELDIR and O=,
# which a cross build sets differently.
B = "${S}/src/wifi/rwnx_drv"
MAKE_TARGETS = "-C ${STAGING_KERNEL_DIR} M=${B} modules"
MODULES_INSTALL_TARGET = "-C ${STAGING_KERNEL_DIR} M=${B} modules_install"
MODULES_MODULE_SYMVERS_LOCATION = "."

# The Makefile builds with -Werror. WuQi and Seeed built with GCC 12 (Debian
# bookworm); wrynose has GCC 15, whose new warnings would fail the build on code
# that is fine. Dropped, and checked dropped.
drop_werror() {
	sed -i '/^ccflags-y += -Werror$/d' ${S}/src/wifi/rwnx_drv/Makefile
	if grep -q -- '-Werror' ${S}/src/wifi/rwnx_drv/Makefile; then
		bbfatal "-Werror still in the wq_wlan Makefile"
	fi
}
do_patch[postfuncs] += "drop_werror"

do_install:append() {
	install -d ${D}${libexecdir}
	install -m 0755 ${UNPACKDIR}/wq9201s-bt-attach ${D}${libexecdir}/
	install -d ${D}${systemd_system_unitdir}
	install -m 0644 ${UNPACKDIR}/wq9201s-bt.service ${D}${systemd_system_unitdir}/
}

# Loaded at boot rather than left to the SDIO modalias: what matters is that it is
# up before the Bluetooth attach waits for wlan0.
KERNEL_MODULE_AUTOLOAD += "wq_wlan"

PACKAGES =+ "${PN}-bt"
FILES:${PN}-bt = "${libexecdir}/wq9201s-bt-attach ${systemd_system_unitdir}/wq9201s-bt.service"
SYSTEMD_PACKAGES = "${PN}-bt"
SYSTEMD_SERVICE:${PN}-bt = "wq9201s-bt.service"
SYSTEMD_AUTO_ENABLE:${PN}-bt = "enable"
RDEPENDS:${PN}-bt = "bluez5 procps util-linux-rfkill"

RDEPENDS:${PN} += "wq9201s-firmware"
