SUMMARY = "Persistent data partition at /var/lib/robot"
DESCRIPTION = "Mounts the data partition where the daemon keeps what a user adds, \
and keeps NetworkManager's wifi profiles there too, so both survive a RAUC slot \
switch."
LICENSE = "MIT"
LIC_FILES_CHKSUM = "file://${COREBASE}/meta/COPYING.MIT;md5=3da9cfbcb788c80a0384361b4de20420"

SRC_URI = " \
    file://var-lib-robot.mount \
    file://90-microduck-keyfile.conf \
    file://microduck-data.tmpfiles \
"

inherit systemd

# The unit name must be the mount point with / -> -.
SYSTEMD_SERVICE:${PN} = "var-lib-robot.mount"
SYSTEMD_AUTO_ENABLE = "enable"

do_install() {
	install -d ${D}${systemd_system_unitdir}
	install -m 0644 ${UNPACKDIR}/var-lib-robot.mount ${D}${systemd_system_unitdir}/

	install -d ${D}${nonarch_libdir}/NetworkManager/conf.d
	install -m 0644 ${UNPACKDIR}/90-microduck-keyfile.conf ${D}${nonarch_libdir}/NetworkManager/conf.d/
	install -d ${D}${nonarch_libdir}/tmpfiles.d
	install -m 0644 ${UNPACKDIR}/microduck-data.tmpfiles ${D}${nonarch_libdir}/tmpfiles.d/microduck-data.conf

	# In the image too, so the wks seeds it into a fresh data partition and
	# utils/seed-wifi.sh finds it before first boot.
	install -d -m 0700 ${D}${localstatedir}/lib/robot/network
}

# microduck-daemon ships /var/lib/robot itself; this adds one subdirectory.
RDEPENDS:${PN} += "microduck-daemon networkmanager"

FILES:${PN} = " \
    ${systemd_system_unitdir}/var-lib-robot.mount \
    ${nonarch_libdir}/NetworkManager/conf.d/90-microduck-keyfile.conf \
    ${nonarch_libdir}/tmpfiles.d/microduck-data.conf \
    ${localstatedir}/lib/robot/network \
"
