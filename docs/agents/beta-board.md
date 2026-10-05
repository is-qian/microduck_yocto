# The beta board (`microduck-beta`) — what an agent needs to know

The first spin of the custom microduck PCB: a Seeed-designed RK3566 board
(derived from their Home Assistant Green), 1 GB DDR3, 64 GB eMMC, no SD slot.
Machine `microduck-beta`, device tree
`meta-microduck/recipes-kernel/linux/files/rk3566-microduck-beta.dts`.

**Nothing here has run on a board yet.** The DTS compiles against radxa's tree at
the pinned `SRCREV`, with the same dtc warnings as the Zero 3W's and no new
ones. That shows the labels and pin groups exist. It does not show they match
the board.

**The schematic is older than the boards.** The DTS was transcribed from "MicroDuck
Main Board V0.1", dated 2026-09-09, and the boards in hand are a later
revision whose schematic we do not have. Every block in the DTS cites its sheet
so a newer schematic can be diffed against it. Until then, treat each pin as a
claim to check, in the order below.

## How it differs from the Zero 3W

| | Zero 3W (`microduck-zero3`) | beta (`microduck-beta`) |
|---|---|---|
| device tree | radxa's, plus our patches | ours, a whole file in this layer |
| storage | SD card | eMMC, nothing else |
| PMIC | RK817 | RK809 + RK860-1 (TCS4525 footprint-compatible) |
| motor bus | UART2, fought over with the console | UART3; UART2 is only the console, on TP83/TP84 |
| `/dev/ttyS2` | UART2 | UART3, by a `serial2`/`serial3` alias swap in the DTS |
| radio | AIC8800DS2 | WL6201S module, chip unknown |
| audio | none on the board | RK809 codec → NS4150B amp → speaker; PDM mic on the face board |
| camera | IMX219 | GC2613, **no driver in the kernel**, not described yet |
| face board | — | IMU on I2C3, ToF on SPI3 (1.8 V), PDM mic |
| other | HDMI | NFC (ST25R100, SPI1), fan header, Qwiic (I2C4), board-ID EEPROM |

`/dev/ttyS2` is the motor bus on both boards on purpose. `robotd.toml`, the
daemon's scripts and its docs all name it, and the alias costs one DTS line. The
alternative was a per-machine `robotd.toml`, which would mean a per-machine build
of the whole Rust daemon recipe just to change one string.

## Telling the boards apart

The board is chosen when the image is built (`MACHINE`), so there is nothing to
detect at install time. The two places it is visible afterwards:

- **`/proc/device-tree/compatible`** starts with `pollen,microduck-beta` (the
  Zero 3W says `radxa,zero3w-aic8800ds2`). This is what software on the robot
  should switch on.
- **RAUC** `compatible=` is `${MACHINE}`, so a Zero 3W bundle refuses to install
  on a beta board and vice versa.

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
2. **Get into maskrom.**
   - A blank eMMC drops into maskrom by itself.
   - Otherwise, short **TP64 (eMMC_D0) to TP4 (GND)** while powering on
     (sheet 12).
   - The **Recovery button** (SARADC VIN0) may get there without opening
     anything. The DTS describes it as an `adc-keys` entry the way Rockchip's evb
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
   `db` succeeding is the first hardware result worth recording. It runs the
   same rkbin DRAM init as the image's idblock, on DDR3, which nothing has
   tried yet. The `.wic` is sparse at 12.1 GiB, and `wl` writes every byte of
   it, zeros included, so expect minutes rather than seconds over USB 2.

There is no `seed-wifi.sh` for eMMC: the board is not a card you can mount.
Wifi comes over Bluetooth (`duckctl wifi connect`) once the radio works, and
the serial console works before it does.

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
6. **Radio.** `dmesg | grep -i -e aic -e brcm -e mmc2`. The image carries the
   AIC8800 driver as a guess (see `microduck-beta.conf`). An SDIO card that
   enumerates with no driver binding gives the chip's vendor/device ID in
   `/sys/bus/sdio/devices/*/`, which answers the question.
7. **Motors.** `ls -l /dev/ttyS2` should resolve to `fe670000.serial` (UART3) in
   `dmesg`, then `robotctl health`.
8. **Face board.** `i2cdetect -y 3`: the BMI088 answers at 0x19 and 0x68 if
   it is the same IMU as the prototype's HAT. `ls /dev/spidev3.0` for the ToF.
9. **Audio.** `aplay -l` shows `rockchip-rk809`. The amp sits on the codec's
   headphone output, so select it once with
   `amixer cset name='Playback Path' HP`. `arecord -D hw:<pdm card>` for the
   mic.

## Open items, each blocking something specific

- **The wifi chip.** Decides `RK_WIFIBT_RRECOMMENDS` and whether the AIC8800
  pairing workaround (`/var/lib/robot/weird-ble`, written by the daemon recipe on
  every machine) applies here at all.
- **A GC2613 driver.** radxa's `drivers/media/i2c` stops at gc2385. Until one
  exists the camera is deliberately absent from the DTS. A sensor node nothing
  binds would leave rkisp waiting and produce no `/dev/video*` at all.
- **The ToF part.** It is on SPI, so it is not the prototype's VL53L5CX, which is
  I²C only. `tofd` needs an SPI transport, and `/dev/spidev*` needs a udev rule
  giving `tofd` access, the same way `60-robot-i2c.rules` does for I²C.
- **The PDM mic's channel and the codec path** are configured from the
  drivers' source, not from a board. `rockchip,path-map` and `hp-ctl-gpios` are
  the two lines to suspect.
- **A newer schematic**, to diff against every block above.
