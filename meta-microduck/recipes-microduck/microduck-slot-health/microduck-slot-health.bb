SUMMARY = "Gate RAUC's good-boot marking on the robot's daemons being healthy"
DESCRIPTION = "A oneshot in boot-complete.target, which rauc-mark-good.service \
requires: the slot is marked good only if the daemons came up. See the script."
LICENSE = "MIT"
LIC_FILES_CHKSUM = "file://${COMMON_LICENSE_DIR}/MIT;md5=0835ade698e0bcf8506ecda2f7b4f302"

# OS policy, not daemon code: its own recipe, so changing it does not rebuild the
# daemon. microduck-daemon RDEPENDS on it -- the script checks the daemon's units
# and fails without them, which would leave every slot unmarked.
SRC_URI = " \
    file://microduck-slot-health \
    file://microduck-slot-health.service \
"
S = "${UNPACKDIR}"

inherit allarch systemd

SYSTEMD_SERVICE:${PN} = "microduck-slot-health.service"
SYSTEMD_AUTO_ENABLE = "enable"

do_install() {
	install -d ${D}${systemd_system_unitdir} ${D}${libexecdir}
	install -m 0644 ${UNPACKDIR}/microduck-slot-health.service ${D}${systemd_system_unitdir}/
	install -m 0755 ${UNPACKDIR}/microduck-slot-health ${D}${libexecdir}/
}

FILES:${PN} += "${libexecdir}/microduck-slot-health"
RDEPENDS:${PN} = "rauc systemd"
