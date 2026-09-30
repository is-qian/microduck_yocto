# A writable U-Boot environment and A/B slot selection for RAUC.
# docs/agents/rauc.md has the why and the traps.
#
# Named without `_%`: the upstream recipe file is u-boot-rockchip.bb, and an
# unmatched bbappend fails the whole parse.

FILESEXTRAPATHS:prepend := "${THISDIR}/files:"

# `+=`, never `=`: SRC_URI carries a second git entry, rkbin, that make.sh needs.
#
# rauc-env.cfg reaches the defconfig despite make.sh: oe-core's uboot_configure()
# still runs merge_config.sh over every .cfg in SRC_URI. fw_env.config is installed
# by oe-core's u-boot.inc into ${PN}-env. The patch prepends slot selection to
# bootcmd; run scripts/test-uboot-bootcmd.sh before changing it.
SRC_URI += " \
    file://rauc-env.cfg \
    file://fw_env.config \
    file://0001-evb_rk3568-select-the-RAUC-slot-before-Rockchip-s-boot.patch \
"

# The name libubootenv falls back to on a blank environment. Without that file the
# first fw_setenv saves only one variable, and U-Boot then loses bootcmd.
UBOOT_INITIAL_ENV = "u-boot-initial-env"

# Fail the build, not the board, if the fragment did not take effect: every line of
# rauc-env.cfg must appear verbatim in the merged .config. Without it the env
# silently reverts to ENV_IS_NOWHERE.
microduck_check_uboot_config() {
	for line in $(grep -vE '^[[:space:]]*(#|$)' ${UNPACKDIR}/rauc-env.cfg); do
		grep -qxF "$line" ${B}/.config || bbfatal "rauc-env.cfg: '$line' is not in the merged ${B}/.config"
	done
}
do_configure[postfuncs] += "microduck_check_uboot_config"
