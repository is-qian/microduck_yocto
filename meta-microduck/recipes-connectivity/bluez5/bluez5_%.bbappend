# TEMPORARY, remove-with-real-radio -- Privacy = device, for the AIC8800 only.
#
# This is the `--weird-ble` half of microduck_daemon's scripts/setup-board.sh
# (configure_bluetooth), baked into the image because nothing runs that script
# on a Yocto board: provision-board.sh is the Armbian path, and its two BLE
# flags -- --weird-ble and --pause-btd-on-pair -- otherwise reach us not at all.
# The other half, the /var/lib/robot/weird-ble marker, is written by
# microduck-daemon; both carry this tag and both go together.
#
# What it buys. On roughly half of ten Radxa Zero 3W units measured upstream, an
# Xbox pad will not bond at all under BlueZ's default `Privacy = off`, and does
# bond under `device`. Nothing measurable separates those units from the rest:
# same kernel, same BlueZ, byte-identical aic8800 firmware.
#
# What it costs, stated plainly because it is not free. On a board that did NOT
# need it, `device` breaks *reconnection*: the pad bonds, then flaps with
# `PIN or Key Missing (0x06)` -- 46 times in 45 seconds on 50:37:CD:16:1D:90,
# 2026-08-19. That failure looks like success at first, which is the worst shape
# of it. Upstream therefore dropped `device` as its default and split the flags
# in two. We take it here anyway, as an image-wide default, because there is one
# image and it is flashed onto the bring-up boards; a board that flaps wants this
# bbappend deleted (or `Privacy = off` set by hand in /etc/bluetooth/main.conf,
# followed by a reboot -- restarting bluetoothd leaves the kernel holding hci0
# and the daemon reporting "No default controller available").
#
# Both halves are workarounds for the AIC8800, which is not the radio that ships.
# When the real one lands, this file, the marker in microduck-daemon and
# `BtdPaused` in microduck_daemon's robotctl all go. See the tracking issue.
#
# Matched with `%` rather than 5.86 so an oe-core version bump does not silently
# drop the setting -- the whole point is that its absence is invisible until a
# pad will not pair.

do_install:append() {
	bt_conf="${D}${sysconfdir}/bluetooth/main.conf"

	# bluez5 configures --enable-datafiles, so upstream's main.conf (with
	# `#Privacy = off` commented out under [General]) is installed. Handled
	# defensively all the same: a version that stops shipping it, or moves the
	# key, must not turn this into a no-op.
	if [ -f "$bt_conf" ]; then
		if grep -Eq '^[[:space:]]*#?[[:space:]]*Privacy[[:space:]]*=' "$bt_conf"; then
			sed -i -E 's|^[[:space:]]*#?[[:space:]]*Privacy[[:space:]]*=.*|Privacy = device|' "$bt_conf"
		elif grep -q '^\[General\]' "$bt_conf"; then
			sed -i '/^\[General\]/a Privacy = device' "$bt_conf"
		else
			printf '\n[General]\nPrivacy = device\n' >> "$bt_conf"
		fi
	else
		install -d ${D}${sysconfdir}/bluetooth
		printf '[General]\nPrivacy = device\n' > "$bt_conf"
	fi

	grep -Eq '^Privacy = device$' "$bt_conf" \
		|| bbfatal "bluez5: Privacy = device did not apply to main.conf"
}
