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
    file://rauc.cfg \
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

# The camera, and unlike the motor bus it needs no config fragment: the sensor
# drivers and the ISP are already =y in radxa's defconfig. The device tree was
# the entire gap -- with i2c2 and the csi2/isp chain left disabled, the board
# showed no /dev/video* and nothing in dmesg whether or not a camera was
# plugged in, which is a confusing way to look broken.
#
# Zero 3W only: it edits radxa's Zero 3W dts, and the beta board's camera is a
# different sensor described (or not yet) in its own DTS. `:append` lands after
# every `+=`, so it is still ordered after the MICRODUCK_MOTOR_UART line; it
# touches a different part of the same file, so it applies whether or not 0002
# did; both orders are checked. (0001, under PATCHPATH, is unconditional and
# still patches the Zero 3W dts on a beta build -- harmless, that dts is simply
# not built there.)
SRC_URI:append:microduck-zero3 = " file://0003-rk3566-radxa-zero-3w-radxa-camera-8m-imx219.patch"

# A board whose device tree lives in this layer rather than in any kernel tree.
# The machine conf names the file (MICRODUCK_BOARD_DTS); it is copied into the
# kernel source after patching and listed in the rockchip dts Makefile, so the
# normal KERNEL_DEVICETREE build -- and meta-rockchip's per-DTB boot.img, which
# runs `make rockchip/<name>.dtb` through scripts/mkimg -- picks it up like any
# in-tree board.
#
# A file rather than a patch because it is a whole new board in bring-up: it will
# be edited constantly against a schematic that is itself older than the boards,
# and a readable .dts diffs better than a patch of one. Empty for the Zero 3W,
# whose device tree is radxa's own plus the patches above.
#
# A shell postfunc, not do_patch:append -- do_patch is a Python task (AGENTS.md).
MICRODUCK_BOARD_DTS ??= ""
SRC_URI += "${@'file://' + d.getVar('MICRODUCK_BOARD_DTS') if d.getVar('MICRODUCK_BOARD_DTS') else ''}"

microduck_install_board_dts() {
	dts="${MICRODUCK_BOARD_DTS}"
	[ -n "$dts" ] || return 0
	dir="${S}/arch/${ARCH}/boot/dts/rockchip"
	install -m 0644 "${UNPACKDIR}/$dts" "$dir/"
	dtb="${dts%.dts}.dtb"
	grep -qF "$dtb" "$dir/Makefile" || echo "dtb-\$(CONFIG_ARCH_ROCKCHIP) += $dtb" >> "$dir/Makefile"
	grep -qF "$dtb" "$dir/Makefile" || bbfatal "$dtb did not make it into $dir/Makefile"
}
do_patch[postfuncs] += "microduck_install_board_dts"

# The beta board's extra kernel options, and a check that they took. Same idea as
# microduck_check_uboot_config: a fragment line whose dependency is missing is
# silently dropped by merge_config, and the first symptom would be a face board
# with no LEDs on a board that otherwise boots fine.
SRC_URI:append:microduck-beta = " file://microduck-beta.cfg"

microduck_check_kernel_fragments() {
	for frag in ${@' '.join(u.split('://')[1].split(';')[0] for u in d.getVar('SRC_URI').split() if u.startswith('file://microduck-') and u.endswith('.cfg'))}; do
		for line in $(grep -vE '^[[:space:]]*(#|$)' ${UNPACKDIR}/$frag); do
			grep -qxF "$line" ${B}/.config || bbfatal "$frag: '$line' is not in the merged ${B}/.config"
		done
	done
}
do_configure[postfuncs] += "microduck_check_kernel_fragments"
