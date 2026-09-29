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

### Two images: dev and shippable

`kas/microduck.yml` builds **`microduck-image-dev`** by default, which is what
bring-up needs — `microduck-image` has no login at all. To build the shippable
one:

```sh
kas build kas/microduck.yml --target microduck-image
```

or, on the HF Jobs path, `HFJOB_TARGET=microduck-image scripts/build-hfjob.sh`.

`microduck-image-dev.bb` `require`s `microduck-image.bb` and only ever adds, so a
dev board is a strict superset of the shipping one and anything verified on it
holds for both. What it adds: password-less root login, `microduck-daemon-padd`,
and enough userspace to debug over ssh (`coreutils`, `util-linux`, `strace`,
`iw`, `tcpdump`, `evtest`, `less`, `e2fsprogs`).

The password-less root login is the only difference that *has* to be an image
rather than configuration — you need a login before you can configure anything,
and the robot has no package manager to add one later. The team dev key and
`allow_dev_keys = true` are deliberately **not** baked into either image: they
are a per-board, revocable trust decision, and `microduck_daemon`'s
`docs/robot/install-dev.md` covers applying them.

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

- **No Rockchip defconfig sets `CONFIG_ENV_IS_IN_*`**, so out of the box this
  U-Boot has no persistent environment at all: the Kconfig choice falls to
  `ENV_IS_NOWHERE`, and `saveenv` silently does nothing. RAUC's `uboot` backend
  needs one, plus `fw_printenv`/`fw_setenv` and `/etc/fw_env.config`. On the
  `rauc-ab` branch `recipes-bsp/` now provides all of it — see the RAUC section.
  This U-Boot also has Android/AVB A/B (`CONFIG_SPL_AB`, `CONFIG_AVB_LIBAVB_AB`),
  a different mechanism that must not be enabled alongside RAUC's.

  This bullet used to say RAUC needs `CONFIG_BOOTCOUNT_LIMIT` too. **It does
  not**, and that was never checked: `grep -ri bootcount` over RAUC's own
  documentation returns nothing. The retry counter is `BOOT_<slot>_LEFT`, a
  plain environment variable that the boot script we write decrements itself —
  and RAUC parses it as **hex** (`g_ascii_strtoull(..., 16)` in
  `src/bootloaders/uboot.c`), so that script has to say `0x${BOOT_A_LEFT}`.
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

