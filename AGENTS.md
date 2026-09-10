# microduck_os — working notes for agents

Yocto OS image for the microduck robot (RK3566, Rockchip vendor BSP 6.1).
See `README.md` for what this is and how to build it; this file is about *how to
work in here*.

Workspace-level conventions live in `../../AGENTS.md`.

## The one rule: verify, don't assume

Almost every bug in a Yocto layer is a wrong assumption about an upstream layer —
a branch that doesn't exist, a recipe named differently, a variable set somewhere
you didn't look. This repo already has a scar for each:

- `poky` has **no `wrynose` branch** (it stops at `walnascar`), which is why we
  use `openembedded-core` + `meta-yocto` + `bitbake` separately.
- meta-rockchip sets neither `RK_UBOOT_SPL` nor `RK_UBOOT_CFG` in any machine
  conf, so its only tested U-Boot path is the Rockchip Miniloader with generic
  `rk3568_defconfig` — not what radxa's defconfig expects.
- `u-boot-rockchip`'s `SRC_URI` has a **second** git entry (`rkbin`, with
  `destsuffix=rkbin`) that `make.sh` requires. Replacing `SRC_URI` wholesale
  silently drops it and breaks the build.
- `linux-rockchip.inc` assembles its defconfig with
  `cat rockchip_linux_defconfig ${RK_SOC_FAMILY}_linux.config > ... || true`.
  radxa's tree has no `rk3568_linux.config`, and the `|| true` hides that, so
  **every SoC-specific config override is silently missing** and you get radxa's
  family-wide defconfig alone. Never trust the effective kernel config from the
  defconfig name — read `${B}/.config` after `do_configure`.
- **Do not set `S = "${WORKDIR}/git"` or `S = "${UNPACKDIR}/git"`** in a git-based
  recipe. wrynose fails `do_unpack` outright if you do: the fetcher now unpacks to
  `${UNPACKDIR}/${BP}`, which bitbake.conf's default `S` already points at. Omit
  the assignment. (This will bite the `microduck-daemon` recipe too.)
- `do_patch` is a **Python** task (patch.bbclass). `do_patch:append() { ... }`
  with a shell body makes bitbake parse shell as Python and fail with a
  `TabError`. Use `do_patch[postfuncs] += "your_shell_func"`.
- meta-rockchip's `wrynose` branch does not always match oe-core's `wrynose` tip.
  It has been wrong twice: the `xserver-xorg` fork tag, and the above. Treat it as
  the least trustworthy layer here.

So before adding a recipe or a bbappend that leans on meta-rockchip or oe-core,
go read the thing you are leaning on. `git ls-remote`, the GitHub contents API
and `https://git.openembedded.org/<repo>/plain/<path>?h=wrynose` are all cheap
and answer these in seconds.

**When something cannot be verified, say so in the file.** Do not write a
confident comment over a guess.

## Layer pins

`kas/microduck.yml` pins every upstream layer to an explicit `commit:` next to
its `branch:`. Never drop the commit and float on a branch — the whole point of
choosing Yocto over `reachy_mini_os`'s pi-gen build is that a build is
reproducible. `reachy_mini_os` pins its kernel with apt `Pin-Priority: -1`
because a point release broke BLE; we should not recreate that failure mode.

To bump a pin: change `commit:`, build, and note what moved in the commit
message.

## Where things go

```
kas/microduck.yml         layer pins + build config. NO absolute paths.
kas/<host>.env            per-host paths, as env vars (see below).
meta-microduck/conf/machine/microduck-zero3.conf
                          the Radxa Zero 3W stand-in board.
meta-microduck/conf/machine/include/microduck-common.inc
                          anything shared with the future custom PCB.
```

Paths are **environment variables in `kas/<host>.env`, not `local_conf_header`
entries.** kas-container bind-mounts `KAS_BUILD_DIR`, `DL_DIR` and `SSTATE_DIR`;
a path written into `local.conf` names a host directory the container cannot see,
so the build fails or silently writes somewhere useless. `TMPDIR` needs no
setting — it defaults to `$KAS_BUILD_DIR/tmp`, which is already mounted.

The machine conf is deliberately thin over meta-rockchip's `rk356x.inc`. When
the custom PCB arrives it should be a second machine conf plus its own DTS,
reusing `microduck-common.inc` unchanged — so put board-specific things in the
machine conf and shared things in the include, not the other way round.

Add a new `kas/<host>.env` rather than editing an existing one. Paths are the
only thing that may differ between hosts; anything else belongs in
`microduck.yml`.

## Hardware constraints that shape recipes

- **UART2 is contested.** `MICRODUCK_MOTOR_UART` in `microduck-common.inc`
  switches UART2 between the serial console and `robotd`'s Dynamixel bus. It
  defaults to `1` (motors) since 2026-09-10 — through bring-up it was `0`, but
  no UART adapter was ever attached to read that console and `robotd` needs
  `/dev/ttyS2`. Note it only silences *Linux* — U-Boot's
  `CONFIG_DEBUG_UART_BASE` is UART2 too, so U-Boot still prints onto the motor
  wires at power-on.
- **No RTC.** `updaterd`'s preflight check exists because a 1970 clock fails TLS
  with an opaque error. Anything that fetches over HTTPS depends on timesyncd
  working. Verify with `timedatectl`; do not assume.
- **The image must stay lean.** Dev headers belong in the SDK
  (`-c populate_sdk`), not on the robot. `reachy_mini_os` ships
  `build-essential`, `gdb` and four `gstreamer*-dev` packages on the device
  purely because PyGObject compiles on-target; do not repeat that.

## Working with microduck_daemon

The daemon is a **separate repo** (`../../microduck_daemon/`) and cross-compiles
on its own via `cargo board`. Do not add a Yocto recipe step to someone's
edit-test loop:

1. Pure-Rust change → `cargo board`, rsync to the board. No Yocto involved.
2. Links native libs (`mediad` against GStreamer/MPP) → Yocto SDK.
3. Iterating on a recipe already in the image → `devtool modify` /
   `devtool deploy-target`.

Two recipe constraints, both load-bearing:

- `rustls` resolves to the **`aws-lc-rs`** provider, whose build script invokes
  CMake → the recipe needs `cmake-native`. There is no OpenSSL in the tree.
- `serialport` must keep `default-features = false`. Its `libudev` feature needs
  a pkg-config sysroot the cross-build does not have and breaks the aarch64 build
  outright. `grep -c libudev Cargo.lock` should stay `0`.

## Commits

Per the workspace convention, no `Co-Authored-By:`. Use a single
`Assisted-by: Claude:<model-id>` trailer.

There is no linter here. The check that matters before pushing is that bitbake
parses and the target still builds.
