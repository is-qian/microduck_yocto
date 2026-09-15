SUMMARY = "microduck robot control and update daemons"
DESCRIPTION = "robotd (50 Hz Dynamixel control loop), updaterd (signed, \
rollback-safe update engine), robotctl (local CLI), padd (gamepad client), \
configd (wifi and robot identity, drives NetworkManager), btd (the BLE \
transport that fronts configd, so a robot with no network can be provisioned \
from a phone), mediad, tofd, sounds and the duck/pet detectors. Every unit is \
enabled: a robot with the hardware should come up working. On a board without \
it, disable the three that need parts -- mediad, tofd, padd."
HOMEPAGE = "https://github.com/pollen-robotics/microduck_daemon"

# Upstream now ships a LICENSE file, so point at the whole thing rather than a
# line range in Cargo.toml. The old form pinned `license = "Apache-2.0"` by line
# number and md5, which survived exactly as long as nobody edited the manifest
# above it: at 6507d2e that declaration has moved from line 28 to line 88, and
# line 28 is now a comment about `ort` version floors. A whole-file checksum
# cannot drift that way.
#
# 86d3f3a9 is the standard Apache-2.0 text (201 lines). It differs from oe-core's
# well-known 89aea4e1 only because that one is the licence as distributed in
# common-licenses with no trailing appendix differences -- both are Apache-2.0,
# and the checksum here describes the file we actually fetch.
LICENSE = "Apache-2.0"
LIC_FILES_CHKSUM = "file://LICENSE;md5=86d3f3a95c324c9479bd8986968f4327"

inherit cargo cargo-update-recipe-crates pkgconfig systemd useradd

# microduck_daemon is a public repo, so this fetches anonymously, and so do the
# robots: updaterd pulls release assets over plain HTTPS with no token. That is
# load-bearing -- updater-design.md §11 records that a *private* repo's
# releases/download/<tag>/<asset> URL returns 404 with or without a token, and
# only the api.github.com/.../releases/assets/<id> form works authenticated.
#
# SRCREV_daemon tracks a RELEASE, not main. The stable release daemon-v0.12.0
# carries "source_revision": "213a5ce9..." in its manifest, so pinning that exact
# commit makes the image's 0.12.0 the same code as the published 0.12.0.
#
# That matters more than it looks. Before this, the recipe pinned 6507d2e -- 21
# commits *ahead* of the release -- and both called themselves 0.12.0. updaterd
# compares versions, so it would have seen 0.12.0 == 0.12.0, concluded there was
# nothing to do, and never converged, while the two binaries differed. A version
# that does not identify the code makes "is this robot up to date?" unanswerable.
#
# Chasing main instead would not help and would cost reproducibility: main was
# already 31 commits past our pin five days after setting it, and kas/microduck.yml
# rejects AUTOREV by name for exactly that reason. The image seeds a release; the
# robot converges to the newest release by itself.
FILESEXTRAPATHS:prepend := "${THISDIR}/files:"

SRC_URI = "git://git@github.com/pollen-robotics/microduck_daemon.git;protocol=ssh;branch=main;name=daemon \
           git://github.com/pollen-robotics/bmi088-rs;protocol=https;nobranch=1;name=bmi088;destsuffix=bmi088-rs \
           file://60-robot-i2c.rules \
"
SRCREV_daemon = "213a5ce90ec5f2605b6e6977b75672d9d2eea8c5"
SRCREV_bmi088 = "bd113f317b14923f50f9a12a06678fe162b7fe06"
SRCREV_FORMAT = "daemon_bmi088"

# bmi088 is the one git dependency in the lockfile -- tof's BMI088 IMU driver,
# ours rather than crates.io's because the published ones stop at the raw
# registers. It has to be named here because cargo-update-recipe-crates only
# emits crates.io entries:
#
#     crates_candidates = list(filter(lambda c: 'crates.io' in c.get('source', ''), ...))
#
# so a git dep never reaches microduck-daemon-crates.inc and would be missing at
# build time. cargo_common.bbclass closes the gap: cargo_common_do_patch_paths
# walks SRC_URI for git entries that carry BOTH `name` and `destsuffix`, appends
#
#     [patch."https://github.com/pollen-robotics/bmi088-rs"]
#     bmi088 = { path = "<UNPACKDIR>/bmi088-rs" }
#
# to the cargo config, and strips the `source = "git...` lines from Cargo.lock so
# --frozen still passes. `name` must therefore be the *crate* name (bmi088), not
# the repository name (bmi088-rs).
#
# SRCREV_bmi088 is the commit, not the tag. v0.1.2 is an annotated tag, so
# `git ls-remote` shows the tag object 172ddbee while the commit underneath --
# the one the lockfile pins -- is bd113f31. Using the tag object here fetches
# something cargo then rejects.
#
# Naming the main repo `daemon` is forced by having two git URLs: SRCREV_FORMAT
# has to spell out both, and a bare SRCREV no longer applies to either.