On `main`, the partition layout comes from meta-rockchip's `generic-gptdisk.wks`:
raw `idblock.img` outside the table, `uboot` at 2 MiB, `boot`, then `rootfs`.
(`trust.img` is absent and skipped — ATF rides inside `uboot.img`'s FIT.) That
layout has a latent bug: `uboot` at 2 MiB spans Rockchip's vendor storage at
3.5 MiB, so U-Boot overwrites part of its own primary copy on first boot. The
`rauc-ab` branch replaces it with Rockchip's documented layout; the table is in
the RAUC section below.

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

#### Bluetooth needs `hciattach`, not `btattach`

The BT side is **HCI over uart1** — `/dev/ttyS1` at 1500000 baud. (Not to be
confused with uart2 / `/dev/ttyS2`, which is the Dynamixel bus.) `aic8800_btlpm`
is only an rfkill power switch: it registers a platform driver and nothing else,
no character device and no HCI transport, so something in userspace has to
attach the controller. `aic8800-bt` ships `aic8800-bt-attach.service` to do it,
enabled by default.

**Use `hciattach`. `btattach` does not work on this chip, and its failure looks
like dead hardware.** `btattach` creates `hci0` and transmits, but never
receives a single byte — TX 4, RX 0, `HCI_Reset` timing out with `-110` —
identically at 1500000 and 115200, with and without flow control. The working
command is:

```sh
hciattach -n -s 1500000 /dev/ttyS1 any 1500000 flow
```

`-s` sets the port speed *before* the HCI line discipline attaches; `btattach`
attaches the discipline first. That is the explanation for the difference and it
fits every measurement, but it was established by testing the two tools, not by
reading their internals — what is certain is which command works.

**rfkill has to be cleared twice.** Once before the attach, for the chip-level
kill that is soft-blocked at boot; and again *after*, because BlueZ registers a
second rfkill node for `hci0` itself when the adapter appears, and that one is
born blocked. Skip the second and `hciconfig hci0 up` fails with `Operation not
possible due to RF-kill (132)`. The first clear cannot cover a node that does
not exist yet.

Working result, from a cold boot:

```
hci0:  BD Address: 50:37:CD:16:5A:3D   HCI Version: 5.4 (0xd)
       UP RUNNING   RX bytes:1460 events:89
bluetoothctl show -> Powered: yes
```

**To debug it, use the vendor's own tool.** `aic8800-bt-test` (dev image) ships
AICSemi's `bt_test`, which bypasses the Linux Bluetooth stack and talks to the
chip directly:

```sh
bt_test -s uart 1500000 /dev/ttyS1     # server, holds the transport
bt_test -c 01 03 0c 00                 # client, sends HCI_Reset
```

A healthy chip answers `04 0E 04 05 03 0C 00` — Command Complete, opcode 0x0C03,
status 0. That one result is what separated "the radio is broken" from "our
tooling cannot talk to a working radio", after a day spent on power rails, reset
GPIOs and device-tree nodes. Its `-s wlan wlan0` mode fails with
`ioctl[TX_PARA] -1`, which rules out HCI-over-SDIO: the transport really is the
UART.

Not verified: that `btd` can advertise through the adapter. `btd` is not built
yet, and the LE checks available from a shell conflict with `bluetoothd` holding
the adapter.

#### Gamepad pairing: an AIC8800 workaround, tagged `remove-with-real-radio`

An Xbox pad will not bond reliably through this radio without help, and the help
lives in `microduck_daemon`'s `scripts/setup-board.sh` — driven by
`provision-board.sh`, the Armbian path, which never runs against a Yocto board.
So the half that is safe everywhere is applied by a recipe here, and the half
that is not is left to whoever meets it.

**Shipped in the image.** `microduck-daemon` writes `/var/lib/robot/weird-ble`,
which is what `provision-board.sh --pause-btd-on-pair` leaves. `robotctl pad
pair` reads only whether that file exists; when it does, it stops `btd` for the
pairing window, power-cycles the adapter, pairs and starts `btd` again. Measured
upstream on `50:37:CD:16:1D:90`, 2026-08-19: with `btd` advertising a **new**
bond dies ~800 µs after Encryption Change with `Remote User Terminated (0x13)`;
with `btd` paused it bonds and holds, 45/45 samples. Every earlier failure blamed
on the aic8800 driver build had `btd` advertising — that was the uncontrolled
variable. An existing bond is unaffected, so a paired pad connects and drives
with everything running. It costs a pairing window and nothing else, so it is on
by default; `sudo rm /var/lib/robot/weird-ble` opts out.

**Not shipped: `Privacy = device`**, the `--weird-ble` half. Some units cannot
bond under BlueZ's default `Privacy = off` at all, even with `btd` paused, and
want it. But on a unit that did *not* need it the pad bonds and then flaps with
`PIN or Key Missing (0x06)` — 46 times in 45 seconds on the same board — never
creating an input device. That failure looks like success at first, which is the
worst shape of it, and roughly half of ten Zero 3W units measured upstream fall
in each camp with nothing to tell them apart. Upstream dropped it as a default
for that reason, and this image follows: a board that will not bond fails
loudly, which is the cheaper failure to diagnose. Apply it by hand:

```sh
sudo sed -i -E 's|^#? *Privacy *=.*|Privacy = device|' /etc/bluetooth/main.conf
```

```sh
sudo reboot
```

That regex is checked against bluez 5.86's own `main.conf`: it rewrites exactly
one line, `#Privacy = off` at 98 under `[General]`, and the `Privacy Mode` prose
at 91 and 95 does not false-match. Swap `device` for `off` to reverse it. Reboot
rather than `systemctl restart bluetooth` — on this board a restart leaves the
kernel holding `hci0` and `bluetoothd` reporting "No default controller
available" until a reboot.

**Clear both halves of the bond after any change to `Privacy`, or it will not
work.** `Privacy` changes the address the stored keys were derived against, so
every existing bond stops matching and flaps with `PIN or Key Missing` until it
is re-made — including the one you are trying to fix. On the robot:

```sh
sudo robotctl pad forget <mac>
```

And on the pad: an Xbox pad keeps **one** host bond, and a half-completed attempt
leaves it holding a key this board no longer has, which fails in exactly the way
a broken board does. Holding it in pairing mode does not reliably release that
slot — pair it to a laptop once and remove it there. Then pair again.

Both halves are workarounds for the AIC8800, which is not the radio that ships.
When the real one lands, the marker, this section and `BtdPaused` in `robotctl`
all go — grep the tag to find them. `microduck_daemon`'s
`docs/robot/pair-a-gamepad.md` has the full account.

> Under A/B updates these opt-outs stop sticking: `/var` and `/etc` both live
> inside the rootfs slot, so a bundle overwrites a hand edit, and an edit made on
> one slot is not on the other. Tracked on #3.

### The policy set ships in the image

`robotd` loads its gait and its one-shots from `/opt/robot/policies/current`,
which is outside the daemon release on purpose — a retrained gait should not
need a daemon release. On a provisioned Armbian board that directory is filled
by the daemon's `scripts/seed-policies.sh`; **an image installs through neither
`hooks/postinstall` nor `scripts/install.sh`**, so the first images had no
policies at all — and a powered robot whose gait file is missing reports
`unhealthy`, which is what the updater's health gate rolls a release back on.

`recipes-microduck/microduck-policies` seeds the set at build time, from the
Hub, pinned by commit and checksummed per file, in the layout the seeder and
`updaterd` both use. That recipe is where this is explained and where the
version lives: **bump it in step with the daemon**, following `POLICY_VERSION`
in the pinned release's own `scripts/seed-policies.sh`. Seeding *below* what the
daemon names is the case that breaks.

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

## A/B OS updates (RAUC)

Being built on the `rauc-ab` branch, in a worktree, because it changes the
partition layout and later the bootloader — and `main` is what a colleague
flashes. Nothing merges until stage 1 is verified on hardware.

Where it stands:

| stage | what | state |
|---|---|---|
| 1 | RAUC userspace, signed verity bundles, install to the inactive slot | verified on hardware 2026-09-18 |
| — | `data` partition for user data, at `/var/lib/robot` | verified on hardware 2026-09-21 |
| 2a | writable U-Boot environment, `fw_setenv` from Linux | verified on hardware 2026-09-25 |
| — | first 16 MiB moved to Rockchip's layout | verified on hardware 2026-09-25 |
| 2b | boot script that picks a slot, `bootloader=uboot` | verified on hardware 2026-09-29: A→B update, boot from B, rollback to A |
| 3 | move the daemon hooks' OS work into the image | not started |

`meta-rauc` is pinned in `kas/microduck.yml`, and `bitbake microduck-bundle`
produces a signed verity `.raucb`.

### Partition layout

`meta-microduck/files/wic/microduck-ab.wks.in`. Every partition has an explicit
offset and fixed size, so wic fails rather than shifting anything: a bundle built
against one layout must never write into another. Everything addresses
partitions by GPT **label**, never by number.

| # | label | offset | size | contents |
|---|---|---|---|---|
| — | *(idblock)* | 32 KiB | 3552 KiB | loader; no GPT entry, the boot ROM reads sector 64 |
| 1 | `v_storage` | 3.5 MiB | 256 KiB | Rockchip vendor storage: MAC, serial. Written by U-Boot only |
| 2 | `uboot_env` | 0x3F8000 | 32 KiB | U-Boot environment: `BOOT_ORDER`, `BOOT_x_LEFT` |
| 3 | `uboot` | 8 MiB | 4 MiB | `uboot.img`, two 2 MiB FIT copies |
| 4 | `boot` | 16 MiB | 32 MiB | kernel + DTB (Android `boot.img`) |
| 5 | `boot_b` | 48 MiB | 32 MiB | reserved, empty |
| 6 | `rootfs_a` | 80 MiB | 2 GiB | slot A, ext4 |
| 7 | `rootfs_b` | 2128 MiB | 2 GiB | slot B, written by RAUC |
| 8 | `data` | 4176 MiB | 8 GiB | ext4, `/var/lib/robot`, survives updates |

Total 12.08 GiB, for the 32 GB card. The unused 20 GB can be claimed later
without a reflash. 4–8 MiB and 12–16 MiB are left empty, as Rockchip's table
reserves them.

**The first 16 MiB are not ours to choose.** Vendor code writes three regions at
fixed byte offsets without reading the partition table: vendor storage at
3.5 MiB, the environment at `0x3F8000`, and U-Boot is loaded from 8 MiB. The
layout copied from JeffyCN's generic wks put `uboot` at 2 MiB, which spans
3.5 MiB. On 2026-09-25 a freshly flashed card was measured differing from its
`.wic` in exactly one 64 KiB window after first boot: U-Boot had written a
generated MAC into the primary copy of itself. The board still booted, inferred
from the backup copy. `scripts/check-wks-layout.py` now asserts the fixed
offsets, and cross-checks the wks, `rauc-env.cfg` and `fw_env.config` against
each other.

**The kernel partition is named `boot`, not `boot_a`, and that is load-bearing.**
Vendor U-Boot finds the kernel by GPT partition *name* — its environment carries
`name=boot,size=32M,bootable` and `bootcmd=boot_android ...;boot_fit;bootrkp;run
distro_bootcmd;`. The first flash of this layout called it `boot_a`, so there was
no `boot` to find, every arm of that bootcmd fell through, and the board never
loaded a kernel: no wifi, no BLE, nothing. The card itself was written correctly
(RKNS at sector 64, `uboot.img` at 2 MiB, a valid `ANDROID!` image in the slot) —
only the name was wrong. So rootfs is A/B today and the kernel is not; `boot_b`
keeps its offset, and renaming the pair is stage 2's job, at the point where a
`boot.scr` can actually choose between them.

`system.conf` ships `bootloader=noop` until stage 2b. That is not a placeholder
to forget about: nothing on this board picks a slot at boot yet. `root=` is
compiled into the DTB, which is packed inside an Android `boot.img` on a raw
partition, with no extlinux and no `boot.img` header cmdline. So RAUC can write
the inactive slot and honestly reports that it cannot make the board boot it.
`noop` becomes `uboot` once the boot script exists.

**Signing keys are not in this repo.** Generate once, into the build directory
(bind-mounted by kas-container, and already in `.gitignore`):

```sh
. kas/lotus.env                        # or coach.env -- this sets KAS_BUILD_DIR
mkdir -p "$KAS_BUILD_DIR/rauc-keys" && cd "$KAS_BUILD_DIR/rauc-keys"

# The script comes from the meta-rauc layer. kas clones it next to
# meta-microduck on the first build; before that, fetch just the script:
curl -sfLO https://raw.githubusercontent.com/rauc/meta-rauc/wrynose/scripts/openssl-ca.sh
chmod +x openssl-ca.sh && ./openssl-ca.sh                # edit ORG=/CA= at the top first

cp openssl-ca/dev/development-1.cert.pem .
cp openssl-ca/dev/private/development-1.key.pem .
chmod 600 development-1.key.pem
```

Then copy the **public** CA certificate into the layer, so the image trusts
bundles signed by that key:

```sh
cp openssl-ca/dev/ca.cert.pem <repo>/meta-microduck/recipes-core/rauc/files/
```

Until that certificate exists, `rauc-conf` falls back to meta-rauc's example and
says so (`Please overwrite example ca.cert.pem ...`), and the image cannot verify
anything. In CI the private key becomes an org-scoped GitHub secret written to
`$KAS_BUILD_DIR/rauc-keys/` before the build.

**Verified on hardware 2026-09-18**, on the 32 GB card, dev image, RAUC 1.15.2:

```
rauc status
  Compatible:  microduck-zero3
  Booted from: rootfs.0 (/dev/mmcblk1p5)     # p6 since v_storage was added
  [rootfs.0] /dev/disk/by-partlabel/rootfs_a  bootname A  booted
  [rootfs.1] /dev/disk/by-partlabel/rootfs_b  bootname B  inactive

rauc info microduck-bundle-*.raucb
  Verified inline signature by 'O = Pollen Robotics, CN = Pollen Robotics Development-1'
  Compatible: 'microduck-zero3'   Bundle Format: verity
  1 Image: rootfs -> microduck-image-dev-microduck-zero3.rootfs.ext4, 720.8 MB

rauc install microduck-bundle-*.raucb
  Copying image to rootfs.1 done.
  noop bootloader: ignore setting slot rootfs.1 as primary
  Installing succeeded          (exit 0)
```

So the CA committed here validates a bundle signed by a key that only ever
existed on the build host; 720 MB reached `rootfs_b`, which held zeroes before
and carries an ext4 superblock after; and RAUC stopped exactly where this board's
capability ends, saying so rather than pretending.

Two things that look wrong and are not. Both slots report `boot status: bad` --
`noop` cannot answer, and RAUC's default for unknown is bad; they become real
answers under `bootloader=uboot`. And that day `rauc info` warned `No data
directory or status file set`. It no longer does: `system.conf` now sets
`data-directory=/var/lib/robot/rauc`, on the `data` partition, so RAUC's slot
bookkeeping survives a slot being rewritten.

Reproduce with:

```sh
bitbake microduck-bundle                 # -> tmp/deploy/images/.../microduck-bundle-*.raucb
rauc info  microduck-bundle-*.raucb      # signature verifies, compatible matches
rauc install microduck-bundle-*.raucb    # writes rootfs_b, logs the noop refusal to switch
```

**User data has its own partition.** `data` (ext4, 8 GiB, formatted at image
build) is mounted at `/var/lib/robot` by `recipes-core/microduck-data/`, which is
where the daemon already keeps everything a person adds — the policy library,
the sound bank, `configd`'s state — so no daemon change was needed. It survives
daemon updates and, once stage 2 exists, RAUC slot switches. It does **not**
survive a reflash of the whole `.wic`. If the partition is missing or blank, the
mount is *skipped* rather than failed (`ConditionPathExists=/dev/disk/by-label/data`)
and the daemons start against the rootfs directories: a failed mount would
otherwise cascade through their `StateDirectory=` and stop `configd` and
`updaterd`, which was observed on a card flashed from an incomplete download.

Both halves are verified on hardware (2026-09-18), on that same card: with the
partition blank, the mount was skipped and every daemon started; after
formatting it with the wks's exact `mkfs.ext4` options, `by-label/data`
appeared, the mount came up (`/dev/mmcblk1p7 ext4 7.7G`), and `StateDirectory=`
created `config/` and `updater/` on the empty filesystem with zero failed units.

**Build the bundle, not the image, and it builds the dev image too.**
`RAUC_SLOT_rootfs` is `microduck-image-dev` on this branch, so `HFJOB_TARGET=microduck-bundle`
produces both a flashable card and the bundle that matches it. It was briefly
`microduck-image`, which built a prod card with no password-less root -- a board
that came up, answered ping, and could not be logged into.

**Stage 2a (verified on hardware 2026-09-25): a U-Boot environment Linux can
write.** `recipes-bsp/u-boot/` enables `CONFIG_ENV_IS_IN_BLK_DEV` and
`CONFIG_CMD_SETEXPR`; `recipes-bsp/u-boot-env/` ships `/etc/fw_env.config`,
`fw_printenv`/`fw_setenv` and U-Boot's default environment. The proof was that
U-Boot reads what Linux writes, not just that Linux reads back its own bytes: a
`bootargs` token set with `fw_setenv` appeared in `/proc/cmdline` after a reboot.

The default environment file, `/etc/u-boot-initial-env`, is load-bearing. A
freshly flashed card's environment region is zeros, so its CRC fails. Without
that file, the first `fw_setenv` writes an environment holding only the one
variable, with a valid CRC. U-Boot then loads it *instead of* its defaults, loses
`bootcmd`, and stops at a prompt you cannot see without a serial console. RAUC's
first write would do exactly that.

**Stage 2b (verified on hardware 2026-09-29): boot-time slot selection.**
Build `6ab69578`. The full cycle ran on the board: booted A with
`root=PARTLABEL=rootfs_a rauc.slot=A` and `rauc-mark-good` marking it good;
`rauc install` wrote B and U-Boot made it primary (`BOOT_ORDER=B A`); the board
booted B (`/dev/mmcblk1p7`); a file written on A was still on the data partition
from B; `rauc status mark-bad booted` then sent it back to A on the next boot,
unattended. Three changes:

- `recipes-bsp/u-boot/files/0001-evb_rk3568-*.patch` prepends a slot selection
  to U-Boot's `bootcmd`. It walks `BOOT_ORDER`, takes the first slot with
  attempts left, decrements and saves the counter, and sets
  `root=PARTLABEL=rootfs_a|b rauc.slot=A|B`. It has to be a header patch:
  `evb_rk3568.h` `#undef`s `CONFIG_BOOTCOMMAND`, so a Kconfig value is ignored.
- The device tree's chosen node loses its `root=`. U-Boot merges the DTB's
  bootargs in *after* the script's, and a matching key replaces rather than
  appends, so a `root=` there would pin every boot to one slot.
- `system.conf` switches to `bootloader=uboot`. `rauc-mark-good.service`, which
  has been installed all along but only runs when `rauc.slot=` is on the kernel
  command line, now restores the counter on every boot that completes.

The board has no serial console, so a syntax error in `bootcmd` would leave it
at an invisible prompt. The script is therefore tested first, in a U-Boot
sandbox built from mainline v2017.09 **with the vendor's `lib/strto.c` copied
in**. Without that file the sandbox lies: the first flash looped forever in
U-Boot because `test 0x3 -gt 0` is false on the vendor's number parser and true
on mainline's, and the plain sandbox passed it. The shell, `test`, `setexpr` and
`setenv` are byte-identical between the two trees; the number parser is the one
difference on the script's path. `scripts/test-uboot-bootcmd.sh` compiles the
macro out of the patch and runs nine cases: fresh environment, B first after an
install, fallback when B is exhausted, a slot RAUC marked bad, hex counters, an
empty counter, an unknown slot name, and both slots exhausted. A deliberately
broken copy fails with a `syntax error`, so the test does catch it.

The fallback, if this U-Boot resists on hardware, is
`radxa/u-boot next-dev-v2024.10`, which boots via extlinux.

**Wifi credentials live on the data partition.** The first A→B update booted B
perfectly and left it unreachable: NetworkManager kept profiles in
`/etc/NetworkManager/system-connections`, inside the slot, and B was written from
a clean image. It looked like a boot failure, and U-Boot's counters cannot tell a
kernel that booted with no network from one that did not boot. `microduck-data`
now points NetworkManager at `/var/lib/robot/network`
(`/usr/lib/NetworkManager/conf.d/90-microduck-keyfile.conf`), so a profile
survives every update however it was created. `utils/seed-wifi.sh` writes there
on images that have it. Not yet built.

**Stage 3 (not started): reconcile with `updaterd`.** Both stay. RAUC owns the
kernel, DTB, rootfs and everything the daemon's install hooks currently drop
into `/usr/local/lib` and `/usr/lib/sysusers.d`; `updaterd` shrinks to the daemon
binaries under `/opt/robot/daemon`. A daemon fix should not cost a reboot
(~20 s symlink swap and a 13 MB payload today, versus a reboot and ~200 MB), and
a developer should not wait on a 50-minute image build to try one.
