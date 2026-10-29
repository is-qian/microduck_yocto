#!/bin/bash
# Test microduck-slot-health's verdict, before a board.
#
# Why this exists: the script decides whether RAUC keeps an update. Too strict and a
# board with a flaky radio rejects every OS update; too loose and a kernel that lost
# its wifi driver is kept on a robot nobody can reach. Both are cheap to get wrong and
# expensive to find on hardware, so each case the script distinguishes is run here
# against a fake /sys, /lib/modules and record, with robot-boot-check, uname, rauc and
# sleep stubbed. The sleep stub moves the fake uptime on instead of waiting, so the
# radio wait runs to its end without taking any time.
#
# Runs anywhere with bash, in well under a second. TEST_SH picks the shell the script
# under test runs in; dash is the stricter POSIX check:
#
#   scripts/test-slot-health.sh
#   TEST_SH=dash scripts/test-slot-health.sh
set -u

here=$(cd "$(dirname "$0")/.." && pwd)
script=$here/meta-microduck/recipes-microduck/microduck-slot-health/files/microduck-slot-health

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

# The stubs read what each case sets in these files.
mkdir -p "$work/bin"
# The daemon's verdict is its exit status, and only --dry-run may be asked: anything
# else would let the OS's gate hand a release to robot-rescue.
cat > "$work/bin/robot-boot-check" <<'EOF'
#!/bin/sh
[ "$*" = --dry-run ] || { echo "robot-boot-check called as: $*"; exit 99; }
echo "robot-boot-check: stub verdict $(cat "$STUB/bootcheck")"
exit "$(cat "$STUB/bootcheck")"
EOF
# Advances the fake uptime by its argument, and brings wifi up once the uptime
# reaches $STUB/wifi-at, when a case sets one.
cat > "$work/bin/sleep" <<'EOF'
#!/bin/sh
up=$(( $(cut -d. -f1 "$STUB/uptime") + $1 ))
echo "$up.00" > "$STUB/uptime"
[ ! -f "$STUB/wifi-at" ] || [ "$up" -lt "$(cat "$STUB/wifi-at")" ] || mkdir -p "$SYSROOT/class/net/wlan0"
EOF
cat > "$work/bin/uname" <<'EOF'
#!/bin/sh
cat "$STUB/kernel"
EOF
# A missing $STUB/rauc is a rauc that fails.
cat > "$work/bin/rauc" <<'EOF'
#!/bin/sh
cat "$STUB/rauc" 2>/dev/null
EOF
chmod +x "$work/bin/"*

pass=0; fail=0

# run NAME EXPECT(0|1) -- with the case's state already in $STUB, $SYSROOT, $MODS, $RECORD
run() {
	name=$1; want=$2
	out=$(PATH="$work/bin:$PATH" STUB=$STUB SYSROOT=$SYSROOT SLOT_HEALTH_UPTIME=$STUB/uptime \
		SLOT_HEALTH_SYS=$SYSROOT SLOT_HEALTH_MODULES=$MODS SLOT_HEALTH_RECORD=$RECORD \
		SLOT_HEALTH_BOOT_CHECK=$BOOT_CHECK \
		${TEST_SH:-sh} "$script" 2>&1)
	got=$?
	if [ "$got" = "$want" ]; then
		pass=$((pass + 1)); echo "ok   $name"
	else
		fail=$((fail + 1)); echo "FAIL $name (exit $got, wanted $want)"
		echo "$out" | sed 's/^/     | /'
	fi
	last=$out
}

