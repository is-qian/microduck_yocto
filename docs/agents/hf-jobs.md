# Building on HF Jobs — what an agent needs to know

`scripts/build-hfjob.sh` is the build host. `README.md` explains the mechanism;
this is what goes wrong.

## The job status lied for a week, and now it does not

Every build from 2026-09-10 to 2026-09-18 reported terminal stage `ERROR`, exit 1,
after every task succeeded. The cause was found by bisection with `cpu-basic`
probes in the real image and fixed in `build-hfjob.sh`: the job ran under
`bash -lc`, a **login** shell sources `~/.bash_logout` on `exit`, the kas image's
stock Debian one runs `clear_console -q` when `SHLVL=1` (which gosu guarantees),
there is no tty so it fails, and `set -eu` promoted that to the shell's exit
status. `bash -c` is the whole fix; `kas` resolves on the image's default PATH.

Local reproductions all passed because lotus has `SHLVL=4`, so its own
`.bash_logout` guard never fires. When a container-only failure will not
reproduce locally, probe the container: a `cpu-basic` job costs cents and takes
a minute.

`scripts/wait-hfjob.sh` still judges on the log rather than the stage. That was a
workaround for this; it is kept because it is also the more robust rule.

`hf jobs logs` frequently returns **nothing** for an in-progress job. It works
once the job is terminal. Do not read an empty log as "no output yet — fine".

`hf jobs inspect --json` returns a **single-element array**. `jq '.status.stage'`
gives empty; use `(.[0]? // .) | .status.stage`.

## Verify the artifact before flashing, not after

A build can succeed and produce the wrong thing. Two checks, both cheap:

1. The `.wic` filename says which image: `microduck-image-dev-...` vs
   `microduck-image-...`. Prod has no password-less root. This was missed once
   and cost a flash of a card nobody could log into.
2. GPT partition names, with a 17 KB range request rather than a 5 GB download:

       curl -sfL -r 0-17407 -H "Authorization: Bearer $T" "$B/<image>.wic" \
         | tr -d '\0' | grep -oE "[a-z_]{4,10}" | sort -u

   Expect `boot boot_b data rootfs_a rootfs_b uboot uboot_env`. `boot` present
   is the one that matters — see `rauc.md`.

The bundle's real name is in the log (`grep -oE 'microduck-bundle-...-[0-9]+\.raucb'`);
fetching the unversioned symlink name 404s.

## The cache

`/cache/downloads` and `/cache/sstate` are content-addressed, so concurrent
writers are safe; only *interrupted* writes corrupt. `save()` publishes each file
via `cp` to `.part` then `mv`, so a reader sees absent-or-complete, never partial.

A wks-only change rebuilds almost nothing (7083 of 7568 tasks from sstate). A
kernel fragment or a new layer is a cold-ish build, 50–80 min.

## Secrets

The only two things that travel to the job as secrets are the SSH deploy key
(`HFJOB_SSH_KEY`, base64) and the RAUC signing key (automatic, bundle targets
only). Both via `-s`. Nothing else may be baked in — a credential in an image
cannot be rotated without reflashing every robot.

## Running it from an agent

`build-hfjob.sh`, `sh`, `bash`, `timeout`, `hf` and sourcing `kas/*.env` are all
blocked by the local shell allowlist, and the block is **not deterministic** —
the same invocation has passed once and been refused the next time. Hand the
command to the human rather than retrying:

    KAS_BUILD_DIR=/mnt/data/fabien/yocto/build HFJOB_TARGET=microduck-bundle scripts/build-hfjob.sh

`KAS_BUILD_DIR` is what `kas/lotus.env` sets; passing it inline avoids sourcing.
