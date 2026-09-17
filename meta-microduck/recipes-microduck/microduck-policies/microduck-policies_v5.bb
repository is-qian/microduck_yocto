SUMMARY = "The official microduck policy set"
DESCRIPTION = "The ONNX networks robotd runs: the walking gait, the standing \
network, and the one-shots the gamepad buttons fire. Installed under \
/opt/robot/policies, deliberately outside the daemon release -- a gait retrain \
should not need a daemon release, and a daemon fix should not re-ship six \
megabytes of unchanged weights."
HOMEPAGE = "https://huggingface.co/pollen-robotics/microduck-policies"

LICENSE = "Apache-2.0"
LIC_FILES_CHKSUM = "file://${COMMON_LICENSE_DIR}/Apache-2.0;md5=89aea4e17d99a7cacdbeed46a0096b10"

# Why this recipe exists.
#
# robotd resolves every policy slot under /opt/robot/policies/current, and a slot it
# cannot read is not a limitation -- it is `unhealthy`:
#
#     robotd/src/main.rs:793
#     if let Some(reason) = self.policy_error.load_full() {
#         return unhealthy(format!("policy unavailable: {reason}"));
#     }
#
# `unhealthy` is what the updater's health gate rolls a release back on, so an image
# with no policy set cannot install an update at all. Observed on the board:
#
#     "reason": "health check failed: not healthy within 30s: policy unavailable:
#      reading /opt/robot/policies/current/velstand.onnx: No such file or directory"
#
# Note that is a *rollback*, not a refusal: the release downloaded, verified, swapped,
# started, failed the gate and was reverted. Every update, forever, with a robot that
# holds its pose in between.
#
# Upstream fills that directory from `scripts/seed-policies.sh`, run by
# `hooks/postinstall` on every update and by `scripts/install.sh` on a fresh board. An
# image installs through neither, so nothing ever ran it here. Re-running it at first
# boot would not fix this either: it needs curl, which this image does not carry (see
# recipes-microduck/onnxruntime for the other half of that discovery), and it needs the
# network to be up before the first update check -- on a robot provisioned over BLE
# because it has no wifi yet, that is a robot that cannot walk out of the box.
#
# So the image ships the set, and the seeder then leaves it alone. That is not a
# coincidence: seed-policies.sh reads the `.source` record written below, compares
# `version` against its own pin, and exits 0 for anything at or above it. A set from
# another repo, or one with no record, it refuses to touch at all.

# The pin is `PV`, so it is in the filename: microduck-policies_v5.bb. And it belongs to
# the daemon rather than to us -- `POLICY_VERSION` in the release's own
# scripts/seed-policies.sh, which an xtask test holds against
# [workspace.metadata.policies] in Cargo.toml. It is v5 at daemon 0.13.0 and still v5 at
# 0.14.1, so rename this file in step with a daemon bump.
#
# Seeding *below* what the daemon names is the one thing that actively breaks: its
# defaults name files only the newer set carries -- velstand.onnx is exactly such a file,
# new in v5 -- so robotd would fail to load its gait, and seed-policies.sh would try to
# move the board up over the network on the first update, which is where we came in.
# Seeding above it is safe; the pin is a minimum, not a ceiling.
POLICY_REPO = "pollen-robotics/microduck-policies"

# Fetched by commit, recorded as the tag. A Hub tag is a mutable ref -- it can be moved
# onto different weights -- and "reproducible" is the reason this tree pins every layer
# to a commit too. `.source` still says `v5`, because that is the string seed-policies.sh
# and `robotctl policy check` compare against.
POLICY_REV = "99e9e7bd11b0b1f8744434a3e255993776fd3888"
POLICY_BASE = "https://huggingface.co/${POLICY_REPO}/resolve/${POLICY_REV}"

# The whole set, not the subset robotd's defaults happen to name today.
#
# This is what seed-policies.sh installs -- it downloads every `"file"` in the revision's
# manifest.json -- and matching it is what makes the two paths produce the same board.
# It also costs nothing to be complete: 7.9 MiB of weights against a 535 MiB image, and
# the alternative is that `robotctl policy load <slot> roller.onnx` fails on a robot
# whose set is silently a subset of the one it says it has.
#
# manifest.json comes with them because robotd reads it: robotd-params' `set_manifest()`
# takes the ground-pick period, the sitstand ramp and which policies are skills from
# `${POLICY_DIR}/manifest.json`, falling back to the prototype's hard-coded numbers when
# it is absent. A set without it runs, subtly differently.
#
# Only manifest.json takes a downloadfilename. DL_DIR is flat and shared by every recipe
# in the tree, and `manifest.json` is a name another recipe will plausibly want; the ten
# weights are distinctive enough to stand on their own.
SRC_URI = "\
    ${POLICY_BASE}/manifest.json;name=manifest;downloadfilename=${BPN}-${PV}-manifest.json \
    ${POLICY_BASE}/velstand.onnx;name=velstand \
    ${POLICY_BASE}/alpha_walking.onnx;name=alpha_walking \
    ${POLICY_BASE}/alpha_stand.onnx;name=alpha_stand \
    ${POLICY_BASE}/alpha_sitstand.onnx;name=alpha_sitstand \
    ${POLICY_BASE}/alpha_ground_pick.onnx;name=alpha_ground_pick \
    ${POLICY_BASE}/ball_kick_left.onnx;name=ball_kick_left \
    ${POLICY_BASE}/ball_kick_right.onnx;name=ball_kick_right \
    ${POLICY_BASE}/roulade.onnx;name=roulade \
    ${POLICY_BASE}/roller.onnx;name=roller \
    ${POLICY_BASE}/roller_crouch.onnx;name=roller_crouch \
"

