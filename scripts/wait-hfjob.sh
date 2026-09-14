#!/bin/sh
# Wait for a Hugging Face Jobs build and decide, honestly, whether it worked.
#
#   scripts/wait-hfjob.sh <job-id>
#   POLL=60 scripts/wait-hfjob.sh <job-id>     # seconds between polls, default 90
#
# Exits 0 only if the build genuinely succeeded, 1 otherwise. Prints the
# artifacts URL on success so a caller can pick it up.
#
# THE WHOLE POINT: you cannot use the job's exit status.
#
# Every successful build of this image reports `stage: ERROR, exit code: 1` --
# nine for nine as of 2026-09-14, with no counter-example. Builds that ran all
# 7475 tasks, logged "all succeeded", wrote the .wic and copied every artifact
# into the bucket still finish ERROR. There are zero ERROR: lines in ~11k log
# lines when that happens, and bitbake with no errors exits 0, so it is not
# coming from kas; it happens somewhere after save() during the cache sync, and
# the cause has never been attributed. `hf jobs wait` would therefore fail every
# green build, which is why this script exists instead.
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
POLL=${POLL:-90}

log=$(mktemp)
seen=$(mktemp)
trap 'rm -f "$log" "$seen"' EXIT
: > "$seen"

# Milestones worth echoing as they happen, plus every shape of real failure.
# Deliberately wider than the happy path: a filter that only matches success
# stays silent through a crash, and silence is indistinguishable from progress.
INTERESTING='Sstate summary:|task do_(patch|compile|install|package_qa|rootfs|image_wic|image_complete): (Started|Succeeded|Failed)|Tasks Summary|artifacts: hf://|ARTIFACT COPY FAILED|no deploy/images dir|^\| *error|ERROR:|failed with exit code|not found in the pkg-config|Nothing RPROVIDES|installed-vs-shipped'

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

	stage=$(hf jobs inspect "$JOB" --namespace "$NS" 2>/dev/null \
		| tr '\t' '\n' | grep -o "'stage': '[A-Z]*'" | head -1 || true)
	case "$stage" in
		*ERROR*|*COMPLETED*|*SUCCEEDED*|*CANCEL*|*STOPPED*|*DELETED*) break ;;
	esac
	sleep "$POLL"
done

echo
echo "job reached: ${stage:-unknown}  (this says nothing about success -- see the header)"

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
