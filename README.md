# microduck_os

Yocto OS image for the microduck robot: RK3566, Rockchip vendor BSP 6.1.
Target hardware is a custom PCB; a **Radxa Zero 3W** stands in for it today.

Replaces the Armbian install that `microduck_daemon/scripts/setup-board.sh`
currently has to fight for control of one UART.

## Build

Builds run on `coach` (see `~/.ssh/config`). `-A` matters: the daemon recipe
fetches a private repo over SSH.

```sh
ssh -A coach
cd /data1/fabien/microduck/microduck_os_main
. kas/coach.env
kas-container build kas/microduck.yml
```

On the laptop, source `kas/lotus.env` instead — `/home` has ~28 GB free, which
will not fit a rockchip build, so its paths point at `/mnt/data`. To reuse
coach's output rather than repeating a multi-hour build, rsync its sstate
straight into `SSTATE_DIR`; sstate is content-addressed, so merging the
directories is all that sharing means:

```sh
rsync -a coach:/data1/fabien/yocto/sstate/ /mnt/data/fabien/yocto/sstate/
```

`kas/microduck.yml` holds every layer pin and no absolute paths, so it is
identical on every host. Paths live in `kas/<host>.env` as **environment
variables** rather than in `local_conf_header`, because kas-container
bind-mounts `KAS_BUILD_DIR`, `DL_DIR` and `SSTATE_DIR` into the container — a
path written into `local.conf` would name a host directory the container cannot
see. `TMPDIR` needs no setting; it defaults to `$KAS_BUILD_DIR/tmp`.

`kas` itself is not installed on coach and does not need to be: `kas-container`
(in `~/bin`) is a shell wrapper around the `ghcr.io/siemens/kas/kas` image, which
also sidesteps coach being Ubuntu 22.04 — older than wrynose's supported host
distros.

### Why no `poky` repo

The combined `poky` repo has **no `wrynose` branch** — it stops at `walnascar`.
`openembedded-core` and `meta-yocto` both have one, which is the three-repo
layout meta-rockchip's README names anyway. `bitbake` is checked out as a
sibling of `openembedded-core`, where `oe-buildenv-internal`'s
`$OEROOT/../bitbake` fallback finds it.

Yocto **6.0 wrynose** is the current LTS (May 2026, supported to April 2030).

## Layers

| Layer | Branch | Why |
|---|---|---|
| `openembedded-core` | wrynose | `meta` |
| `bitbake` | 2.18 | oe-core wrynose sets `BB_MIN_VERSION = "2.18.0"` |
| `meta-yocto` | wrynose | `meta-poky` (the `poky` distro) |
| `meta-openembedded` | wrynose | `meta-oe` (meta-rockchip depends on it), `meta-python`, `meta-multimedia`, `meta-networking` |
| `meta-rockchip` | wrynose | vendor BSP kernel, u-boot, MPP, RGA, libmali, rkaiq, `gstreamer1.0-rockchip` |
| `meta-microduck` | — | this repo |

## Hardware notes

### UART2 is contested

UART2 is both the RK3566 debug console (`ttyFIQ0`, via the vendor BSP's FIQ
debugger) **and** the wire `robotd` drives 15 Dynamixel XL330 servos plus the
`imu_to_dxl` board on at 1 Mbaud.

They cannot share it: a getty does not merely hold the port, it *reads* it,
consuming servo replies, and kernel printk mid-transaction corrupts a reply.

`MICRODUCK_MOTOR_UART` in `conf/machine/include/microduck-common.inc` switches
between them. It defaulted to `0` (console on UART2) through bring-up, and is
`1` since 2026-09-10: the board boots from SD and answers on ssh, no USB-UART
adapter was ever attached to read the console UART2 was being held for, and
`robotd` cannot open its bus without `/dev/ttyS2`. A kernel that dies still
prints to HDMI, via the `console=tty0` the dts patch puts in bootargs.

### Which Zero 3W do you have

The board shipped with two incompatible wifi modules. Check on the existing
Armbian install:

```sh
dmesg | grep -iE 'brcmf|aic8800'
```