require microduck-daemon-crates.inc

# No `S =` assignment -- wrynose errors out on S = "${UNPACKDIR}/git"; the default
# ${UNPACKDIR}/${BP} is correct. See AGENTS.md.

# rustls resolves to the aws-lc-rs provider, whose aws-lc-sys build script invokes
# CMake. There is no OpenSSL anywhere in the tree.
DEPENDS += "cmake-native"

# libudev, for padd only: gilrs depends on libudev-sys unconditionally on Linux and
# no feature disables it. duck-control/Cargo.toml calls that "a real cost" because
# their cargo-zigbuild cross-build has no pkg-config sysroot.
#
# Under Yocto it needs both halves, and missing either fails the build:
#   DEPENDS "systemd"   provides the target libudev to link against
#   inherit pkgconfig   provides the pkg-config *binary* that libudev-sys/build.rs
#                       shells out to. cargo_common already exports
#                       PKG_CONFIG_SYSROOT_DIR and friends, so without this the
#                       env looks right and the build still dies with
#                       "The pkg-config command could not be found."
DEPENDS += "systemd"

# GStreamer, for mediad. It links the bindings rather than driving gst-launch:
# gstreamer, gstreamer-app, gstreamer-webrtc, gstreamer-video and glib, each of
# which has a -sys crate whose build.rs pkg-configs a .pc out of the sysroot.
#
# They are declared under
#
#     [target.'cfg(target_os = "linux")'.dependencies]
#
# rather than plain [dependencies], which is easy to miss when reading the
# manifest and is exactly how this was missed the first time -- the 0.12.0 build
# failed at
#
#     error: failed to run custom build command for `gstreamer-sys v0.24.5`
#     Package 'gstreamer-1.0' not found
#
# tof's bmi088 hides in the same kind of section. When adding a workspace member,
# read the target-conditional tables too.
#
# Which recipe provides what:
#   glib-2.0                    glib-2.0.pc, gobject-2.0.pc, gio-2.0.pc
#   gstreamer1.0                gstreamer-1.0.pc, gstreamer-base-1.0.pc
#   gstreamer1.0-plugins-base   gstreamer-app-1.0.pc, gstreamer-video-1.0.pc,
#                               gstreamer-sdp-1.0.pc
#   gstreamer1.0-plugins-bad    gstreamer-webrtc-1.0.pc -- which exists only
#                               because our bbappend turns the webrtc
#                               PACKAGECONFIG on; without it this would fail with
#                               the same shape of error as above.
DEPENDS += "glib-2.0 gstreamer1.0 gstreamer1.0-plugins-base gstreamer1.0-plugins-bad"

# Everything the board can use, expressed as the workspace minus four exclusions
# rather than a list of packages to include. That is the repo's own convention --
# its Cargo.toml sets default-members for exactly this reason, so "a new daemon is
# picked up without anybody remembering" -- and it means a crate added upstream
# gets built here without editing this line.
#
# The four exclusions:
#   xtask         release tooling that links the full minisign and can sign
#   test-support  test scaffolding
#   duckctl       the laptop-side client; cross-compiling a Bluetooth stack for a
#                 board that must never run it, as upstream's own comment says
#   duck-ether    not in upstream's default-members either
#
# Everything else builds, including mediad, tof, sounds, duck-detect and
# pet-detect.
CARGO_BUILD_FLAGS += "--workspace --exclude xtask --exclude test-support --exclude duckctl --exclude duck-ether"

