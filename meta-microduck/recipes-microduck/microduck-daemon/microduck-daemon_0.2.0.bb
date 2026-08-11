SUMMARY = "microduck robot control and update daemons"
DESCRIPTION = "robotd (50 Hz Dynamixel control loop), updaterd (signed, \
rollback-safe update engine), robotctl (local CLI) and padd (gamepad client). \
mediad and btd are designed but not built yet."
HOMEPAGE = "https://github.com/pollen-robotics/microduck_daemon"

# The repo declares license = "Apache-2.0" in its workspace Cargo.toml but ships no
# LICENSE file, so the checksum has to pin that declaration line instead.
#
# Worth fixing upstream: Apache-2.0 requires the licence text to accompany
# distributions, and this image is a distribution. Once a LICENSE file exists,
# repoint this at it -- a whole-file checksum is far less brittle than a line range.
LICENSE = "Apache-2.0"
LIC_FILES_CHKSUM = "file://Cargo.toml;beginline=28;endline=28;md5=2507bd5f0713f05419cd2f22fa06fe62"

inherit cargo cargo-update-recipe-crates pkgconfig systemd useradd

# Pinned to tag daemon-v0.2.0. Private repo, so the fetch needs an agent:
#   ssh -A coach
#   kas-container --ssh-agent build ...
SRC_URI = "git://git@github.com/pollen-robotics/microduck_daemon.git;protocol=ssh;branch=main"
SRCREV = "4dfc293d4ccde6aa53b88d7d71f9ed5c121e68cf"

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

# Only the shipped crates. Excludes xtask (release tooling that links the full
# minisign and can therefore sign) and test-support.
CARGO_BUILD_FLAGS += "--package robotd --package updater --package robotctl --package padd"

# Both units hard-require this group; updater/systemd/sysusers.d/robot.conf exists
# upstream to create it at first boot. Creating it at image build time instead is
# deterministic and does not depend on sysusers ordering, so that file is not
# installed.
USERADD_PACKAGES = "${PN}"
GROUPADD_PARAM:${PN} = "-r robot"

SYSTEMD_SERVICE:${PN} = "robotd.service updaterd.service"
SYSTEMD_AUTO_ENABLE = "enable"

do_install:append() {
	# Units, with ExecStart repointed from the self-updating release tree at
	# /opt/robot/daemon/current/bin to ${bindir}. This is the "option 2" packaging
	# choice: distro-managed binaries rather than updaterd's release-tree layout.
	install -d ${D}${systemd_system_unitdir}
	install -m 0644 ${S}/robotd/systemd/robotd.service ${D}${systemd_system_unitdir}/
	install -m 0644 ${S}/updater/systemd/updaterd.service ${D}${systemd_system_unitdir}/
	sed -i -e 's|/opt/robot/daemon/current/bin/|${bindir}/|g' \
		${D}${systemd_system_unitdir}/robotd.service \
		${D}${systemd_system_unitdir}/updaterd.service

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
RDEPENDS:${PN} += "systemd ca-certificates"

# duck-control depends on ort 2.0.0-rc.11 with the "load-dynamic" feature, so
# libonnxruntime is dlopened at first use rather than linked. The workspace pins a
# floor of 1.23 (an earlier board shipped 1.20.1 against an ort that panics below
# that) and a target of 1.28.0.
#
# Not packaged yet: robotd installs and starts fine without it and only fails when
# it tries to run the gait policy. Needs its own recipe -- either Microsoft's
# prebuilt aarch64 tarball, as scripts/setup-board.sh fetches, or a source build.
# ORT_DYLIB_PATH overrides the location.
