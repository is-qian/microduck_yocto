#!/bin/bash
# Test the RAUC slot-selection bootcmd in a U-Boot sandbox, before a board.
#
# Why this exists: the script runs as U-Boot's bootcmd on a board with no serial
# console. A hush syntax error there aborts bootcmd at a prompt nobody can see,
# and the only recovery is reflashing the card. So every change to the patch
# gets run here first.
#
# Why a *mainline* v2017.09 sandbox is faithful -- ONLY WITH THE VENDOR'S
# lib/strto.c COPIED IN. The vendor tree the board runs (JeffyCN/mirrors u-boot
# @ a93658f8) has common/cli_hush.c, cmd/test.c, cmd/setexpr.c, lib/ctype.c and
# the setenv/env_get functions of cmd/nvedit.c byte-identical to v2017.09, and
# autoboot runs bootcmd through the same run_command_list() that `u-boot -c`
# uses. lib/strto.c is the exception, and it matters: the vendor's
# simple_strtol honours the base it is given, so `test 0x3 -gt 0` (test passes
# base 10) is FALSE there, while mainline's switches to hex on "0x" and says
# true. A plain mainline sandbox passed a script that looped forever on the
# board (2026-09-25). With the vendor's strto.c the sandbox reproduced that
# board failure exactly, 8 of 9 cases, which is what makes it trustworthy now.
# The vendor tree's own sandbox does not build (Rockchip code is compiled
# unconditionally).
#
# Build the sandbox once (a few minutes; GCC 14 needs the relaxed flags):
#
#   git clone --depth 1 -b v2017.09 https://source.denx.de/u-boot/u-boot.git
#   cd u-boot
#   curl -sfL https://raw.githubusercontent.com/JeffyCN/mirrors/a93658f8f45dc0266be21840931131b10c325e03/lib/strto.c -o lib/strto.c
#   make sandbox_defconfig
#   sed -i 's/^CONFIG_SANDBOX_SDL=y/# CONFIG_SANDBOX_SDL is not set/' .config
#   make olddefconfig
#   sed -i '/always += .*_libfdt.so/s/^/#/' tools/Makefile   # needs python2 otherwise
#   W="-fcommon -Wno-error=implicit-function-declaration -Wno-error=incompatible-pointer-types -Wno-error=int-conversion -Wno-error=implicit-int -std=gnu11"
#   make -j"$(nproc)" HOSTCFLAGS="$W" KCFLAGS="$W" NO_SDL=1 u-boot
#
# Then:  scripts/test-uboot-bootcmd.sh /path/to/u-boot/u-boot
#
# The sandbox has no environment storage, so `saveenv` is stubbed with an echo;
# that it actually persists on the board was verified there, not here.
set -euo pipefail

SANDBOX=${1:?usage: $0 /path/to/v2017.09/sandbox/u-boot}
PATCH=$(dirname "$0")/../meta-microduck/recipes-bsp/u-boot/files/0001-evb_rk3568-select-the-RAUC-slot-before-Rockchip-s-boot.patch
work=$(mktemp -d); trap 'rm -rf "$work"' EXIT

# The macro exactly as the patch adds it, compiled so the C string escaping is
# the compiler's, not a hand re-typing. RKIMG_BOOTCOMMAND is stubbed with a
# marker so each case can tell whether the boot chain was reached.
{
	echo '#include <stdio.h>'
	echo '#define RKIMG_BOOTCOMMAND "echo REACHED_BOOT_CHAIN;"'
	sed -n 's/^+//; /^#define MICRODUCK_RAUC_SELECT/,/rauc\.slot=\${rauc_slot}; "$/p' "$PATCH"
	echo 'int main(void){ fputs(MICRODUCK_RAUC_SELECT RKIMG_BOOTCOMMAND, stdout); return 0; }'
} > "$work/gen.c"
gcc -o "$work/gen" "$work/gen.c"
BOOTCMD=$("$work/gen" | sed 's/saveenv;/echo SAVEENV_CALLED;/g')
[ -n "$BOOTCMD" ] || { echo "could not extract MICRODUCK_RAUC_SELECT from $PATCH"; exit 1; }

