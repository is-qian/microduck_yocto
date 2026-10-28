#!/bin/bash
# Test microduck-slot-health's verdict, before a board.
#
# Why this exists: the script decides whether RAUC keeps an update. Too strict and a
# board with a flaky radio rejects every OS update; too loose and a kernel that lost
# its wifi driver is kept on a robot nobody can reach. Both are cheap to get wrong and
# expensive to find on hardware, so each case the script distinguishes is run here
# against a fake /sys, /lib/modules and record, with systemctl, uname and rauc stubbed.
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
cat > "$work/bin/systemctl" <<'EOF'
#!/bin/sh
# systemctl show -p <prop> --value <unit>
case "$3" in
ActiveState) cat "$STUB/state" ;;
NRestarts) cat "$STUB/restarts" ;;
esac
EOF
cat > "$work/bin/uname" <<'EOF'
#!/bin/sh
cat "$STUB/kernel"
EOF
# Only the booted slot's object: the script splits RAUC's JSON one slot per line with
# GNU sed, which the board has; emitting one object keeps this test portable.
cat > "$work/bin/rauc" <<'EOF'
#!/bin/sh
cat "$STUB/rauc"
EOF
chmod +x "$work/bin/"*

pass=0; fail=0

# run NAME EXPECT(0|1) -- with the case's state already in $STUB, $SYSROOT, $MODS, $RECORD
run() {
	name=$1; want=$2
	out=$(PATH="$work/bin:$PATH" STUB=$STUB SLOT_HEALTH_UPTIME=$STUB/uptime \
		SLOT_HEALTH_SYS=$SYSROOT SLOT_HEALTH_MODULES=$MODS SLOT_HEALTH_RECORD=$RECORD \
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
	echo active > "$STUB/state"; echo 0 > "$STUB/restarts"
	echo 6.1.99 > "$STUB/kernel"
	slot A ""
	radios wifi bluetooth
}
slot() {  # bootname, RAUC install timestamp ("" for a flashed slot)
	if [ -n "$2" ]; then installed=",\"installed\":{\"timestamp\":\"$2\"}"; else installed=; fi
	printf '{"rootfs.0":{"bootname":"%s","state":"booted"%s}}\n' "$1" "$installed" > "$STUB/rauc"
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
grep -qx 'radios=wifi bluetooth' "$RECORD" && grep -qx 'os=A@' "$RECORD" ||
	{ fail=$((fail + 1)); echo "FAIL first boot records its OS and radios"; }

fresh no-radios-ever
radios
run "a board that never had a radio passes (nothing to lose)" 0

fresh daemon
echo failed > "$STUB/state"
run "a failed daemon fails the slot" 1

fresh loop
echo 3 > "$STUB/restarts"
run "a crash-looping daemon fails the slot" 1

fresh kernel
rm -rf "$MODS/6.1.99"; mkdir -p "$MODS/6.1.120"
run "a kernel without its modules fails (U-Boot's fallback kernel)" 1
case $last in *"not the slot's own kernel"*) ;; *) fail=$((fail + 1)); echo "FAIL the kernel failure says why";; esac

fresh update-keeps-radios
run "flashed A passes" 0
slot B "2026-10-28T10:00:00Z"
run "an update to B with both radios passes" 0
grep -qx 'os=B@2026-10-28T10:00:00Z' "$RECORD" ||
	{ fail=$((fail + 1)); echo "FAIL a passing update becomes the record"; }

fresh update-loses-wifi
run "flashed A passes" 0
slot B "2026-10-28T10:00:00Z"; radios bluetooth
run "an update to B that lost wifi fails" 1
case $last in *"wifi(present under the previous OS"*) ;; *) fail=$((fail + 1)); echo "FAIL the wifi failure says why";; esac
grep -qx 'os=A@' "$RECORD" ||
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
grep -qx 'radios=bluetooth' "$RECORD" ||
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
grep -qx 'radios=wifi bluetooth' "$RECORD" ||
	{ fail=$((fail + 1)); echo "FAIL a gained radio raises the record"; }

fresh reinstall
run "flashed A passes" 0
slot B "2026-10-28T10:00:00Z"
run "update to B passes" 0
slot B "2026-10-29T09:00:00Z"; radios bluetooth
run "a second update into the same slot that lost wifi still fails" 1

fresh unwritable
mkdir -p "$(dirname "$RECORD")"; chmod 0555 "$(dirname "$RECORD")"
run "an unwritable record does not fail the slot" 0
chmod 0755 "$(dirname "$RECORD")"

echo
echo "$pass passed, $fail failed"
[ "$fail" = 0 ]
