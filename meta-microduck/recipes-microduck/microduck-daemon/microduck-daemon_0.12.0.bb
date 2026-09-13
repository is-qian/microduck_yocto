SUMMARY = "microduck robot control and update daemons"
DESCRIPTION = "robotd (50 Hz Dynamixel control loop), updaterd (signed, \
rollback-safe update engine), robotctl (local CLI), padd (gamepad client), \
configd (wifi and robot identity, drives NetworkManager), btd (the BLE \
transport that fronts configd, so a robot with no network can be provisioned \
from a phone), mediad, tofd, sounds and the duck/pet detectors. Only robotd, \
updaterd, configd and btd have their units enabled; the rest are built and \
installed but wait on hardware that is not fitted."
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

# microduck_daemon is a public repo, so this fetches anonymously. The recipe used
# to note that it was private and needed `ssh -A coach`; that stopped being true,
# and bitbake falls through to oe-core's https mirror rewrite regardless.
SRC_URI = "git://git@github.com/pollen-robotics/microduck_daemon.git;protocol=ssh;branch=main;name=daemon \
           git://github.com/pollen-robotics/bmi088-rs;protocol=https;nobranch=1;name=bmi088;destsuffix=bmi088-rs \
"
SRCREV_daemon = "6507d2e960417aaa4ecd38eccf59b2dcf586ecd2"
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
GROUPADD_PARAM:${PN} = "-r robot; -r bluetooth; -r btd"
USERADD_PARAM:${PN} = "-r -g btd -d / -s /sbin/nologin -c 'Robot BLE transport adapter' btd"

SYSTEMD_SERVICE:${PN} = "robotd.service updaterd.service configd.service btd.service"
SYSTEMD_AUTO_ENABLE = "enable"

# Four units, not eight. Upstream also ships mediad.service, tofd.service,
# padd.service and robot-boot-check.service, and all four are deliberately left
# out even though their binaries are built and installed:
#
#   mediad   needs a camera. None is fitted (the CSI module on order is an
#            IMX219; the RPi Cam 3 that was tried has no driver in this kernel).
#   tofd     needs the time-of-flight sensor, not fitted.
#   padd     needs a paired gamepad.
#   robot-boot-check  its ExecStart is /usr/local/sbin/robot-boot-check, a path
#            this packaging never installs -- it belongs to updaterd's release
#            tree layout, which is the layout we deliberately do not use.
#
# None of the first three carries a Condition= that would make it skip itself on
# hardware that is absent, and all are WantedBy=multi-user.target. Enabling them
# would mean three permanently failing units on every boot, which costs more than
# it gives: `systemctl --failed` stops being a usable health signal, and that is
# the check this bring-up has leaned on repeatedly.
#
# The binaries are present, so any of them can be run by hand or enabled with
# `systemctl enable` once its hardware arrives. Their users would need creating
# at that point too -- each runs as its own (mediad, tofd, padd).

do_install:append() {
	# Units, with ExecStart repointed from the self-updating release tree at
	# /opt/robot/daemon/current/bin to ${bindir}. This is the "option 2" packaging
	# choice: distro-managed binaries rather than updaterd's release-tree layout.
	install -d ${D}${systemd_system_unitdir}
	install -m 0644 ${S}/robotd/systemd/robotd.service ${D}${systemd_system_unitdir}/
	install -m 0644 ${S}/updater/systemd/updaterd.service ${D}${systemd_system_unitdir}/
	install -m 0644 ${S}/configd/systemd/configd.service ${D}${systemd_system_unitdir}/
	install -m 0644 ${S}/btd/systemd/btd.service ${D}${systemd_system_unitdir}/
	sed -i -e 's|/opt/robot/daemon/current/bin/|${bindir}/|g' \
		${D}${systemd_system_unitdir}/robotd.service \
		${D}${systemd_system_unitdir}/updaterd.service \
		${D}${systemd_system_unitdir}/configd.service \
		${D}${systemd_system_unitdir}/btd.service

	# Per-robot config. Never overwritten by an update upstream, so mark as conffiles.
	install -d ${D}${sysconfdir}/robot
	install -m 0644 ${S}/deploy/robotd.toml ${D}${sysconfdir}/robot/
	install -m 0644 ${S}/deploy/updater.toml ${D}${sysconfdir}/robot/

	# minisign trust anchor. All three keys ship from the first image on purpose so
	# key rotation never needs a reflash.
	install -d ${D}${sysconfdir}/robot/trusted_keys
	install -m 0644 ${S}/deploy/trusted_keys/*.pub ${D}${sysconfdir}/robot/trusted_keys/

	# Storage=persistent, SystemMaxUse=200M, ForwardToSyslog=no.
	install -d ${D}${sysconfdir}/systemd/journald.conf.d
	install -m 0644 ${S}/deploy/journald.conf.d/10-robot.conf \
		${D}${sysconfdir}/systemd/journald.conf.d/

	install -d ${D}${localstatedir}/lib/robot/updater
}

CONFFILES:${PN} = "${sysconfdir}/robot/robotd.toml ${sysconfdir}/robot/updater.toml"

# padd split out so a gamepad stack can be dropped without rebuilding, and so the
# libudev runtime dependency is visible rather than buried in the main package.
PACKAGES =+ "${PN}-padd"
FILES:${PN}-padd = "${bindir}/padd"

FILES:${PN} += " \
    ${systemd_system_unitdir} \
    ${sysconfdir}/robot \
    ${sysconfdir}/systemd/journald.conf.d/10-robot.conf \
    ${localstatedir}/lib/robot \
"

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
