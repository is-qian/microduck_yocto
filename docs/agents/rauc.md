# A/B updates with RAUC — what an agent needs to know

Stage 1 is verified on hardware (2026-09-18): signed bundles build on HF, the
signature verifies on the board against the CA in
`recipes-core/rauc/files/ca.cert.pem`, and `rauc install` writes `rootfs_b`.
Stage 2 — actually booting slot B — is not started. `README.md` has the full
narrative; this is the list of things that will bite you.

## The partition table is load-bearing, and one name is not ours

`meta-microduck/files/wic/microduck-ab.wks.in`. Every partition has an explicit
`--offset` and `--fixed-size`, so wic fails rather than shifting anything. A
bundle built against one layout writing into another is the failure this prevents.

**The kernel partition is named `boot`, not `boot_a`, and you cannot rename it.**
Vendor U-Boot finds the kernel by GPT partition name — its environment carries
`name=boot,size=32M,bootable` and `bootcmd=boot_android ...;boot_fit;bootrkp;run
distro_bootcmd;`. Called `boot_a`, the card flashes perfectly, RKNS is at sector
64, a valid `ANDROID!` image sits in the slot, and the board never loads a kernel:
no wifi, no BLE, looks dead. `boot_b` is reserved at its offset for stage 2, which
is where renaming the pair belongs — beside the `boot.scr` that can choose.

`scripts/check-wks-layout.py` catches overlaps, unplaced partitions, line
continuations and artifacts that no longer fit, in 0.1 s. Run it before any
build that touches the wks. It is keyed by `--part-name`, so it moves with renames.

`--fixed-size` on idblock is a **bound, not a size**. idblock has no GPT entry,
so wic cannot detect it overlapping anything, and `uboot_env` starts 32 KiB past
the bound. Do not remove it because "nothing reads its length".

## Where RAUC's files are on wrynose

`system.conf` and the keyring install to **`/usr/lib/rauc/`**, not `/etc/rauc/`.
meta-rauc moved them in wrynose; most RAUC docs and the upstream `rk-rauc-demo`
still say `/etc`. The keyring `path=` in system.conf must be absolute.

Config adaptations go in `rauc-conf.bbappend`, not `rauc_%.bbappend`.

## Two things the machine conf used to get wrong about RAUC

- **RAUC does not need `CONFIG_BOOTCOUNT_LIMIT`.** `grep -ri bootcount` over
  RAUC's own docs returns nothing. The retry counter is `BOOT_<slot>_LEFT`, a
  plain env variable our own boot script decrements. What the `uboot` backend
  genuinely needs is a writable environment (`CONFIG_ENV_IS_IN_*`),
  `CONFIG_CMD_SETEXPR`, and `fw_printenv`/`fw_setenv` + `/etc/fw_env.config`.
- **`BOOT_<slot>_LEFT` is hex.** `g_ascii_strtoull(..., 16)` in
  `src/bootloaders/uboot.c`. The stage 2 boot script must say `0x${BOOT_A_LEFT}`.
  A bare `test ${BOOT_A_LEFT} -gt 0` is wrong from 10 upward and for A–F.

Still true from that note: Rockchip's `CONFIG_SPL_AB`/`CONFIG_AVB_LIBAVB_AB` is a
*different, conflicting* A/B mechanism. Do not enable both.

## Stage 2: how the kernel cmdline is actually built, and the trap in it

Read before touching bootargs. All of this is vendor U-Boot 2017.09 at the SRCREV
meta-rockchip pins, read at that exact ref.

**The env var `bootargs` is the single accumulator, and the DT does not win by
being the DT — it wins by being merged last.** Three sources fold into one var:

1. `boot.img`'s header cmdline is appended to env `bootargs`
   (`common/image-android.c:241-259`).
2. `boot_android` assembles its cmdline starting from env `bootargs` as chunk 0
   (`common/android_bootloader.c:403-405`), then writes the result back with
   `env_update` (`:1370-1371`).
3. The DTB's `/chosen/bootargs` is read out and merged *into the env var*
   (`arch/arm/mach-rockchip/board.c:1300-1333`), and the final env value is what
   `board_fdt_chosen_bootargs()` returns as the kernel's cmdline (`:1372-1376`).

