SUMMARY = "AICSemi AIC8800 SDIO wifi/BT driver and firmware"
DESCRIPTION = "Out-of-tree AICSemi aic8800_* kernel modules for the SDIO-attached \
AIC8800 wifi/BT combo fitted to some Radxa Zero 3W boards, plus its firmware. \
Not present in radxa's kernel tree -- the device tree wires up the SDIO bus, \
power sequence and BT UART, but nothing drives it."
HOMEPAGE = "https://github.com/radxa-pkg/aic8800"
LICENSE = "GPL-2.0-only"
LIC_FILES_CHKSUM = "file://LICENSE;md5=db95b6e40dc7d26d8308b6b7375637b6"

inherit module

# Tag 5.0+git20260123.5f7be68d-7. radxa-pkg wraps AICSemi's vendor drop in Debian
# packaging; we reuse their patches and build rules but not dpkg/DKMS.
SRCREV = "6e076049b719ac2ff7ce5c92786a680407b11cdb"
SRC_URI = "git://github.com/radxa-pkg/aic8800.git;protocol=https;nobranch=1"

# No `S =` line on purpose. wrynose actively rejects S = "${WORKDIR}/git" and
# S = "${UNPACKDIR}/git" with a do_unpack error: the git fetcher now unpacks to
# ${UNPACKDIR}/${BP}, which is what bitbake.conf's default S already points at.

# The vendor Makefile under driver/aic8800 is a plain kbuild fragment: it has no
# rule of its own that invokes the kernel build, which is why the DKMS config
# supplies `-C $kernel_source_dir M=<driver dir>`. module.bbclass instead assumes
# the module Makefile calls kbuild itself via KERNEL_SRC, so we pass -C and M=
# explicitly through MAKE_TARGETS / MODULES_INSTALL_TARGET.
B = "${S}/src/SDIO/driver_fw/driver/aic8800"
MAKE_TARGETS = "-C ${STAGING_KERNEL_DIR} M=${B} modules"
MODULES_INSTALL_TARGET = "-C ${STAGING_KERNEL_DIR} M=${B} modules_install"
MODULES_MODULE_SYMVERS_LOCATION = "."

# Apply exactly what debian/patches/series lists, in its order. Two patches in
# that directory are deliberately absent from series
# (fix-build-on-low-memory-devices-v2, fix-map-riscv64-subarch), so a `*.patch`
# glob would be wrong.
#
# Two of these are load-bearing for us:
#   fix-linux-6.1-build.patch      -- we are on the 6.1 vendor BSP
#   fix-sdio-firmware-path.patch   -- points the driver at
#                                     /lib/firmware/aic8800_fw/SDIO, which is
#                                     where do_install below puts the blobs
# Attached as a do_patch postfunc rather than `do_patch:append`: do_patch is a
# *Python* task (patch.bbclass), so appending a shell body to it makes bitbake try
# to parse the shell as Python. A postfunc also guarantees this runs as part of
# patching, immediately after a clean unpack, instead of risking double
# application if a later task re-runs.
#
# -l/--ignore-whitespace is needed, not cosmetic: parts of AICSemi's vendor drop
# are CRLF while the patches are LF, and without it
# fix-aic_btusb-use-bluez-by-default.patch dies with
# "Hunk #1 FAILED at 144 (different line endings)" on
# src/USB/driver_fw/drivers/aic8800/aic_load_fw/aicbluetooth.c.
#
# Failures stay fatal deliberately. The whole series is applied, as Debian does,
# even though we only build the SDIO variant -- silently tolerating rejects would
# mean not knowing whether an SDIO-relevant fix landed.
apply_debian_patches() {
	cd ${S}
	while read -r p; do
		case "$p" in ''|\#*) continue ;; esac
		patch -p1 -l --no-backup-if-mismatch -i "debian/patches/$p"
	done < debian/patches/series
}
do_patch[postfuncs] += "apply_debian_patches"

FW_SUBDIR = "${nonarch_base_libdir}/firmware/aic8800_fw"

do_install:append() {
	install -d ${D}${FW_SUBDIR}/SDIO
	# Mirror Debian's aic8800-firmware.install and ship every chip variant
	# (aic8800, aic8800D80, aic8800D80N, aic8800D80X2, aic8800DC), letting the
	# driver select at runtime. The board is an AIC8800DS2 and none of those
	# directory names map to it obviously, so picking one here would be a guess.
	cp -R ${S}/src/SDIO/driver_fw/fw/* ${D}${FW_SUBDIR}/SDIO/
	# A compressed duplicate of the aic8800D80 directory; no use on target.
	rm -f ${D}${FW_SUBDIR}/SDIO/aic8800D80.7z
}

PACKAGES =+ "${PN}-firmware"
FILES:${PN}-firmware = "${FW_SUBDIR}"

# The modules are useless without the blobs, and the SDIO device probes at boot.
RDEPENDS:${PN} += "${PN}-firmware"
KERNEL_MODULE_AUTOLOAD += "aic8800_bsp aic8800_fdrv aic8800_btlpm"

# aic8800_btlpm only handles BT low-power signalling. Bringing the controller up
# still needs an HCI attach on the UART the device tree's wireless-bluetooth node
# describes (btattach/hciattach) -- that is runtime work, not packaging.
