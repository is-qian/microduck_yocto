#!/bin/sh
# Build on Hugging Face Jobs, billed to the pollen-robotics org.
#
#   scripts/build-hfjob.sh                                   # build the image
#   HFJOB_TARGET=linux-rockchip scripts/build-hfjob.sh       # one recipe
#   HFJOB_FLAVOR=cpu-basic scripts/build-hfjob.sh checkout   # layers+conf, no build
#
# The first argument is the kas command, defaulting to `build`, the same shape
# build-coach.sh uses. `checkout` is worth knowing about: it clones the layers and
# writes local.conf without compiling anything, so it validates this whole
# pipeline on cpu-basic for a fraction of a cent before cpu-performance bills by
# the minute.
#
# Why a job at all: coach's memory fault is only partly mitigated (../CONTEXT.md),
# so nothing built there should be flashed. A job gets 32 cores and 247 GB with no
# -j cap, which is why kas/limited-ram.yml is *not* composed in here.
#
# Billing is per minute of wall clock against the org balance, ~$1.90/h on
# cpu-performance. Estimated 2.5-4 h cold, 15-35 min once the bucket is warm.
# --timeout is a ceiling that stops the billing, not a reservation.
#
# The private microduck-daemon fetch needs a key, and there is no ssh-agent to
# forward into a job the way build-coach.sh does. Pass one explicitly -- and
# prefer a read-only deploy key over your own, because it is held server-side
# (encrypted) for the life of the job:
#
#   HFJOB_SSH_KEY=~/.ssh/microduck_daemon_deploy scripts/build-hfjob.sh
#
# Without a key, anything fetching only public sources still builds. Start with
#
#   scripts/build-hfjob.sh linux-rockchip
#
# to fill the cache and measure the hardware before paying for a whole image.

set -eu

cd "$(dirname "$0")/.."

FLAVOR=${HFJOB_FLAVOR:-cpu-performance}
TIMEOUT=${HFJOB_TIMEOUT:-8h}
BUCKET=${HFJOB_BUCKET:-pollen-robotics/microduck-yocto-cache}
TARGET=${HFJOB_TARGET:-}
KAS_CMD=${1:-build}

# This repo is on no git host -- only coach's bare repo and local checkouts -- so
# the build inputs travel as local-directory volumes: hf syncs them (incrementally)
# to the org's private jobs-artifacts bucket and mounts them read-only. Needs
# huggingface_hub >= 1.30.
#
# The two directories are named explicitly rather than mounting the whole tree:
# kas checks the upstream layers out in-tree (see .gitignore), so a stray local
# checkout would otherwise upload gigabytes. Together they are ~400K.
set -- \
	--namespace pollen-robotics \
	--flavor "$FLAVOR" \
	--timeout "$TIMEOUT" \
	--name "microduck-${TARGET:-image}-$KAS_CMD" \
	--detach \
	-v ./kas:/src/kas \
	-v ./meta-microduck:/src/meta-microduck \
	-v "hf://buckets/$BUCKET:/cache" \
	-e "HFJOB_BUCKET=$BUCKET" \
	-e "KAS_CMD=$KAS_CMD"

# Appended conditionally, and each guarded: under `set -e` a bare
# `[ -n "$x" ] && set -- ...` would exit the script whenever x is empty.
if [ -n "$TARGET" ]; then
	set -- "$@" -e "KAS_TARGET=$TARGET"
fi
if [ -n "${HFJOB_TASK:-}" ]; then
	set -- "$@" -e "KAS_TASK=$HFJOB_TASK"
fi
if [ -n "${HFJOB_SSH_KEY:-}" ]; then
	# base64, because $(cat key) strips the trailing newline that OpenSSH requires
	# on an ed25519 key -- which cost a build: kas picked the secret up, piped it to
	# `ssh-add -`, and got "Error loading key "(stdin)": error in libcrypto".
	set -- "$@" -s "SSH_PRIVATE_KEY_B64=$(base64 -w0 "$HFJOB_SSH_KEY")"
fi

