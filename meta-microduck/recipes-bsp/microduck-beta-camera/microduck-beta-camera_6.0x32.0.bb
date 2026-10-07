SUMMARY = "Camera tuning for the beta board: rkaiq 3A server and the GC2093 IQ file"
DESCRIPTION = "Rockchip's ISP 3A engine (rkaiq) and Seeed's tuning file for the \
beta board's GC2093 module (SIDB205300385-VA). Without them the ISP streams \
untuned: no white balance or colour correction, a green picture. With them it \
matches Seeed's factory image."
HOMEPAGE = "https://github.com/Seeed-Studio/seeed_armbian_extension"
# rkaiq is Apache-2.0 (meta-rockchip's rockchip-rkaiq recipe, from its NOTICE);
# Seeed's package carries no licence file, so the checksum is oe-core's copy.
LICENSE = "Apache-2.0"
LIC_FILES_CHKSUM = "file://${COMMON_LICENSE_DIR}/Apache-2.0;md5=89aea4e17d99a7cacdbeed46a0096b10"

# Seeed's own build, not meta-rockchip's rockchip-rkaiq: the IQ file's format
# follows rkaiq's version, Seeed tuned this module against v6.0x32, and
# meta-rockchip builds a 2024-04 snapshot. This is the package Seeed's factory
# image installs, byte for byte the one validated on a beta board (2026-10-07).
SRC_URI = " \
    https://seeed-studio.github.io/seeed_armbian_extension/pool/main/c/camera-engine-rkaiq-rk356x/camera-engine-rkaiq-rk356x_${PV}-1_arm64.deb;subdir=deb \
    file://rkaiq_3A.service \
    file://mediad-rkaiq.conf \
"
SRC_URI[sha256sum] = "25fe38c49143d358dc30134963e8e6093117be25e5a2bd2114a55236d12c9463"

S = "${UNPACKDIR}"

COMPATIBLE_MACHINE = "microduck-beta"

inherit systemd

do_configure[noexec] = "1"
do_compile[noexec] = "1"

# Only what the server loads: it maps librkaiq.so and nothing else of the
# package's libraries (read from /proc/<pid>/maps while streaming). Only the
# GC2093's IQ file: the package also carries a GC2613 one, a module these boards
# do not have.
do_install() {
	install -d ${D}${libdir} ${D}${bindir} ${D}${sysconfdir}/iqfiles
	install -m 0755 ${S}/deb/usr/lib/librkaiq.so ${D}${libdir}/
	install -m 0755 ${S}/deb/usr/bin/rkaiq_3A_server ${D}${bindir}/
	install -m 0644 ${S}/deb/etc/iqfiles/gc2093_SIDB205300385-VA_default.json ${D}${sysconfdir}/iqfiles/

	install -d ${D}${systemd_system_unitdir}/mediad.service.d
	install -m 0644 ${UNPACKDIR}/rkaiq_3A.service ${D}${systemd_system_unitdir}/
	install -m 0644 ${UNPACKDIR}/mediad-rkaiq.conf ${D}${systemd_system_unitdir}/mediad.service.d/rkaiq.conf
}

SYSTEMD_SERVICE:${PN} = "rkaiq_3A.service"

# librkaiq.so is unversioned (its SONAME is librkaiq.so), so it ships in the main
# package, not -dev.
FILES:${PN} += "${libdir}/librkaiq.so ${systemd_system_unitdir}/mediad.service.d"
FILES_SOLIBSDEV = ""

# Prebuilt by Seeed: linked and stripped by their toolchain.
INSANE_SKIP:${PN} += "ldflags already-stripped dev-so"