# The Hub stores these in LFS, and an LFS object's oid *is* its sha256 -- so these came
# from the tree listing at ${POLICY_REV} and were confirmed against the downloaded bytes,
# rather than being taken on trust from one of the two.
SRC_URI[manifest.sha256sum] = "622048c2c23ea58942023f66fd16b189a875fd169e88d85beb16ebbe63b20c94"
SRC_URI[velstand.sha256sum] = "1c659be55da94bc5753b707de5c6a3e7c49931e05ca3b6991615cef1a8ba9a45"
SRC_URI[alpha_walking.sha256sum] = "e36332d383997d51401897734cd3e79cf5038406feddb18b4d57ecfb141daa6c"
SRC_URI[alpha_stand.sha256sum] = "1569268713e40deea795dd2922dba50d3621e15a872855408b6b1b125b1c094b"
SRC_URI[alpha_sitstand.sha256sum] = "c6c40e35e726eabd803d633e090d112994f469921152448367953fbaf9799bc8"
SRC_URI[alpha_ground_pick.sha256sum] = "ffbf5109982ff999b0ba53afe86b9ae731bbec679d67fb7f8ab4c52152c88872"
SRC_URI[ball_kick_left.sha256sum] = "d6928284dccd3dd61e08bf2f760effa74309fbefd97b2b31afb2a60f526d196a"
SRC_URI[ball_kick_right.sha256sum] = "147a32c388c6b19111b3ac3b550a9a6dc8b8bf267118af4d8c3712522eedb5af"
SRC_URI[roulade.sha256sum] = "3d60da08fc13f29c1b57f41977aa898132c0d60042100149d8e775affcbca32b"
SRC_URI[roller.sha256sum] = "cf05651d2708a2f9364212e86b866c97a70ace8131c492500105e8f28bf99afd"
SRC_URI[roller_crouch.sha256sum] = "a1a084be240469c76ac9d3fa44d4792f16d4b1da60398b3ecd3cfc5e2244d990"

# Weights and a JSON file. Nothing here is compiled, linked or tuned, so one build
# serves every machine.
inherit allarch

# Nothing unpacks to a directory here -- eleven loose files land straight in
# ${UNPACKDIR} -- so the default S, ${UNPACKDIR}/${BP}, names a directory that never
# exists and every shell task fails on the `cd` into it before running a line. (The
# prohibition in AGENTS.md is specific to S = "${UNPACKDIR}/git" on a git recipe, which
# wrynose rejects in do_unpack; this is the unrelated loose-files case.)
S = "${UNPACKDIR}"

do_configure[noexec] = "1"
do_compile[noexec] = "1"

# The layout is the updater's, and that is the point: `releases/<name>/` beside a
# `current` symlink, swapped by rename. updater/src/policy.rs installs sets exactly this
# way, so a set that arrived in the image and a set that arrived from
# `robotctl policy update` are the same kind of thing.
#
# `seed-` is load-bearing. Both the seeder and updaterd treat that prefix as "ours" and
# refuse to disturb a `current` without it -- so a set installed here can be moved
# forward later, while one named anything else would be left frozen forever.
POLICY_ROOT = "/opt/robot/policies"
POLICY_SET = "releases/seed-${PV}"

do_install() {
	install -d ${D}${POLICY_ROOT}/${POLICY_SET}
	install -m 0644 ${UNPACKDIR}/*.onnx ${D}${POLICY_ROOT}/${POLICY_SET}/
	install -m 0644 ${UNPACKDIR}/${BPN}-${PV}-manifest.json \
		${D}${POLICY_ROOT}/${POLICY_SET}/manifest.json

	# Where this set came from, beside the set. It is what lets `robotctl policy check`
	# ask the Hub whether there is anything newer without anyone configuring the repo a
	# second time, and -- the reason it is not optional here -- it is what
	# seed-policies.sh reads to decide whether to leave us alone. Without it the set
	# parses as "no record", which the seeder refuses to touch: safe, but it would also
	# never move the board forward again.
	#
	# Two keys, matching `updater::policy::Source::render()` exactly. The shell seeder
	# also writes a `fetched=` timestamp and `Source::parse` ignores keys it does not
	# know, but a build-time timestamp is a reproducibility problem for no gain: the
	# thing it would record is the date of a build, not of a download.
	cat > ${D}${POLICY_ROOT}/${POLICY_SET}/.source <<-EOT
		repo=${POLICY_REPO}
		version=${PV}
	EOT
	chmod 0644 ${D}${POLICY_ROOT}/${POLICY_SET}/.source

	# Relative, not absolute, and for the seeder's stated reason: an absolute target
	# resolves against the wrong directory the moment POLICY_ROOT is not what it was
	# written with -- ${D} being exactly such a moment.
	ln -snf ${POLICY_SET} ${D}${POLICY_ROOT}/current
}

FILES:${PN} = "${POLICY_ROOT}"