So a boot script setting `bootargs` **does** reach the kernel. That is the good
news and it means one `boot.img` can serve both slots.

**The trap: `env_update()` replaces a same-key argument, it does not append.**
`cmd/nvedit.c:406-535` tokenises both strings on spaces, splits each on `=`, and
on a matching key calls `env_replace` (`:511-513`). So if the DTB's
`/chosen/bootargs` carries a `root=` — ours does — it **silently overwrites the
`root=` a boot script set**, with no error from either side. The guard that strips
the DT's `root=` (`board.c:1328`) is compiled only under `CONFIG_ANDROID_AB`,
which `rk3568_defconfig` does not set. This is why "set bootargs in boot.scr" is
not on its own a design.

Three ways out, and the second is ours because it deletes code rather than adding
a mechanism:

1. Set env `android_rootdev` instead — a purpose-built hook that becomes `root=`
   (`android_bootloader.c:432-445`). Still loses to a DT `root=`.
2. **Strip `root=` from the chosen-node patch**, keeping `rw rootwait
   console=ttyFIQ0 console=tty0`. Then the env's `root=` is the only one. Note
   `console=tty0` is load-bearing when `MICRODUCK_MOTOR_UART = "1"`.
3. Enable `CONFIG_ANDROID_AB` for the filter — pulls in Rockchip's *conflicting*
   AVB A/B machinery. No.

Two more facts that shape the rest of stage 2:

- **The kernel partition name is an exact `strcmp` against the literal `"boot"`**
  (`include/android_image.h:14`, `disk/part.c:727`), with no env override. The
  suffix-append path needs `CONFIG_ANDROID_AB`, which is off. So `boot_b` is inert
  — safe to leave reserved — and there is no runtime way to point this U-Boot at
  it. Kernel A/B needs a different mechanism than rootfs A/B, and is not stage 2.
- `bootcmd` is `boot_android ${devtype} ${devnum};boot_fit;bootrkp;run
  distro_bootcmd;` (`include/configs/rockchip-common.h:172-176`). `distro_bootcmd`
  — and therefore any `boot.scr` autodiscovery — runs **last**, only if all three
  earlier arms fail. A `boot.scr` that must be authoritative needs
  `CONFIG_BOOTCOMMAND` changed, not just a file dropped on a partition.

## `bootloader=noop` is deliberate, and two outputs look wrong because of it

The board has no boot-time slot selection: `root=` is compiled into the DTB
inside `boot.img`. So `noop` is the honest backend, and it produces:

- `noop bootloader: ignore setting slot rootfs.1 as primary` on install — success,
  not failure. RAUC did everything it owns and stopped where the board's
  capability ends.
- `boot status: bad` on **both** slots in `rauc status`. `noop` cannot answer,
  RAUC's default for unknown is bad. Becomes real under `bootloader=uboot`.
  Consequence: `rauc install` may need `--target-slot rootfs.1` explicitly,
  because it will not auto-pick when every slot reads bad.

## Build the bundle, not the image

`HFJOB_TARGET=microduck-bundle` builds the bundle **and** the image it wraps,
because `RAUC_SLOT_rootfs` is a dependency. On this branch that is
`microduck-image-dev`, so card and bundle are the same thing. It was briefly
`microduck-image` (prod), which built a card with no password-less root that came
up, answered ping, and could not be logged into. Flip it back to prod when stage 2
can boot slot B and "does a prod rootfs come up" becomes the question.

`RAUC_SLOT_rootfs[fstype] = "ext4"` is required: `bundle.bbclass` defaults to
the first entry of `IMAGE_FSTYPES`, which is oe-core's `tar.zst` here.

## The signing key

Lives at `$KAS_BUILD_DIR/rauc-keys/development-1.{key,cert}.pem`, generated once
with meta-rauc's `scripts/openssl-ca.sh` (README.md has the commands). **Never in
this repo, never in the image.** Only `ca.cert.pem` — the public CA — is committed.

`scripts/build-hfjob.sh` sends the pair to the job **only for a bundle target**,
the key as `-s` (secret), the cert as `-e`. It refuses locally in 0.1 s if either
is unreadable, because `bundle.bbclass` only checks the key in `do_bundle` —
task 7561 of 7568 — so without the guard a missing key costs a full image build.