# A quoted heredoc, not `-lc '...'`. With a single-quoted argument every apostrophe
# in the body below -- including one inside a prose comment -- silently terminates
# the string, and the remainder of the script becomes arguments to `hf` and then
# separate local commands. That is not hypothetical: this file carried nine of them
# at one point, which would have shipped a script truncated at the first comment
# that said "the uid of this image". <<"IN_CONTAINER" expands nothing and ends only
# on the terminator, so the body is free text and $VAR reaches the container intact.
IN_CONTAINER=$(cat <<"IN_CONTAINER"
set -eu

# /src is the read-only build input, and kas checks the upstream layers out beside
# meta-microduck, so work from a copy on the ephemeral disk -- under $HOME, because
# the container root is NOT writable by this image's uid 30000 and a literal /work
# dies at mkdir. $HOME, /tmp and /var/tmp are all the same 1.7 TB overlay. Plain
# -R, not -a: -a preserves ownership, which a non-root uid cannot do, and that
# would fail the job before bitbake ever started.
mkdir -p "$HOME"/work && cp -R /src/. "$HOME"/work/
cd "$HOME"/work
. kas/hfjob.env
mkdir -p "$DL_DIR" "$SSTATE_DIR"

# Seed both caches from the bucket in bulk -- sequential reads run at ~480 MB/s,
# whereas letting bitbake read or write *through* the mount either stalls
# (DL_DIR) or silently finds nothing (SSTATE_DIR). See kas/hfjob.env.
#
# Everything worth keeping leaves through save(), and save() is wired to the fatal
# signals as well as EXIT. An EXIT trap alone does NOT run when the container is
# killed by --timeout or a cancel: bash dies on the signal without reaching it. That
# is the one path that would silently drop an image which had already been written.
#
# The artifacts go first because they are the irreplaceable part, and the caches
# after, because re-fetching 10 GB or rebuilding a toolchain is merely expensive.
saved=0
save() {
	if [ "$saved" = 1 ]; then
		return 0
	fi
	saved=1

	img="$KAS_BUILD_DIR"/tmp/deploy/images/microduck-zero3
	out=/cache/deploy/"${JOB_ID:-nojobid}"
	if [ -d "$img" ]; then
		# Loud, not silent: a failed artifact copy is the whole problem this
		# function exists to prevent, so it must never scroll by as nothing.
		# Both in the condition, so set -e cannot abort save() before the
		# warning is printed and before the caches below are synced.
		if mkdir -p "$out" && cp -R "$img"/. "$out"/; then
			echo "artifacts: hf://buckets/$HFJOB_BUCKET/deploy/${JOB_ID:-nojobid}"
			ls -l "$out"
		else
			echo "WARNING: ARTIFACT COPY FAILED -- image built but not saved"
		fi
		# rm_work has already deleted the per-recipe work dirs, so tmp/log is
		# the only surviving record of what individual tasks did.
		if [ -d "$KAS_BUILD_DIR"/tmp/log ]; then
			cp -R "$KAS_BUILD_DIR"/tmp/log "$out"/bitbake-log || true
		fi
	else
		echo "no deploy/images dir -- nothing to copy out"
	fi

	# No 2>/dev/null on these: suppressing the errors here is what made one run
	# of sstate evidence unreadable and cost an experiment.
	cp -Ru "$DL_DIR"/. /cache/downloads/ || true
	cp -Ru "$SSTATE_DIR"/. /cache/sstate/ || true
}

# Named handlers rather than trap 'save; exit 143' TERM -- see the heredoc note
# above; a quoted trap body would reintroduce the apostrophe hazard for no gain.
on_term() { save; exit 143; }
on_int()  { save; exit 130; }
on_hup()  { save; exit 129; }

# Only for `build`. `checkout` and `dump` fetch and compile nothing, and copying
# gigabytes to print a config file wastes more time than the command itself.
if [ "$KAS_CMD" = build ]; then
	cp -Ru /cache/downloads/. "$DL_DIR"/ 2>/dev/null || true
	cp -Ru /cache/sstate/. "$SSTATE_DIR"/ 2>/dev/null || true
	trap on_term TERM
	trap on_int INT
	trap on_hup HUP
	trap save EXIT
fi

# kas locates the url-less microduck_os repo by asking git for the work-tree root.
# With no repo here it falls back to the *config file* directory and then resolves
# the meta-microduck layer against kas/ rather than the tree root, so bblayers.conf
# names a path that does not exist:
#
#   INFO - Using /builder/work/kas as root for repository microduck_os
#
# git init is what makes this match the git checkout coach builds from.
git init -q .
git add -A
git -c user.email=hfjob@pollen-robotics.com -c user.name=hfjob commit -q -m snapshot

if [ -n "${SSH_PRIVATE_KEY_B64:-}" ]; then
	umask 077
	mkdir -p ~/.ssh
	# id_ed25519 is a filename ssh tries by default, so this works even if the
	# agent below is somehow not consulted.
	printf %s "$SSH_PRIVATE_KEY_B64" | base64 -d > ~/.ssh/id_ed25519
	# Guarantee the trailing newline. base64 round-trips it faithfully, but a key
	# that arrives without one is rejected by ssh-add with an opaque libcrypto
	# error rather than anything mentioning newlines. An if, not `test && echo`:
	# under set -e the latter exits the script whenever the test is false, which
	# here is the normal case.
	if [ -n "$(tail -c1 ~/.ssh/id_ed25519)" ]; then
		echo >> ~/.ssh/id_ed25519
	fi
	chmod 600 ~/.ssh/id_ed25519

	# known_hosts is not optional: ssh refuses before authenticating when it
	# cannot verify the host, which surfaces as the misleading "Could not read
	# from remote repository". Same host key coach already trusts.
	ssh-keyscan -t ed25519 github.com >> ~/.ssh/known_hosts 2>/dev/null

	# An agent, not GIT_SSH_COMMAND. oe-core's BB_ENV_PASSTHROUGH_ADDITIONS carries
	# SSH_AUTH_SOCK into bitbake but NOT GIT_SSH_COMMAND, so setting the latter
	# looks load-bearing and reaches the fetcher never. This is the same mechanism
	# build-coach.sh gets from kas-container --ssh-agent.
	eval "$(ssh-agent -s)" >/dev/null
	ssh-add ~/.ssh/id_ed25519

	# DO NOT expect this to work: SSH auth to GitHub does not succeed from a job.
	# The same key, byte-identical (399 bytes, matching fingerprint), offered with
	# IdentitiesOnly and no agent, authenticates from lotus and is refused here:
	#
	#   Offering public key: /builder/.ssh/id_ed25519 ED25519 SHA256:2RTfYiDcEb...
	#   git@github.com: Permission denied (publickey).
	#
	# Not interception -- the container sees github.com's real published host key
	# (SHA256:+DiY3wvvV6TuJJhbpZisF/zLDA0zPMSvHdkr4UvCOqU) on 140.82.114.3. Cause
	# unknown, and deliberately not chased further, because microduck_daemon is
	# now a *public* repo: bitbake's do_fetch fails on the ssh URL, falls through
	# to oe-core's default MIRRORS rewrite to https, and succeeds anonymously.
	#
	# So this whole block is currently unnecessary. It is kept because it is
	# correct, and if the repo goes private again it is the starting point -- but
	# expect to need an https token rather than a key, given the above. A warning,
	# not an exit: https already works, so refusing to build would be wrong.
	if git ls-remote git@github.com:pollen-robotics/microduck_daemon.git >/dev/null 2>&1; then
		echo "ssh: deploy key loaded, microduck_daemon reachable over ssh"
	else
		echo "WARNING: ssh auth to github failed; relying on the public https mirror"
	fi
fi

# Do not let a non-zero exit skip the copy below. bitbake returns 1 merely because
# its error *counter* ended up non-zero, and a bb.error raised by a QA check bumps
# that counter without failing any task: the 2026-09-10 build ran all 7352 tasks,
# wrote the .wic, reported "all succeeded", and then set -e discarded 77 minutes of
# compute on the way out. Whether such an image is trustworthy is a judgement for
# whoever reads the log, so copy it out either way and return the real code at the
# end -- the exit status still fails the job, it just stops costing the artifacts.
rc=0
kas "$KAS_CMD" kas/microduck.yml:kas/hfjob.yml || rc=$?

# No copy here: save() already ran it, or is about to via the EXIT trap. Doing it
# in both places is how the image gets lost -- the copy that matters is the one on
# the path that also survives a kill.
exit "$rc"
IN_CONTAINER
)

exec hf jobs run "$@" ghcr.io/siemens/kas/kas:5.4 -- /bin/bash -lc "$IN_CONTAINER"
