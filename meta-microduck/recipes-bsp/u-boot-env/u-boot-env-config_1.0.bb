SUMMARY = "fw_env.config for the microduck U-Boot environment"
DESCRIPTION = "Tells fw_printenv/fw_setenv -- and therefore RAUC's uboot \
bootloader backend -- which bytes hold the U-Boot environment. libubootenv \
ships the tools but no configuration, and without this file both tools fail \
with 'Cannot parse config file'."

LICENSE = "MIT"
LIC_FILES_CHKSUM = "file://${COMMON_LICENSE_DIR}/MIT;md5=0835ade698e0bcf8506ecda2f7b4f302"

SRC_URI = "file://fw_env.config"

# The offsets inside the file are per-machine; keep the package from being shared
# across machines in sstate.
PACKAGE_ARCH = "${MACHINE_ARCH}"

S = "${UNPACKDIR}"

do_install() {
    install -d ${D}${sysconfdir}
    install -m 0644 ${UNPACKDIR}/fw_env.config ${D}${sysconfdir}/fw_env.config
}

# The tools this config is for. Installing the config without them is useless, and
# libubootenv-bin is what RPROVIDES u-boot-fw-utils on wrynose.
RDEPENDS:${PN} += "libubootenv-bin"
