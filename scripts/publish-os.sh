#!/bin/sh
# Publish a built RAUC bundle to an OS update channel (prototype).
#
#   scripts/publish-os.sh <hf-job-id> [machine] [channel]
#   scripts/publish-os.sh 6ad1ed6bc66288145225bc28 microduck-zero3 dev
#
# Takes the bundle a build-hfjob.sh job left in the cache bucket's deploy/<job>/,
# and writes, in the public bucket (pollen-robotics/microduck-os):
#
#   os/<machine>/<build>/microduck-bundle.raucb   never overwritten
#   os/<machine>/<channel>.env                    the channel: which bundle
#
# The bundle first, the channel file last, so a robot never reads a channel naming
# a bundle that is not there yet. microduck-osupdate on the robot reads the channel
# and checks every field; RAUC verifies the bundle itself.
#
# <build> is the bundle's RAUC_BUNDLE_BUILD, which is also the DATETIME at the end
# of its file name (bundle.bbclass gives BUNDLE_NAME and RAUC_BUNDLE_BUILD the same
# ${DATETIME}). RAUC records it per slot, and the robot compares it.
#
# The dev channel carries the dev image (password-less root): fine for a bench
# prototype, and nothing in it is secret, but robots in the field must only ever
# follow a channel of the shipping image.
set -eu

JOB=${1:?usage: $0 <hf-job-id> [machine] [channel]}
MACHINE=${2:-microduck-zero3}
CHANNEL=${3:-dev}
CACHE=${HFJOB_BUCKET:-pollen-robotics/microduck-yocto-cache}
PUBLIC=${OS_BUCKET:-pollen-robotics/microduck-os}
HF=${HF:-hf}

for v in "$JOB:[0-9a-f]{24}" "$MACHINE:[a-z0-9-]{1,40}" "$CHANNEL:[a-z0-9-]{1,20}"; do
	printf '%s' "${v%:*}" | grep -Eqx -- "${v##*:}" || { echo "$0: bad argument '${v%:*}'" >&2; exit 1; }
done

name=$($HF buckets ls "$CACHE/deploy/$JOB" | grep -oE "microduck-bundle-$MACHINE-[0-9]{14}\.raucb" | sort -u | tail -n 1)
[ -n "$name" ] || { echo "$0: no microduck-bundle-$MACHINE-*.raucb in deploy/$JOB" >&2; exit 1; }
build=${name%.raucb}; build=${build##*-}

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
$HF buckets cp "hf://buckets/$CACHE/deploy/$JOB/$name" "$work/bundle.raucb" > /dev/null
size=$(wc -c < "$work/bundle.raucb")
sha256=$(sha256sum "$work/bundle.raucb" | cut -d' ' -f1)
version=${OS_VERSION:-0.1.0}-$build

path=os/$MACHINE/$build/microduck-bundle.raucb
$HF buckets cp "$work/bundle.raucb" "hf://buckets/$PUBLIC/$path" > /dev/null
printf 'VERSION=%s\nBUILD=%s\nURL=%s\nSHA256=%s\nSIZE=%s\nCOMPATIBLE=%s\nJOB=%s\n' \
	"$version" "$build" "https://huggingface.co/buckets/$PUBLIC/resolve/$path" \
	"$sha256" "$size" "$MACHINE" "$JOB" > "$work/channel.env"
$HF buckets cp "$work/channel.env" "hf://buckets/$PUBLIC/os/$MACHINE/$CHANNEL.env" > /dev/null

echo "published $version to $MACHINE/$CHANNEL:"
cat "$work/channel.env"