That key has been on HF's compute. It is a development key forever; a production
key must be a different key with its own CA added to the image keyring.

## User data survives; know exactly what that means

`data` is ext4, 8 GiB, mounted at `/var/lib/robot` by `recipes-core/microduck-data/`.
That is where the daemon already keeps everything a user adds — `POLICY_LIBRARY`,
the sound bank, `StateDirectory=robot/*` — so **no daemon change was needed**,
and that was checked: the policy installer's `fs::rename` calls stay inside the
library root, so nothing crosses a filesystem boundary.

- Survives: daemon updates (never touched it) and RAUC slot switches (neither
  slot touches it).
- Does **not** survive: a reflash of the whole `.wic`, which zeros that region.
  Do not describe it as if it did.
- **A failed mount cascades, and this cost a board its updater.** The daemon
  units carry `StateDirectory=robot/config` and `robot/updater`; systemd turns
  those into an implicit `RequiresMountsFor=/var/lib/robot/...`, i.e. a
  `Requires=` on `var-lib-robot.mount`. When that mount *failed* (unformatted
  partition), `configd` and `updaterd` never started — "Dependency failed for
  Robot config service (wifi, identity)". `WantedBy` on the mount did nothing
  for this; only a *skipped* unit satisfies `Requires=`. Hence
  `ConditionPathExists=/dev/disk/by-label/data`: udev creates `by-label/<x>`
  only when blkid finds a filesystem labelled `<x>`, so one path answers both
  "is there a partition" and "is it formatted", and a blank one degrades to the
  rootfs directories with all daemons up. Verified live on the failing board.
- A `.mount` unit, not a wic `--mountpoint`: the latter writes `/etc/fstab`, and
  a missing partition there is an emergency shell.
- `data-directory=/var/lib/robot/rauc` in system.conf puts RAUC's own bookkeeping
  there too. Without it RAUC keeps per-slot status that a slot rewrite erases.

`/opt/robot/daemon` stays **on the rootfs** on purpose: it is core, replaced by
the image, reseeded on flash. A slot switch reverts the daemon to the image's
seed and `updaterd` converges it again — test that deliberately once stage 2 exists.

## What is not done

- Kernel is not A/B. `boot_b` is reserved and empty.
- Nothing can select a slot at boot. Stage 2a is **done in the tree, untested on
  hardware**: a writable U-Boot environment
  (`recipes-bsp/u-boot/files/rauc-env.cfg`) plus `/etc/fw_env.config` and
  `libubootenv-bin` (`recipes-bsp/u-boot-env/`). It deliberately changes no boot
  behaviour, so the board must come up exactly as before; what it adds is that
  `fw_setenv X 1; reboot; fw_printenv X` survives. Verify that before stage 2b,
  because everything RAUC does with `bootloader=uboot` rests on it, and a wrong
  `CONFIG_ENV_OFFSET` corrupts U-Boot rather than failing loudly.
  Stage 2b is then the boot script + stripping `root=` from the chosen patch +
  `bootloader=uboot`; the fallback is `radxa/u-boot next-dev-v2024.10`, which
  boots via extlinux (see `conf/machine/microduck-zero3.conf`).
- The `data` partition is **done, both paths on hardware**. Blank partition:
  mount skipped, every daemon up (2026-09-18, incomplete-download card). Correctly
  flashed card, first boot, no hand-formatting (2026-09-21): wic's own `mkfs`
  produced `by-label/data`, `var-lib-robot.mount` came up (`/dev/mmcblk1p7 ext4
  7.7G`), `StateDirectory=` created `config/` and `updater/` on the empty
  filesystem, all five daemons active, zero failed units, no RAUC
  data-directory warning. Note that image was built from `c15f752`, *before* the
  Condition fix in `e8130b0` — which is fine, a correct flash never takes the
  degraded path — but it means **no image containing `e8130b0` has been built**.
  The next `HFJOB_TARGET=microduck-bundle` run picks it up; nothing to test, the
  condition is already proven live as a runtime drop-in.
- `updater-design.md` in the daemon repo (`:46`) still records A/B as rejected
  ("would be over-engineering"). Its premise — "the OS is static" — expired the
  week the camera DTS and three GStreamer patches each needed a reflash. Update
  it rather than leave it contradicting the code.
