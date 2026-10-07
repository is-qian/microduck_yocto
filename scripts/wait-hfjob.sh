#!/bin/sh
# Wait for a Hugging Face Jobs build and decide, honestly, whether it worked.
#
#   scripts/wait-hfjob.sh <job-id>
#   POLL=60 scripts/wait-hfjob.sh <job-id>     # seconds between polls, default 30
#
# Exits 0 only if the build genuinely succeeded, 1 otherwise. Prints the
# artifacts URL on success so a caller can pick it up.
#
# THE WHOLE POINT: judge the build on what it produced, not on its exit status.
#
# Every successful build of this image used to report `stage: ERROR, exit code:
# 1` -- nine for nine as of 2026-09-14 -- with zero ERROR: lines in ~11k lines of
# log and every artifact in the bucket. The cause was found on 2026-09-18 and is
# fixed in build-hfjob.sh: the job ran under `bash -lc`, a login shell sources
# ~/.bash_logout on exit, the kas image's stock Debian one runs `clear_console -q`
# when SHLVL=1, there is no tty so it fails, and `set -eu` made that the shell's
# exit status. `bash -c` fixed it.
#
# This script is kept anyway, because its rule is the more robust one: an exit
# status is one integer from the last thing that ran, while "bitbake said every
# task succeeded AND save() said the artifacts were copied out" are two positives
# a broken build cannot fake. `hf jobs wait` still cannot tell you the second.
#
# What a real failure looks like, for contrast -- the 2026-09-12 GStreamer one:
#
#   NOTE: Tasks Summary: Attempted 7447 tasks of which 7016 didn't need to be
#         rerun and 1 failed.
#   ERROR: Task (...microduck-daemon_0.12.0.bb:do_compile) failed with exit code '1'
#
# So success is judged on two positives that a broken build cannot fake: bitbake
# reporting every task succeeded, AND save() reporting the artifacts copied out.
set -eu

JOB=${1:?usage: wait-hfjob.sh <job-id>}
NS=${HFJOB_NAMESPACE:-pollen-robotics}
POLL=${POLL:-30}
# A backstop, not a target. Without one, any failure to recognise the end of a
# build becomes a silent hang: the first version of this script looped for 37
# minutes after the build had finished, because it was parsing a CLI output
# format that only exists on a terminal. Better to fail loudly and leave the
# artifacts in the bucket than to hold a runner until the job timeout.
MAX_WAIT=${MAX_WAIT:-14400}

log=$(mktemp)
seen=$(mktemp)
trap 'rm -f "$log" "$log.cur" "$seen"' EXIT
: > "$seen"

# Milestones worth echoing as they happen, plus every shape of real failure.
# Deliberately wider than the happy path: a filter that only matches success
# stays silent through a crash, and silence is indistinguishable from progress.
INTERESTING='Sstate summary:|task do_(patch|compile|install|package_qa|rootfs|image_wic|image_complete): (Started|Succeeded|Failed)|Tasks Summary|artifacts: hf://|ARTIFACT COPY FAILED|no deploy/images dir|^\| *error|ERROR:|failed with exit code|not found in the pkg-config|Nothing RPROVIDES|installed-vs-shipped'

started=$(date +%s)
while :; do
	hf jobs logs "$NS/$JOB" > "$log" 2>/dev/null || true

	# Print only lines not printed before, so a poll loop does not repeat itself.
	if [ -s "$log" ]; then
		grep -E "$INTERESTING" "$log" 2>/dev/null \
			| sed -e 's/^[0-9-]* [0-9:]* - [A-Z]* *- //' -e 's/[[:space:]]*$//' \
			| cut -c1-200 > "$log.cur" || true
		if [ -s "$log.cur" ]; then
			grep -vxF -f "$seen" "$log.cur" 2>/dev/null || true
			cat "$log.cur" >> "$seen"
			sort -u "$seen" -o "$seen"
		fi
	fi

	# A failed task fails the build: bitbake stops scheduling at the first one
	# but lets every running task finish first, which can take most of an hour
	# (a kernel, chromium-sized compiles) before "Tasks Summary" appears. Nothing
	# that happens in that time can turn the verdict around, so give it now; the
	# workflow's cancel step then stops the job, and its billing, too.
	if grep -qE "ERROR: Task .* failed with exit code" "$log" 2>/dev/null; then
		echo
		echo "FAILED: a bitbake task failed (not waiting for the running tasks to drain)"
		grep -E "ERROR:" "$log" | sed 's/^[0-9-]* [0-9:]* - [A-Z]* *- //' | cut -c1-300 | tail -20
		exit 1
	fi

	# Primary signal: the log itself. bitbake prints "Tasks Summary" exactly once
	# when it stops, and save() then prints one of the three outcomes below. This
	# is the same evidence the verdict uses, so if it is there we can decide, and
	# no amount of CLI reformatting can take it away.
	if grep -q "Tasks Summary" "$log" 2>/dev/null \
		&& grep -qE "artifacts: hf://|ARTIFACT COPY FAILED|no deploy/images dir" "$log" 2>/dev/null; then
		break
	fi

	# Secondary: the job's own state, for the case where the job dies without
	# finishing its output at all. --json matters -- `hf` defaults to
	# `--format auto`, which "picks 'agent' or 'human' based on the terminal",
	# so the shape of this output differs between a laptop and a CI runner. The
	# first version parsed the human form and silently never matched in CI.
	#
	# `.[0] // .` because inspect returns a single-element array, not an object;
	# reading .status.stage straight off the top level yields empty, which looks
	# exactly like "not terminal yet" and hangs. Tolerating both shapes costs
	# nothing and removes one more way for this to fail quietly.
	stage=$(hf jobs inspect "$JOB" --namespace "$NS" --json 2>/dev/null \
		| jq -r '(.[0]? // .) | .status.stage // empty' 2>/dev/null || true)
	case "$stage" in
		ERROR|COMPLETED|SUCCEEDED|CANCELED|CANCELLED|STOPPED|DELETED) break ;;
	esac

	elapsed=$(( $(date +%s) - started ))
	if [ "$elapsed" -gt "$MAX_WAIT" ]; then
		echo "FAILED: gave up after ${elapsed}s waiting for $JOB (stage='${stage:-unknown}')"
		echo "  the job may still be running: hf jobs ps --namespace $NS"
		exit 1
	fi
	sleep "$POLL"
done

echo
echo "job reached: ${stage:-terminal-by-log}  (this says nothing about success -- see the header)"

# --- the actual verdict ---------------------------------------------------
hf jobs logs "$NS/$JOB" > "$log" 2>/dev/null || true

if grep -qE "Tasks Summary.*[0-9]+ failed" "$log"; then
	echo "FAILED: bitbake reported failed tasks"
	grep -E "Tasks Summary|ERROR: Task .* failed|^\| *error" "$log" \
		| sed 's/^[0-9-]* [0-9:]* - [A-Z]* *- //' | cut -c1-200 | tail -20
	exit 1
fi

if ! grep -q "Tasks Summary.*all succeeded" "$log"; then
	echo "FAILED: no 'all succeeded' summary -- the build did not finish"
	tail -20 "$log" | cut -c1-200
	exit 1
fi

artifacts=$(grep -o "artifacts: hf://[^ ]*" "$log" | tail -1 | sed 's/^artifacts: //')
if [ -z "$artifacts" ]; then
	echo "FAILED: bitbake succeeded but save() copied nothing out"
	grep -E "ARTIFACT COPY FAILED|no deploy/images dir" "$log" | tail -5
	exit 1
fi

echo "SUCCESS"
echo "artifacts=$artifacts"