# A fresh board: daemons up, the slot's kernel, wifi and Bluetooth, slot A as flashed.
fresh() {
	case_dir=$work/$1; rm -rf "$case_dir"; mkdir -p "$case_dir"
	STUB=$case_dir/stub SYSROOT=$case_dir/sys MODS=$case_dir/modules RECORD=$case_dir/data/radios
	mkdir -p "$STUB" "$SYSROOT/class/net" "$SYSROOT/class/bluetooth" "$MODS/6.1.99"
	echo 999.00 > "$STUB/uptime"
	echo 0 > "$STUB/bootcheck"; BOOT_CHECK=$work/bin/robot-boot-check
	echo 6.1.99 > "$STUB/kernel"
	slot A ""
	radios wifi bluetooth
}
# bootname, RAUC install timestamp ("" for a flashed slot). Shaped like rauc status
# on the board: the other slot first, with a timestamp of its own, so the script has
# to split the slots to pick the booted one.
slot() {
	if [ -n "$2" ]; then installed=",\"installed\":{\"timestamp\":\"$2\",\"count\":1}"; else installed=; fi
	other=B; [ "$1" = A ] || other=A
	printf '{"booted":"%s","slots":[{"rootfs.1":{"bootname":"%s","state":"inactive","slot_status":{"installed":{"timestamp":"1999-01-01T00:00:00Z","count":1}}}},{"rootfs.0":{"bootname":"%s","state":"booted","slot_status":{"bundle":{"build":"1"}%s}}}]}\n' \
		"$1" "$other" "$1" "$installed" > "$STUB/rauc"
}
radios() {
	rm -rf "$SYSROOT/class/net/"* "$SYSROOT/class/bluetooth/"*
	for r in "$@"; do
		case $r in
		wifi) mkdir -p "$SYSROOT/class/net/wlan0" ;;
		bluetooth) mkdir -p "$SYSROOT/class/bluetooth/hci0" ;;
		esac
	done
	mkdir -p "$SYSROOT/class/net/lo"
}

fresh first-boot
run "first boot with no record passes" 0
grep -qx 'A@ wifi bluetooth' "$RECORD" ||
	{ fail=$((fail + 1)); echo "FAIL first boot records its OS and radios"; }

fresh no-radios-ever
radios
run "a board that never had a radio passes (nothing to lose)" 0

fresh daemon
echo 3 > "$STUB/bootcheck"
run "a release the boot check says did not come up fails the slot" 1
case $last in *"robot-boot-check --dry-run exited 3"*) ;; *) fail=$((fail + 1)); echo "FAIL the daemon failure says why";; esac
case $last in *"stub verdict 3"*) ;; *) fail=$((fail + 1)); echo "FAIL the boot check's own words reach the journal";; esac

fresh check-errors
echo 1 > "$STUB/bootcheck"
run "a boot check that could not answer fails the slot" 1

fresh no-check
BOOT_CHECK=$work/nowhere/robot-boot-check
run "no boot check to ask fails the slot" 1

fresh kernel
rm -rf "$MODS/6.1.99"; mkdir -p "$MODS/6.1.120"
run "a kernel without its modules fails (U-Boot's fallback kernel)" 1
case $last in *"not the slot's own kernel"*) ;; *) fail=$((fail + 1)); echo "FAIL the kernel failure says why";; esac

fresh update-keeps-radios
run "flashed A passes" 0
slot B "2026-10-28T10:00:00Z"
run "an update to B with both radios passes" 0
[ "$(tail -n 1 "$RECORD")" = 'B@2026-10-28T10:00:00Z wifi bluetooth' ] ||
	{ fail=$((fail + 1)); echo "FAIL a passing update becomes the latest line"; }

fresh update-loses-wifi
run "flashed A passes" 0
slot B "2026-10-28T10:00:00Z"; radios bluetooth
run "an update to B that lost wifi fails" 1
case $last in *"wifi(present under A@, the latest OS that passed"*) ;; *) fail=$((fail + 1)); echo "FAIL the wifi failure says why";; esac
[ "$(cat "$RECORD")" = 'A@ wifi bluetooth' ] ||
	{ fail=$((fail + 1)); echo "FAIL a failed boot leaves the record alone"; }
slot A ""; radios wifi bluetooth
run "the rollback to A passes" 0