`brcmfmac` → AP6212a1 (Broadcom), which is what `microduck-zero3.conf` currently
selects firmware for. `aic8800` → needs an out-of-tree kernel module recipe as
well, and is the reason to specify a Broadcom part on the custom PCB.

Both device trees are built either way; pick the matching one at flash time.

### U-Boot is Rockchip downstream 2017.09

meta-rockchip's `u-boot-rockchip` is Rockchip's downstream U-Boot 2017.09, built
by `make.sh rk3568` with DRAM init from rkbin's TPL. We use it unmodified.

Radxa's fork (`radxa/u-boot` `next-dev-v2024.10`) is, despite the branch name,
*also* 2017.09, and does carry `rk3566-radxa-zero3.dts` and
`radxa-zero3-rk3566_defconfig` — so it is the fallback if the generic rk3568
build cannot reach the kernel. It is not the default because meta-rockchip only
tests the Miniloader path and radxa's defconfig is SPL+ATF.

Two findings from reading those defconfigs, recorded in
`conf/machine/microduck-zero3.conf`:

- **No Rockchip defconfig sets `CONFIG_ENV_IS_IN_*` or
  `CONFIG_BOOTCOUNT_LIMIT`.** RAUC's `uboot` backend needs both. This U-Boot
  instead has Android/AVB A/B (`CONFIG_SPL_AB`, `CONFIG_AVB_LIBAVB_AB`) — a
  different mechanism. Resolve before designing the partition layout.
- **`CONFIG_DEBUG_UART_BASE=0xFE660000` is UART2**, the motor bus, at 1.5 Mbaud.
  U-Boot therefore prints onto the Dynamixel wires at power-on regardless of
  `MICRODUCK_MOTOR_UART`, which only silences Linux. Harmless now; on the custom
  PCB, put the debug UART elsewhere.

## Testing without hardware

Three tiers, and it matters which one can see what.

### Docker (arm64) — userspace only, works today

A container shares the host kernel, so this can never test boot, U-Boot, the DTB,
the AIC8800 driver, SDIO or `/dev/ttyS2`. It *can* test that binaries link and
run, that glibc is right, and that config files are where they should be. Needs
only `qemu-aarch64` binfmt, which both `coach` and `lotus` already have.

```sh
zstd -dc microduck-image-microduck-zero3.rootfs.tar.zst | docker import - microduck-rootfs
docker run --rm --platform linux/arm64 microduck-rootfs \
    /bin/sh -c 'nmcli --version; wpa_supplicant -v | head -1'
```

This is the same pattern `microduck_daemon/scripts/board-test.sh` already uses for
its glibc-2.31 floor check.

### QEMU `-M virt` — the real kernel, real systemd

`qemu-virt.cfg` (see `recipes-kernel/linux/files/`) enables PL011, virtio and
`PCI_HOST_GENERIC` so **the exact kernel and rootfs that ship** boot under QEMU.
Without it you get a silent hang: radxa's defconfig has no PL011, which is the
UART QEMU's virt machine provides.

Needs `qemu-system-aarch64` (`sudo apt install qemu-system-arm` — not installed on
lotus yet):

```sh
qemu-system-aarch64 -M virt -cpu max -smp 4 -m 2048 -nographic \
  -kernel Image \
  -append "root=/dev/vda rw console=ttyAMA0" \
  -drive file=microduck-image-microduck-zero3.rootfs.ext4,format=raw,if=none,id=hd \
  -device virtio-blk-pci,drive=hd \
  -netdev user,id=n0 -device virtio-net-pci,netdev=n0
```

This is what catches the class of bug Docker cannot: systemd unit ordering,
NetworkManager startup, whether `network-online.target` ever settles (which
`updaterd.service` waits on), and `robotd`/`updaterd` socket permissions.

Chosen over a separate `qemuarm64` MACHINE deliberately — that would use a
different kernel and a different tune (cortexa57 vs our cortexa55), sharing
almost no sstate, so it would mean a whole second build *and* test something other
than what ships.

### Only hardware

U-Boot, the Rockchip DTB, the radio associating, `/dev/ttyS2` and the Dynamixel
loop, thermals, and the 50 Hz control-loop jitter question.

## Developing software for this image