# Both units hard-require this group; updater/systemd/sysusers.d/robot.conf exists
# upstream to create it at first boot. Creating it at image build time instead is
# deterministic and does not depend on sysusers ordering, so that file is not
# installed.
#
# btd runs as its own unprivileged user (btd/systemd/sysusers.d/btd.conf upstream:
# `u btd - "Robot BLE transport adapter" - -`) because it is the process parsing
# bytes from anyone in radio range. Its unit also declares
# SupplementaryGroups=robot bluetooth, and systemd refuses to start a unit whose
# supplementary groups do not exist -- so `bluetooth` is created here even though
# it grants nothing on this image: oe-core's bluez5 D-Bus policy has no
# group="bluetooth" rule, only `user="root"` and a permissive `context="default"`.
# The group exists to satisfy systemd, not to carry a permission.
USERADD_PACKAGES = "${PN}"
GROUPADD_PARAM:${PN} = "-r robot; -r bluetooth; -r btd; -r mediad; -r tofd; -r padd; -r i2c"
USERADD_PARAM:${PN} = "-r -g btd -d / -s /sbin/nologin -c 'Robot BLE transport adapter' btd; \
                       -r -g mediad -d / -s /sbin/nologin -c 'Robot media and WebRTC gateway' mediad; \
                       -r -g tofd -d / -s /sbin/nologin -c 'Robot ToF sensor daemon' tofd; \
                       -r -g padd -d / -s /sbin/nologin -c 'Robot gamepad intent client' padd"

# mediad, tofd and padd get their users and groups even though their units are
# not enabled here, because systemd refuses to start a unit whose User= or
# SupplementaryGroups= do not exist -- so creating them is what makes
# `systemctl enable mediad` work on a robot that does have the hardware, with no
# rebuild. The names and descriptions match upstream's own sysusers.d files.
#
# The supplementary groups each unit asks for:
#   mediad   video render robot     video/render already exist (udev-conf-rockchip
#                                   and the DRM rules put the VPU and render nodes
#                                   in them)
#   tofd     i2c robot              i2c does NOT exist on a stock image, hence the
#                                   groupadd above and the udev rule below
#   padd     input robot            input already exists and /dev/input/event* is
#                                   already root:input

# All seven units, all enabled. The robot is the common case: a board with
# servos, a camera, a ToF sensor and a gamepad should come up working, and
# needing to remember an enable command per daemon is how a robot ends up half
# running with nothing obviously wrong.
#
# The cost lands on a bare bring-up board, which has none of that hardware and
# where none of these three carries a Condition= to skip itself -- so mediad,
# tofd and padd fail there and `systemctl --failed` stops being a clean health
# signal. That is the right way round: on a bring-up board someone is already
# watching and
#
#     systemctl disable --now mediad tofd padd
#
# is a one-off, whereas on a robot the alternative is a daemon that silently
# never started.
#
# padd.service is assigned to ${PN}-padd rather than ${PN}, because the unit is
# packaged with its binary (see the FILES split below) and systemd.bbclass
# expects a service to be listed against the package that ships it.
# SYSTEMD_PACKAGES has to name both, since it otherwise defaults to ${PN} alone
# and padd.service would be installed but never enabled.
#
# robot-boot-check.service is the one that stays out entirely: its ExecStart is
# /usr/local/sbin/robot-boot-check, a path belonging to updaterd's release-tree
# layout, which this packaging deliberately does not use. Installing it would
# ship a unit that cannot work.
SYSTEMD_PACKAGES = "${PN} ${PN}-padd"
SYSTEMD_SERVICE:${PN} = "robotd.service updaterd.service configd.service btd.service mediad.service tofd.service"
SYSTEMD_SERVICE:${PN}-padd = "padd.service"
SYSTEMD_AUTO_ENABLE = "enable"

