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
- **Vendor U-Boot finds the kernel by GPT partition *name*, and the name is
  `boot`.** Its environment carries `name=boot,size=32M,bootable` and a `bootcmd`
  of `boot_android ...;boot_fit;bootrkp;run distro_bootcmd;`. Rename that
  partition and the card flashes perfectly, verifies perfectly, and never loads a
  kernel — no wifi, no BLE, looks dead. It cost a flash. `docs/agents/rauc.md`.
- **A build target picks the image.** `HFJOB_TARGET=microduck-bundle` builds
  whatever `RAUC_SLOT_rootfs` names and nothing else; when that was the prod
  image, the result was a card with no password-less root that answered ping
  and could not be logged into. Check the `.wic` filename before flashing.

So before adding a recipe or a bbappend that leans on meta-rockchip or oe-core,
go read the thing you are leaning on. `git ls-remote`, the GitHub contents API
and `https://git.openembedded.org/<repo>/plain/<path>?h=wrynose` are all cheap
and answer these in seconds.

**When something cannot be verified, say so in the file.** Do not write a
confident comment over a guess.

## Deeper notes

Topics too long for this file, each a list of things that bite:

- `docs/agents/rauc.md` — A/B updates: the partition table, why `boot` cannot be
  renamed, `bootloader=noop`, the signing key, what "user data survives" means.
- `docs/agents/hf-jobs.md` — building on HF Jobs: why every job reported
  `ERROR` for a week (`bash -lc` + `.bash_logout` + `set -e`) and the probe
  technique that found it, verifying an artifact before flashing, what is
  blocked for an agent.

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

  Setting the flag is **two halves**, and the kernel fragment alone does
  nothing. `motorbus.cfg` turns `CONFIG_FIQ_DEBUGGER` off, which frees the
  driver; a dts patch enables `&uart2` and disables the `fiq_debugger` node,
  which frees the hardware. Rockchip's `rk3568-linux.dtsi` leaves `&uart2`
  disabled and assigns UART2 to the debugger, so with only the fragment the
  board came up with neither `ttyFIQ0` nor `ttyS2`.
- **Bluetooth needs `hciattach`, never `btattach`.** The AIC8800's BT is HCI
  over **uart1** (`/dev/ttyS1`, 1500000) — a different port from the motor bus.
  `btattach` produces an `hci0` that transmits and never receives, which reads
  exactly like dead silicon and is not; `hciattach -n -s 1500000 /dev/ttyS1 any
  1500000 flow` works, because `-s` sets the speed before the line discipline
  attaches. rfkill must be cleared **twice** — BlueZ adds a second, blocked
  rfkill node for `hci0` once the adapter exists. `aic8800-bt-attach.service`
  does all of this; `bt_test` (dev image) is the vendor tool that talks to the
  chip directly when you need to tell hardware faults from stack faults. Full
  writeup in README.md.
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

Three recipe constraints, all load-bearing:

- `rustls` resolves to the **`aws-lc-rs`** provider, whose build script invokes
  CMake → the recipe needs `cmake-native`. No OpenSSL in the *Rust build* --
  the rootfs has it regardless, via `ssh-server-openssh`.
- `serialport` must keep `default-features = false`. Its `libudev` feature needs
  a pkg-config sysroot the cross-build does not have and breaks the aarch64 build
  outright. `grep -c libudev Cargo.lock` should stay `0`.
- **Anything the daemon's install scripts fetch, the image has to provide.**
  `hooks/postinstall` and `scripts/install.sh` are where a provisioned Armbian
  board gets its policies, its duck detector and its voice bank; an image
  installs through neither, so on this image nobody ever ran them. Two have
  already cost a board: ONNX Runtime, looked for in a directory the recipe did
  not write (`recipes-microduck/onnxruntime`), and the policy set, whose absence
  makes `robotd` report `unhealthy` and every attempted update roll back
  (`recipes-microduck/microduck-policies`, which carries that story in full).
  So when reading a daemon release, read `hooks/postinstall` for what it does to
  a board, not just the units it installs — and give the scripts themselves what
  they ask for: they are POSIX shell and fetch with `curl`, which the daemon
  recipe therefore pulls in.

And two **paths** that the image now depends on, so a daemon change that moves
either breaks A/B without touching a single Yocto file:

- `/var/lib/robot` is a **mount point** for the persistent `data` partition.
  Everything a user adds — `POLICY_LIBRARY`, the sound bank,
  `StateDirectory=robot/*` — lives under it and survives a slot switch *because*
  it lives there. Anything that must persist goes under this root; nothing that
  renames across it and the rootfs is atomic.
- `/opt/robot/daemon` stays **on the rootfs**, on purpose. It is core: replaced
  by the image, reseeded on flash, converged by `updaterd`. Do not move it to
  `/var/lib/robot` to "make it persist" — that would let a slot switch and a
  daemon update disagree about which release is installed.

Also: `SRCREV_daemon` tracks a **release**, never `main` — the commit a
`daemon-v<PV>` manifest names as `source_revision`. Same version string on
different code is how `updaterd` decides there is nothing to do.

## Commits

Per the workspace convention, no `Co-Authored-By:`. Use a single
`Assisted-by: Claude:<model-id>` trailer.

There is no linter here. The checks that matter before pushing: bitbake parses,
the target still builds, and — if the wks changed — `scripts/check-wks-layout.py`
says `layout ok`. That last one runs in 0.1 s and has caught two mistakes a
build would have taken 50 minutes to report.

Anything that changes the partition layout or the bootloader goes on a branch in
its own worktree, never straight to `main`: `main` is what a colleague flashes,
and every push to it costs a ~50-minute CI build.
