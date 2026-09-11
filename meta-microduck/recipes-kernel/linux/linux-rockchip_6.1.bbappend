# Rockchip vendor BSP 6.1, but from Radxa's fork rather than JeffyCN's mirror.
#
# Same Rockchip 6.1 base; the difference is that Radxa's tree is the only one
# carrying rk3566-radxa-zero-3w-*.dts. JeffyCN's kernel-6.1-2024_04_14 has
# quartz64 / soquartz-cm4 / roc-pc / evb only, so on that tree we would have to
# port the Zero 3 device tree by hand.
#
# Verified present in this tree: drivers/media/i2c/gc2093.c (the camera sensor).

FILESEXTRAPATHS:prepend := "${THISDIR}/files:"

SRCREV = "270a678f8364683447f994adec9d31eb182eaf85"
SRC_URI = " \
    git://github.com/radxa/kernel.git;protocol=https;nobranch=1;branch=linux-6.1-stan-rkr4.1 \
    file://cgroups.cfg \
    file://mali-csf.cfg \
    file://qemu-virt.cfg \
"

# WATCH OUT when adding config fragments. linux-rockchip.inc builds the defconfig
# with:
#
#     DEFCONFIGS="rockchip_linux_defconfig ${RK_SOC_FAMILY}_linux.config"
#     cat ${KBUILD_DEFCONFIGS} > ${KBUILD_DEFCONFIG} || true
#
# radxa's tree has no rk3568_linux.config, and that `|| true` swallows the
# missing file without a warning -- so every SoC-specific override meta-rockchip
# expects to apply is silently absent, and we get radxa's family-wide
# rockchip_linux_defconfig alone. mali-csf.cfg exists because of exactly that.
#
# Assume nothing about the effective config: check
# ${B}/.config after do_configure rather than trusting the defconfig name.

# meta-rockchip's linux-rockchip.inc points PATCHPATH at its own
# linux-rockchip_6.1/ directory: a do_mounts retry plus two drm-rockchip
# cursor/plane HACKs, all cut against JeffyCN's tree. None are needed here, and
# a failed apply is a hard error rather than tolerable fuzz -- so point
# PATCHPATH at our own directory instead. auto-patch.bbclass no-ops when the
# directory does not exist, which is the case until we need a patch.
#
# `:=` because THISDIR is a parse-time variable; a lazy `=` would expand to the
# wrong layer.
PATCHPATH := "${THISDIR}/patches"

# See MICRODUCK_MOTOR_UART in conf/machine/include/microduck-common.inc.
#
# Two halves, and the config fragment alone is a no-op. motorbus.cfg turns
# CONFIG_FIQ_DEBUGGER off, which frees the *driver*; the dts patch enables &uart2
# and disables the fiq_debugger node, which frees the *hardware*. Rockchip's
# rk3568-linux.dtsi leaves &uart2 disabled and assigns UART2 to fiq_debugger, so
# with only the fragment applied the board came up on 2026-09-11 with no
# /dev/ttyFIQ0 (correct) and still no /dev/ttyS2 -- fe660000.serial was not even a
# platform device, and robotd kept looping on "cannot open the bus".
#
# The patch goes through SRC_URI rather than the patches/ directory above because
# PATCHPATH is unconditional: uart2 must stay disabled when MICRODUCK_MOTOR_UART
# is "0", or that mode loses the serial console it exists to keep.
SRC_URI += "${@bb.utils.contains('MICRODUCK_MOTOR_UART', '1', 'file://motorbus.cfg file://0002-rk3566-radxa-zero-3w-uart2-for-the-motor-bus.patch', '', d)}"