do_install:append() {
	# Install as a release tree, the layout updaterd owns:
	#
	#   /opt/robot/daemon/releases/<ver>/bin/...
	#   current → releases/<ver>     units point here
	#   golden  → releases/<ver>     never pruned; the rescue net reads it
	#
	# The units are installed verbatim, ExecStart untouched. That is the whole
	# point of this change. They already say /opt/robot/daemon/current/bin/, and
	# the previous packaging rewrote them to ${bindir} -- "option 2", distro-managed
	# binaries -- which quietly disabled the update system underneath: updaterd
	# would verify a signed release, extract it, swap `current`, report success,
	# and systemd would go on running the old binary from /usr/bin. An update that
	# installs correctly and changes nothing is the worst shape of failure.
	#
	# Atomicity is a rename(2) of the `current` symlink on one filesystem
	# (updater-design.md §7.1), so /opt/robot/daemon must not straddle a mount.
	install -d ${D}/opt/robot/daemon/releases/${PV}/bin
	for b in robotd updaterd robotctl configd btd mediad tofd sounds pet-detect duck-bench; do
		if [ -e ${D}${bindir}/$b ]; then
			mv ${D}${bindir}/$b ${D}/opt/robot/daemon/releases/${PV}/bin/
		fi
	done
	ln -snf releases/${PV} ${D}/opt/robot/daemon/current
	ln -snf releases/${PV} ${D}/opt/robot/daemon/golden

	# robotctl is what a person types, so keep it on PATH. A symlink through
	# `current` rather than a copy, so it follows the active release instead of
	# pinning the seed forever.
	install -d ${D}${bindir}
	ln -snf /opt/robot/daemon/current/bin/robotctl ${D}${bindir}/robotctl

	# Units, verbatim: their ExecStart already points into current/.
	install -d ${D}${systemd_system_unitdir}
	install -m 0644 ${S}/robotd/systemd/robotd.service ${D}${systemd_system_unitdir}/
	install -m 0644 ${S}/updater/systemd/updaterd.service ${D}${systemd_system_unitdir}/
	install -m 0644 ${S}/configd/systemd/configd.service ${D}${systemd_system_unitdir}/
	install -m 0644 ${S}/btd/systemd/btd.service ${D}${systemd_system_unitdir}/
	install -m 0644 ${S}/mediad/systemd/mediad.service ${D}${systemd_system_unitdir}/
	install -m 0644 ${S}/tof/systemd/tofd.service ${D}${systemd_system_unitdir}/
	install -m 0644 ${S}/padd/systemd/padd.service ${D}${systemd_system_unitdir}/

	# /dev/i2c-* arrives 0600 root:root, so tofd's SupplementaryGroups=i2c buys it
	# nothing without this -- the same shape of silent failure that left
	# /dev/mpp_service unopenable by a non-root mediad until udev-conf-rockchip
	# was installed. Matched on the kernel name, as meta-rockchip's own rule does.
	install -d ${D}${nonarch_base_libdir}/udev/rules.d
	install -m 0644 ${UNPACKDIR}/60-robot-i2c.rules \
		${D}${nonarch_base_libdir}/udev/rules.d/

	# Per-robot config. Never overwritten by an update upstream, so mark as conffiles.
	install -d ${D}${sysconfdir}/robot
	install -m 0644 ${S}/deploy/robotd.toml ${D}${sysconfdir}/robot/
	install -m 0644 ${S}/deploy/updater.toml ${D}${sysconfdir}/robot/

	# The shipped updater.toml names a placeholder repo, so a stock image polls
	# https://api.github.com/repos/ORG/duck-daemon/releases and logs a 404 every
	# check_interval (6h) forever. Point it at the real one. Upstream leaves it a
	# placeholder deliberately -- it is a per-deployment value, and provisioning
	# fills it in -- but an image *is* a deployment, so it belongs here.
	sed -i -e 's|^repo\( *\)= *"ORG/duck-daemon"|repo\1= "pollen-robotics/microduck_daemon"|' \
		${D}${sysconfdir}/robot/updater.toml
	grep -q 'repo.*pollen-robotics/microduck_daemon' ${D}${sysconfdir}/robot/updater.toml \
		|| bbfatal "updater.toml repo rewrite did not apply -- upstream format changed?"

	# minisign trust anchor. All three keys ship from the first image on purpose so
	# key rotation never needs a reflash.
	install -d ${D}${sysconfdir}/robot/trusted_keys
	install -m 0644 ${S}/deploy/trusted_keys/*.pub ${D}${sysconfdir}/robot/trusted_keys/

	# The dev key, staged but packaged separately (see ${PN}-devkey). It lives in
	# deploy/dev-key/, not deploy/trusted_keys/, so the glob above cannot pick it
	# up by accident -- which is upstream's design and worth preserving.
	install -m 0644 ${S}/deploy/dev-key/team.dev.pub ${D}${sysconfdir}/robot/trusted_keys/

	# Storage=persistent, SystemMaxUse=200M, ForwardToSyslog=no.
	install -d ${D}${sysconfdir}/systemd/journald.conf.d
	install -m 0644 ${S}/deploy/journald.conf.d/10-robot.conf \
		${D}${sysconfdir}/systemd/journald.conf.d/

	install -d ${D}${localstatedir}/lib/robot/updater
}

CONFFILES:${PN} = "${sysconfdir}/robot/robotd.toml ${sysconfdir}/robot/updater.toml"

# padd split out so a gamepad stack can be dropped without rebuilding, and so the
# libudev runtime dependency is visible rather than buried in the main package.
# Its unit travels with it: a padd.service in the main package would reference a
# binary that is not necessarily installed. PACKAGES =+ puts this package ahead
# of ${PN}, so it claims both before ${PN}'s broader globs do.
#
# Note the split now only governs the *seed*. updaterd replaces the whole release
# tree on update, so a later release reinstates padd whether or not this package
# was installed -- the orphan check (updater-design.md §7.3) keeps that honest by
# refusing a release that lacks a binary an installed unit execs.
PACKAGES =+ "${PN}-padd"
FILES:${PN}-padd = "/opt/robot/daemon/releases/${PV}/bin/padd ${systemd_system_unitdir}/padd.service"

FILES:${PN} += " \
    /opt/robot/daemon \
    ${bindir}/robotctl \
    ${systemd_system_unitdir} \
    ${nonarch_base_libdir}/udev/rules.d/60-robot-i2c.rules \
    ${sysconfdir}/robot \
    ${sysconfdir}/systemd/journald.conf.d/10-robot.conf \
    ${localstatedir}/lib/robot \
"

# The dev-board trust anchor, as its own package so it can only reach an image
# that asks for it by name. microduck-image-dev installs it; microduck-image
# cannot get it by accident.
#
# Both halves in one package, deliberately. install-dev.md is emphatic that the
# key and the flag are useless apart -- "either alone leaves a board that still
# refuses branch builds" -- and a board that looks provisioned but silently
# refuses `--ref` is a bad afternoon. Packaging them together makes half-applied
# impossible.
#
# What this buys: `robotctl update apply --ref main` and
# `provision-board.sh --ref BRANCH`. It does NOT make a dev board auto-track a
# branch. updaterd's newest_version skips anything GitHub flags as a prerelease
# and anything carrying a semver prerelease component, so the 6-hourly check
# still resolves stable on a dev board too; branch builds are always an explicit
# act. That filter is what keeps a customer robot off candidate builds, and it is
# not configurable away -- a board pointed at another channel by editing
# tag_prefix still refused, because "the prefix said where to look, and the
# prerelease filter refused to look".
#
# The `.dev.` infix in the filename is load-bearing: updaterd classifies a
# trusted key as a dev key by name, so team.dev.pub landing under any other name
# would be trusted as a *release* key.
PACKAGES =+ "${PN}-devkey"
FILES:${PN}-devkey = "${sysconfdir}/robot/trusted_keys/team.dev.pub"
RDEPENDS:${PN}-devkey += "${PN}"

pkg_postinst:${PN}-devkey() {
	cfg=$D${sysconfdir}/robot/updater.toml
	sed -i -e 's/^allow_dev_keys\( *\)= *false/allow_dev_keys\1= true/' "$cfg"
	if ! grep -q '^allow_dev_keys *= *true' "$cfg"; then
		echo "ERROR: could not enable allow_dev_keys in $cfg" >&2
		exit 1
	fi
}

# updaterd shells out to systemctl for its on_apply restart action, and every
# HTTPS fetch needs the system trust store.
#
# networkmanager and bluez5 are new with configd/btd, and declared rather than
# left to the image: configd drives NetworkManager over D-Bus and is useless
# without it, and btd talks to bluetoothd the same way. Both happen to be in
# microduck-image already, which is exactly how a missing dependency stays
# invisible until someone builds a leaner image -- the same way libnice reached
# the rootfs as a shlib side effect while webrtcbin had no ICE.
RDEPENDS:${PN} += "systemd ca-certificates networkmanager bluez5"

# duck-control, duck-detect and pet-detect all take ort 2.0.0-rc.11 with the
# "load-dynamic" feature, so libonnxruntime is dlopened at first use rather than
# linked. The workspace pins a floor of 1.23 (an earlier board shipped 1.20.1
# against an ort that panics below that) and a target of 1.28.0.
#
# Packaged as recipes-microduck/onnxruntime (Microsoft's prebuilt aarch64 tarball, the
# same one scripts/setup-board.sh fetches). RRECOMMENDS rather than RDEPENDS because
# robotd installs and starts fine without it and only fails when asked to walk, so a
# minimal image can drop it. ORT_DYLIB_PATH overrides the location.
RRECOMMENDS:${PN} += "onnxruntime"
