# The beta board (`microduck-beta`) — what an agent needs to know

The first spin of the custom microduck PCB: a Seeed-designed RK3566 board
(derived from their Home Assistant Green), 1 GB DDR3, 64 GB eMMC, no SD slot.
Machine `microduck-beta`, device tree
`meta-microduck/recipes-kernel/linux/files/rk3566-microduck-beta.dts`.

**Our image has not run on a board yet.** The DTS compiles against radxa's tree
at the pinned `SRCREV`, with the same dtc warnings as the Zero 3W's and no new
ones.

**The schematic is older than the boards; Seeed's own device tree is not.** The
DTS was transcribed from "MicroDuck Main Board V0.1" (2026-09-09), then
corrected against the DTB that Seeed's factory image boots on a real board,
read from `/sys/firmware/fdt` on 2026-10-05. Where they disagreed, Seeed's won,
because their image runs on the board: its radio associates and Bluetooth comes
up. Corrections that came from it:

| | V0.1 schematic | Seeed DTB (real board) |
|---|---|---|
| VCCIO4 (radio bank) | 3.3 V | **1.8 V** |
| RK860 CPU regulator | — | I²C address **0x41** |
| radio control lines | GPIO0_A0/A6/B7/C0/C1 | **GPIO2_B1/B2/B7/C0/C1** |
| Qwiic (I2C4_M1) | enabled | **disabled**: its pins are the BT wake lines |
| face board | not drawn | **PCA9535 at 0x20 on I2C3** (IRQ GPIO4_C7), 12 LEDs on it |
| ToF SPI clock | — | 8 MHz |
| NFC chip select | controller CS | GPIO3_A1 |
| amp enable | — | GPIO1_B0 as `spk-ctl-gpios` |

Each corrected block in the DTS says "Seeed DTB". The rest is still V0.1 and
still a claim to check, in the order below.

## How it differs from the Zero 3W

| | Zero 3W (`microduck-zero3`) | beta (`microduck-beta`) |
|---|---|---|
| device tree | radxa's, plus our patches | ours, a whole file in this layer |
| storage | SD card | eMMC, nothing else |
| PMIC | RK817 | RK809 + RK860-1 (TCS4525 footprint-compatible) |
| motor bus | UART2, fought over with the console | UART3; UART2 is only the console, on TP83/TP84 |
| `/dev/ttyS2` | UART2 | UART3, by a `serial2`/`serial3` alias swap in the DTS |
| radio | AIC8800DS2 | WL6201S module = **WuQi WQ9201S** (`recipes-kernel/wq9201s`) |
| way in without radio | none | USB-C gadget: serial console + network, robot at 192.168.42.1 |
| audio | none on the board | RK809 codec → NS4150B amp → speaker; PDM mic on the face board |
| audio routing | `plughw:aic3104` | ALSA `default` → RK809 + face mic, SPK path set at boot |
| camera | IMX219 | GC2613 or GC2093, **no GC2613 driver in our kernel**, not described yet |
| face board | — | PCA9535 + 12 LEDs and maybe the IMU on I2C3, ToF on SPI3 (1.8 V), stereo PDM mic |
| fan | — | its own thermal zone: off below 52 °C, steps up from 60 °C |
| other | HDMI | NFC (ST25R100, SPI1), fan header, Qwiic (I2C4), board-ID EEPROM |

`/dev/ttyS2` is the motor bus on both boards on purpose. `robotd.toml`, the
daemon's scripts and its docs all name it, and the alias costs one DTS line. The
alternative was a per-machine `robotd.toml`, which would mean a per-machine build
of the whole Rust daemon recipe just to change one string.

## What the boards ship with

Seeed flashes an Armbian build of their own onto the eMMC: `BOARD=microduck`,
Armbian 26.11 trunk on bookworm, vendor kernel 6.1.172 built 2026-09-29, DT model
"Microduck Main Board V0.1", compatible `seeed,microduck`. Plugging in USB-C
with the board powered gives you a way in without opening anything. It is a USB
gadget (`2207:0019`) with:

- **a serial console**, `/dev/ttyACM0` on the host, auto-logged-in as root. On
  first use this drops into Armbian's first-login wizard.