fresh update-loses-bt
run "flashed A passes" 0
slot B "2026-10-28T10:00:00Z"; radios wifi
run "an update to B that lost Bluetooth fails" 1

fresh hardware-dies
run "flashed A passes" 0
radios bluetooth
run "the same OS losing wifi passes: hardware, not an update" 0
case $last in *"warning: wifi"*) ;; *) fail=$((fail + 1)); echo "FAIL the same-OS loss is logged";; esac
grep -qx 'A@ bluetooth' "$RECORD" ||
	{ fail=$((fail + 1)); echo "FAIL the record follows the hardware down"; }
slot B "2026-10-28T10:00:00Z"
run "so the next update is not blocked by the dead chip" 0

fresh beta-without-bt
radios wifi
run "a beta board with no Bluetooth yet passes" 0
slot B "2026-10-28T10:00:00Z"
run "and its update is held only to wifi" 0

fresh radio-gained
radios wifi
run "A with wifi only passes" 0
slot B "2026-10-28T10:00:00Z"; radios wifi bluetooth
run "an update that brings Bluetooth up passes" 0
grep -qx 'B@2026-10-28T10:00:00Z wifi bluetooth' "$RECORD" ||
	{ fail=$((fail + 1)); echo "FAIL a gained radio raises the record"; }
slot A ""; radios wifi
run "a rollback to A, which passed without Bluetooth, passes" 0
slot B "2026-10-29T09:00:00Z"; radios
run "and the next update is held to A's radios, the latest that passed" 1

fresh rauc-fails
radios wifi
run "A with wifi only passes" 0
rm "$STUB/rauc"; radios
run "a boot where rauc status fails is not compared" 0
case $last in *"neither compared nor recorded"*) ;; *) fail=$((fail + 1)); echo "FAIL the skipped comparison is logged";; esac
[ "$(cat "$RECORD")" = 'A@ wifi' ] ||
	{ fail=$((fail + 1)); echo "FAIL a boot with no slot name leaves the record alone"; }

fresh reinstall
run "flashed A passes" 0
slot B "2026-10-28T10:00:00Z"
run "update to B passes" 0
slot B "2026-10-29T09:00:00Z"; radios bluetooth
run "a second update into the same slot that lost wifi still fails" 1

fresh late-wifi
run "flashed A passes" 0
slot B "2026-10-28T10:00:00Z"; radios bluetooth
echo 20.00 > "$STUB/uptime"; echo 40 > "$STUB/wifi-at"
run "an update whose wifi comes up at 40 s is waited for, and passes" 0
case $(cut -d. -f1 "$STUB/uptime") in 40) ;; *) fail=$((fail + 1)); echo "FAIL the wait stops once the radio is up (uptime $(cat "$STUB/uptime"))";; esac

fresh never-wifi
run "flashed A passes" 0
slot B "2026-10-28T10:00:00Z"; radios bluetooth
echo 20.00 > "$STUB/uptime"
run "an update whose wifi never comes up fails at 180 s" 1
case $(cut -d. -f1 "$STUB/uptime") in 180) ;; *) fail=$((fail + 1)); echo "FAIL the wait ends at the deadline (uptime $(cat "$STUB/uptime"))";; esac

fresh same-os-waits-for-nothing
run "flashed A passes" 0
radios bluetooth
echo 20.00 > "$STUB/uptime"
run "the same OS missing wifi early passes without waiting" 0
case $(cut -d. -f1 "$STUB/uptime") in 20) ;; *) fail=$((fail + 1)); echo "FAIL a radio this OS is not held to is not waited for";; esac

fresh unwritable
mkdir -p "$(dirname "$RECORD")"; chmod 0555 "$(dirname "$RECORD")"
run "an unwritable record does not fail the slot" 0
chmod 0755 "$(dirname "$RECORD")"

echo
echo "$pass passed, $fail failed"
[ "$fail" = 0 ]