You do not need a Yocto tree on your laptop. Use the lowest tier that works:

1. **Pure Rust** (`robotd`, `updaterd`, `robotctl`) — nothing changes. The
   daemon already cross-compiles standalone: `cargo board` (a `cargo-zigbuild`
   alias in its `.cargo/config.toml`), then rsync to the board.
2. **Anything linking native libraries** (i.e. `mediad` against GStreamer/MPP) —
   `bitbake microduck-image -c populate_sdk` on `coach`, copy the installer to
   the laptop, then `source .../environment-setup-aarch64-poky-linux`. That sets
   `CC`, `PKG_CONFIG_PATH` and the sysroot so local cargo/meson/cmake link
   against the real target libraries. `cargo zigbuild` cannot do this — zig
   supplies a libc, not GStreamer headers.
3. **Iterating on a recipe in the image** — `devtool modify <recipe>`,
   `devtool build`, `devtool deploy-target <recipe> root@<robot>`.

Building on the target is not an option: 280 crates on an RK3566 with 1–2 GB
of RAM.

## Status

**The image builds.** 5815 tasks, all succeeded. Not yet booted on hardware.

| | microduck_os | reachy_mini_os |
|---|---|---|
| image | **535 MiB** `.wic` | 5.9 GiB |
| rootfs | **393 MiB** | 4.9 GiB used |
| packages | **~915** | 1190 |
| kernel | 6.1.84-rockchip (vendor BSP, pinned) | 6.18.33+rpt (apt, pinned via `Pin-Priority: -1`) |

Roughly an order of magnitude smaller, mostly because dev headers and toolchain
live in the SDK rather than on the robot.

Artifacts in `tmp/deploy/images/microduck-zero3/`: `.wic` (SD/eMMC),
`update.img` (Rockchip flashing), `boot.img`/`zboot.img`, `Image`, both device
trees, `idblock.img`, `uboot.img`, plus a `.manifest` and an SPDX SBOM.

Partition layout comes from meta-rockchip's `generic-gptdisk.wks`: raw
`idblock.img` outside the table, `uboot` at 2 MiB, `boot`, then `rootfs`.
(`trust.img` is absent and skipped — this U-Boot is SPL+FIT, so ATF is inside
`uboot.img`.)

### WiFi/BT: AIC8800 built in

The board is the SDIO AICSemi AIC8800DS2 variant, so `KERNEL_DEVICETREE` is pinned
to `rk3566-radxa-zero-3w-aic8800ds2.dtb` and the out-of-tree driver comes from
`meta-microduck/recipes-kernel/aic8800`.

Verified present in the rootfs (not merely in the manifest):

```
/usr/lib/modules/6.1.84-rockchip-standard/extra/aic8800_{bsp,btlpm,fdrv}.ko
/usr/lib/modules-load.d/aic8800_{bsp,btlpm,fdrv}.conf
/usr/lib/firmware/aic8800_fw/SDIO/{aic8800,aic8800D80,aic8800D80N,...}/
```

`/lib -> usr/lib` (usrmerge), so the `/lib/firmware/...` path the driver's
`fix-sdio-firmware-path.patch` sets does resolve.

### Before flashing

1. **Built on hardware with a known memory fault** — see `../CONTEXT.md`. Do not
   flash this image. Rebuild after `memtest=4` and after deleting
   `/data1/fabien/yocto/{downloads,sstate}`.
2. **The kernel config is radxa's family-wide defconfig alone.**
   `linux-rockchip.inc` cats `${RK_SOC_FAMILY}_linux.config` onto it with
   `|| true`, and radxa's tree has no `rk3568_linux.config` — so every
   SoC-specific override is silently missing. Mali CSF was the one symptom loud
   enough to break the build; audit `${B}/.config` (particularly the SDIO/MMC
   symbols the aic8800ds2 DTS relies on) before blaming the driver for anything.
3. **Nothing has booted.** A clean compile of a vendor driver against a vendor
   kernel is the first hurdle, not evidence the radio associates.

A/B partitioning and RAUC are M7. The A/B `.wks` is still deliberately unwritten
until slot sizes can be based on a real image size — which now exists.