- **an RNDIS network link**: the board is 192.168.42.1 and serves DHCP.
  `nmcli device connect <enx…>` on the host brings it up.

Things on that image worth having before it is overwritten, because they are
what our image is missing:

- `/usr/src/wq9201s-wifi-bt-dkms-2.2.3.94`: the radio driver source (Dual
  BSD/GPL according to `modinfo`).
- `/lib/firmware/wq9201_*.bin` and `wq_wlan_settings.ini`: its firmware.
- `/usr/bin/wq9201s-bt-attach` and `wq9201s-bt.service`: the Bluetooth attach.
- `/usr/bin/usbdevice` and `usbdevice.service`: the USB gadget above, which our
  image should copy for bring-up, since it has no radio yet.
- `/sys/firmware/fdt`: their device tree.

The bootloader line in its cmdline (`ddr-v1.26`, `spl-v1.14`) is the same rkbin
DRAM init our U-Boot uses, so DDR3 init is not a risk.

Its `/etc/armbian-release` also carries an internal GitLab CI token in
`BUILD_REPOSITORY_URL`, which Seeed should know about.

## Telling the boards apart

The board is chosen when the image is built (`MACHINE`), so there is nothing to
detect at install time. The two places it is visible afterwards:

- **`/proc/device-tree/compatible`** starts with `pollen,microduck-beta` (the
  Zero 3W says `radxa,zero3w-aic8800ds2`, Seeed's factory image
  `seeed,microduck`). This is what software on the robot should switch on.
- **RAUC** `compatible=` is `${MACHINE}`, so a Zero 3W bundle refuses to install
  on a beta board and vice versa.