pass=0; fail=0
check() {  # name, env setup, then expected substrings
	local name=$1 setup=$2; shift 2
	local out ok=1
	out=$("$SANDBOX" -c "$setup $BOOTCMD printenv BOOT_ORDER BOOT_A_LEFT BOOT_B_LEFT bootargs" 2>&1) || true
	for want in "$@"; do grep -qF -- "$want" <<< "$out" || { ok=0; echo "     missing: $want"; }; done
	grep -qiE 'syntax error|Unknown command' <<< "$out" && { ok=0; echo "     parser complained"; }
	if [ $ok = 1 ]; then pass=$((pass + 1)); echo "ok   $name"; else fail=$((fail + 1)); echo "FAIL $name"; fi
}

check "virgin env: seeds, picks A, decrements" "" \
	"booting slot A (rootfs_a)" "BOOT_ORDER=A B" "BOOT_A_LEFT=2" "BOOT_B_LEFT=3" \
	"bootargs=root=PARTLABEL=rootfs_a rauc.slot=A" "SAVEENV_CALLED" "REACHED_BOOT_CHAIN"
check "after rauc install: B first" \
	"setenv BOOT_ORDER 'B A'; setenv BOOT_A_LEFT 3; setenv BOOT_B_LEFT 3;" \
	"booting slot B (rootfs_b)" "BOOT_A_LEFT=3" "BOOT_B_LEFT=2" "bootargs=root=PARTLABEL=rootfs_b rauc.slot=B"
check "B exhausted: falls back to A, BOOT_ORDER untouched" \
	"setenv BOOT_ORDER 'B A'; setenv BOOT_A_LEFT 3; setenv BOOT_B_LEFT 0;" \
	"booting slot A (rootfs_a)" "BOOT_ORDER=B A" "BOOT_A_LEFT=2" "BOOT_B_LEFT=0"
check "A marked bad by RAUC (absent from BOOT_ORDER)" \
	"setenv BOOT_ORDER B; setenv BOOT_A_LEFT 0; setenv BOOT_B_LEFT 3;" \
	"booting slot B (rootfs_b)" "BOOT_ORDER=B" "BOOT_B_LEFT=2"
check "hex counter: a (=10) is > 0, decrements to 9" \
	"setenv BOOT_ORDER 'A B'; setenv BOOT_A_LEFT a; setenv BOOT_B_LEFT 3;" \
	"booting slot A" "BOOT_A_LEFT=9"
check "counter 1 still boots, reaches 0" \
	"setenv BOOT_ORDER 'A B'; setenv BOOT_A_LEFT 1; setenv BOOT_B_LEFT 3;" \
	"booting slot A" "BOOT_A_LEFT=0"
check "empty BOOT_A_LEFT: seeded, no abort" \
	"setenv BOOT_ORDER 'A B'; setenv BOOT_B_LEFT 3;" \
	"booting slot A" "BOOT_A_LEFT=2"
check "unknown bootname skipped" \
	"setenv BOOT_ORDER 'X B'; setenv BOOT_A_LEFT 3; setenv BOOT_B_LEFT 3;" \
	"booting slot B"

# Both exhausted: counters reset, then `reset` -- which ends the sandbox process,
# so the boot chain must never be reached.
out=$("$SANDBOX" -c "setenv BOOT_ORDER 'A B'; setenv BOOT_A_LEFT 0; setenv BOOT_B_LEFT 0; $BOOTCMD" 2>&1) || true
if grep -qF "no slot has attempts left" <<< "$out" && ! grep -qF REACHED_BOOT_CHAIN <<< "$out"; then
	pass=$((pass + 1)); echo "ok   both exhausted: resets and reboots, never boots"
else fail=$((fail + 1)); echo "FAIL both exhausted: resets and reboots, never boots"; fi

echo "---- $pass passed, $fail failed  (bootcmd ${#BOOTCMD} chars; CONFIG_SYS_CBSIZE does not bound it)"
[ "$fail" = 0 ]
