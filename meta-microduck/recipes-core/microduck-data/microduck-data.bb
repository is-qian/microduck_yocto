SUMMARY = "Mount the persistent data partition at /var/lib/robot"
DESCRIPTION = "One systemd mount unit. The partition is created and formatted \
by the A/B wks; this only puts it where the daemon already keeps everything a \
user adds -- policies, sounds, config -- so it survives a RAUC slot switch."
LICENSE = "MIT"
LIC_FILES_CHKSUM = "file://${COREBASE}/meta/COPYING.MIT;md5=3da9cfbcb788c80a0384361b4de20420"

SRC_URI = "file://var-lib-robot.mount"

inherit systemd

# A mount unit's name is its mount point with / -> -, so this must be exactly
# var-lib-robot.mount or systemd refuses to load it. Not a name we chose.
SYSTEMD_SERVICE:${PN} = "var-lib-robot.mount"
SYSTEMD_AUTO_ENABLE = "enable"

do_install() {
	install -d ${D}${systemd_system_unitdir}
	install -m 0644 ${UNPACKDIR}/var-lib-robot.mount ${D}${systemd_system_unitdir}/
}

# The mount point has to exist before the mount, and the daemon package already
# ships /var/lib/robot with the two directories StateDirectory= would otherwise
# create. Nothing to install here beyond the unit; we do not own the directory.
RDEPENDS:${PN} += "microduck-daemon"

FILES:${PN} = "${systemd_system_unitdir}/var-lib-robot.mount"
