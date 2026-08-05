#!/bin/bash
# Boot the image under qemu, log in on the serial console, run a handful of
# checks, then power off. Output lands in qemu-test.log.
#
# stdin is a FIFO so we can wait for the login prompt to actually appear before
# typing -- input sent before agetty is listening is simply lost.
#
# initcall_blacklist=rockchip_drm_init is required: rockchip_drm_init asks
# Rockchip's TF-A for the DRAM map over a vendor SIP SMC (x0=0x82000009), which is
# an undefined instruction on qemu virt (no EL3, no Rockchip firmware). The oops
# lands in kernel_init and kills init. Test-only -- the shipping kernel needs
# Rockchip DRM.
set -u
cd "$(dirname "$0")"

LOG=qemu-test.log
FIFO=$(mktemp -u /tmp/qemu-in.XXXXXX)
mkfifo "$FIFO"

rm -f "$LOG"
timeout 600 qemu-system-aarch64 \
	-M virt -cpu cortex-a55 -smp 4 -m 2048 -nographic \
	-kernel Image \
	-append "root=/dev/vda rw console=ttyAMA0 initcall_blacklist=rockchip_drm_init" \
	-drive file=rootfs.ext4,format=raw,if=none,id=hd \
	-device virtio-blk-pci,drive=hd \
	-netdev user,id=n0 -device virtio-net-pci,netdev=n0 \
	< "$FIFO" > "$LOG" 2>&1 &
QPID=$!

# Hold the FIFO open for the lifetime of the run.
exec 3> "$FIFO"

wait_for() {
	local pat=$1 limit=${2:-400} n=0
	until grep -aq "$pat" "$LOG" 2>/dev/null; do
		sleep 3
		n=$((n + 3))
		if [ $n -ge "$limit" ]; then echo "TIMEOUT waiting for: $pat"; return 1; fi
		kill -0 $QPID 2>/dev/null || { echo "qemu exited early"; return 1; }
	done
}

say() { printf '%s\n' "$1" >&3; sleep "${2:-4}"; }

wait_for "login:" || { kill $QPID 2>/dev/null; exit 1; }
say root 6

say 'echo MARK-BEGIN'
# --wait blocks until startup actually completes. Without it the answers below are
# sampled mid-boot: the first run of this script reported is-system-running
# "starting" and network-online.target "inactive" purely because it asked too
# early, which is misleading rather than merely useless.
say 'systemctl is-system-running --wait' 90
say 'systemctl --no-pager --failed'
say 'systemctl is-active NetworkManager wpa_supplicant dbus'
say 'systemctl is-enabled NetworkManager'
say 'systemctl is-active network-online.target'
say 'nmcli -t -f DEVICE,TYPE,STATE device'
say 'lsmod | grep aic'
say 'ls /lib/firmware/aic8800_fw/SDIO'
# network-online.target is inert until some unit Wants it, and nothing in the image
# does yet -- but updaterd.service will (Wants=/After=). So check it CAN be
# satisfied: NetworkManager-wait-online hanging is a known failure mode that would
# stall the updater on real hardware.
say 'systemctl start network-online.target; echo rc=$?' 45
say 'systemctl is-active network-online.target NetworkManager-wait-online.service'

# Guards against services creeping back in. poky enables rpcbind (DISTRO_FEATURES
# nfs), dnsmasq (NetworkManager PACKAGECONFIG) and busybox-syslog
# (VIRTUAL-RUNTIME_base-utils-syslog) by default; none belong on a robot.
say 'ls /etc/systemd/system/multi-user.target.wants/'

# cfg80211 falls back to restrictive built-in regulatory rules without this.
say 'ls /lib/firmware/regulatory.db'

say 'timedatectl | head -n 3'
say 'echo MARK-END'
say poweroff 10

wait_for "reboot: Power down" 120 || true
kill $QPID 2>/dev/null
exec 3>&-
rm -f "$FIFO"
echo "=== done, $(wc -l < "$LOG") lines ==="