The daemon has its own word for the board: `[board] version` in
`/etc/robot/robotd.toml` (`"zero3"` or `"beta"`, microduck_daemon#357), which the
updater turns into the hardware revision a release is checked against (zero3 1,
beta 2). It is declared, not detected: `microduck-board` writes the machine's
`MICRODUCK_BOARD` into the file when the image is assembled. Only once the image's
daemon knows the key, though. 0.15.0's robotd refuses unknown tables, so with it
the package logs that it declared nothing, and the board answers as `zero3` until
the daemon pin moves past #357. `robotctl health` on a #357 daemon warns when the
declaration and `/proc/device-tree/compatible` disagree.

The board **revision** within the beta family comes from hardware. The SARADC
VIN1 divider (sheet 14) reads raw 1023 on V0.1 ("Version_1"), and Seeed's table
defines seven levels:

```sh
cat /sys/bus/iio/devices/iio:device0/in_voltage1_raw
```

There is also a 24C32 EEPROM at `1-0050`. It is read-only from software: WP is
pulled high and the GPIO that could drive it is not fitted. Whether Seeed writes
anything to it is not known.

## Build

```sh
kas build kas/microduck.yml:kas/beta.yml                 # local / coach
HFJOB_MACHINE=microduck-beta scripts/build-hfjob.sh      # HF Jobs
```

or `machine: microduck-beta` in the `build-image` workflow dispatch. A push to
`main` still builds the Zero 3W only. Artifacts land in
`tmp/deploy/images/microduck-beta/`.

## Flash the eMMC

The USB-C port is the RK3566's OTG port, which is what the boot ROM's maskrom
mode talks to. Flashing goes through it, with `rkdeveloptool`.

1. **Power from the battery or a bench supply.** USB-C only reaches the 5 V
   rail. VCC3V3_SYS, which feeds the PMIC, comes from VBAT alone (sheets 5 and
   24), so USB by itself does not start the SoC. That is a reading of V0.1;
   check it on the bench.
2. **Get into maskrom or loader mode.**
   - A new board is NOT blank: it boots Seeed's image (above), so it will not
     drop into maskrom by itself.
   - From Seeed's image, the easiest way is `reboot loader` on its console
     (`/dev/ttyACM0`). Rockchip's vendor U-Boot reads that reboot reason and
     comes up in rockusb **loader** mode. Not tried yet on this board; if it
     just reboots normally, use one of the next two.
   - Otherwise, short **TP64 (eMMC_D0) to TP4 (GND)** while powering on
     (sheet 12).
   - The **button next to the USB-C** is the Recovery button: hold it while
     powering on and the board comes up in **loader** mode (`2207:350a`,
     `rkdeveloptool ld` says `Loader`). Verified 2026-10-05 through Seeed's
     U-Boot. Ours is the same Rockchip vendor U-Boot and the DTS describes the
     key as Seeed's does, but that half is not yet verified. The DTS describes it as an `adc-keys` entry the way Rockchip's evb
     does, which is what Rockchip's U-Boot reads to enter loader mode. Not
     verified on this board.

   `lsusb` shows `2207:350a` in either mode.
3. **Write it.** `loader.bin` is deployed next to the `.wic`:
   ```sh
   sudo rkdeveloptool db loader.bin       # runs DRAM init in SRAM
   sudo rkdeveloptool rfi                 # flash info: proves DDR3 + eMMC before writing
   sudo rkdeveloptool wl 0 microduck-image-dev-microduck-beta.rootfs.wic
   sudo rkdeveloptool rd
   ```
   In loader mode, skip `db`: the loader is already running.

   Debian/Ubuntu's `rkdeveloptool` (1.32, pine64 fork) takes `rl`'s length in
   **bytes**, not sectors: `rl 0 33554432 first32M.img` reads the first 32 MiB.
   That is the cheap backup of a board's bootloader area before reflashing.

   `db` succeeding is the first hardware result worth recording. It runs the
   same rkbin DRAM init as the image's idblock, on DDR3, which nothing has
   tried yet. The `.wic` is sparse at 12.1 GiB, and `wl` writes every byte of
   it, zeros included, so expect minutes rather than seconds over USB 2.

There is no `seed-wifi.sh` for eMMC: the board is not a card you can mount.
Until the radio has a recipe, the way in is the USB-C gadget
(`microduck-usb-gadget`), the same cable you flash with:

- **console:** `/dev/ttyACM*` on the host (getty on the robot's `ttyGS0`);
- **network:** an NCM link, robot at 192.168.42.1, your machine gets a
  192.168.42.x lease with no gateway, so it keeps its own internet route.
  `ssh root@192.168.42.1` on the dev image.

## First boot, in order

Each step assumes the ones before it passed.

1. **Serial console**, TP83 (TX) / TP84 (RX), 3.3 V, 1500000 8N1. U-Boot prints
   here (`CONFIG_DEBUG_UART_BASE` is UART2), and so does the kernel (`earlycon`
   and `console=ttyFIQ0` are in the DTS bootargs, which is safe here, unlike on
   the Zero 3W). Look for the DDR line, U-Boot finding `mmc0`, and the kernel
   starting.
2. **IO-domain voltages: the block that can damage the SoC.** `&pmu_io_domains`
   tells the SoC what voltage each pad bank is at, and claiming 1.8 V for a
   bank fed 3.3 V overdrives it. V0.1 has VCCIO4 at 3.3 V and VCCIO6 at 1.8 V,
   the opposite of the Zero 3W. Confirm both against a newer schematic or
   by measurement before leaving a kernel running. The decoupling caps are
   C204 (VCCIO4, sheet 14) and C191 (VCCIO6, sheet 15).
3. **PMIC rails**, from U-Boot or Linux, at the test points on sheet 6: TP43
   VCC_3V3, TP58 VCC_1V8, TP50 VCC3V3_PMU, TP13 VCCA_1V8, TP51 VCCIO_ACODEC
   (expect 3.3 V; the DTS sets it), TP61/TP62 VCC3V3_SYS/VCC5V0_SYS.
4. **CPU rail.** `dmesg | grep -i -e fan53555 -e vdd_cpu`. If the RK860 node at
   0x40 does not probe, the board may carry the TCS4525. The swap is described
   at the node.
5. **Identity.** `cat /proc/device-tree/compatible`, the VIN1 reading above,
   and `xxd /sys/bus/i2c/devices/1-0050/eeprom | head`.
6. **Radio.** `lsmod | grep wq_wlan`, then `ip link show wlan0` (the driver has
   loaded its firmware when wlan0 exists), then `systemctl status wq9201s-bt`
   and `hciconfig hci0`, which should show a non-zero address. The SDIO card is
   `mmc2:0001:*`, vendor 0x0297, whatever the driver does. If the radio is down,
   the USB-C gadget is the way in: `systemctl status microduck-usb-gadget
   microduck-usb-dhcp` if it does not show up on the host.
7. **Motors.** `ls -l /dev/ttyS2` should resolve to `fe670000.serial` (UART3) in
   `dmesg`, then `robotctl health`.
8. **Face board.** `ls /sys/class/leds | grep face` for the expander's LEDs.
   `i2cdetect -y 3` shows the PCA9535 at 0x20 as `UU`, and the BMI088 at 0x19
   and 0x68 if it is the same IMU as the prototype's HAT. `ls /dev/spidev3.0`
   for the ToF.
9. **Audio.** `aplay -l` shows `rockchip-rk809` and `face-mic` (ALSA ids
   `rockchiprk809`, `facemic`, which `/etc/alsa/conf.d/60-microduck-beta.conf`
   routes `default` to). `microduck-audio-path.service` selects the codec's SPK
   path at boot. `speaker-test -c1 -t sine -l1` should be audible. robotd plays on
   `default` here only from the release that carries the daemon's
   `audio-default-fallback` change; before that it is silent on this board.
10. **Fan.** `cat /sys/class/thermal/thermal_zone*/type` lists `fan-thermal`,
    whose `policy` must read `step_wise` (set by `microduck-fan`'s udev rule).
    `cur_state` in its `cooling_device` should be 0 at idle.

## Which daemon the image carries

`microduck-daemon_0.15.0.bb`: the image seeds release 0.15.0, the newest that
builds with wrynose's Rust (1.94.1). 0.15.1 needs 1.99. A robot with network
converges to the newest release on its own, because `updaterd` installs CI's
prebuilt binaries rather than this recipe's build. The daemon branch
`msrv-yocto-ceiling` brings the floor back to 1.89 and documents the ceiling,
so the first release after it can be pinned here directly.

## Open items, each blocking something specific

- **The WuQi radio is unverified.** `recipes-kernel/wq9201s` has never been
  built or booted. It packages WuQi's driver 2.2.3.94 and the firmware, both
  taken from Seeed's factory image (`/usr/src/wq9201s-wifi-bt-dkms-2.2.3.94`,
  `/lib/firmware/wq9201_*`). Its `-Werror` is stripped because wrynose's GCC 15
  is newer than the GCC 12 it was built with. Bluetooth attaches as
  `btattach -B /dev/ttyS1 -N -P h4 -S 1500000` once wlan0 exists; never run
  `hciattach 3wire` in the same boot. **The firmware's redistribution terms are
  unknown** (`LICENSE = "CLOSED"`): settle them with Seeed or WuQi before an
  image leaves the team. The AIC8800 pairing workaround
  (`/var/lib/robot/weird-ble`, written by the daemon recipe on every machine)
  is to be re-measured on this radio.
- **Daemon releases** carrying `msrv-yocto-ceiling` (so the image can pin the
  newest) and `audio-default-fallback` (so robotd speaks on this board).
- **A GC2613 driver.** radxa's `drivers/media/i2c` stops at gc2385. Seeed's
  kernel has one, and describes gc2613@0x31 and gc2093@0x37 on I2C2 (MCLK from
  CIF_CLKOUT, 2 lanes into csi2_dphy0). Until our kernel has it the camera is
  deliberately absent from the DTS: a sensor node nothing binds would leave
  rkisp waiting and produce no `/dev/video*` at all.
- **Fan trips** (60/68/75 °C) are starting points, not measured values.
- **The ToF part.** It is on SPI, so it is not the prototype's VL53L5CX, which is
  I²C only. `tofd` needs an SPI transport, and `/dev/spidev*` needs a udev rule
  giving `tofd` access, the same way `60-robot-i2c.rules` does for I²C.
- **The PDM mic's channel and the codec path** are configured from the
  drivers' source, not from a board. `rockchip,path-map` and `hp-ctl-gpios` are
  the two lines to suspect.
- **A newer schematic**, to diff against every block above.
