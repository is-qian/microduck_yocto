SUMMARY = "Mount the persistent data partition at /var/lib/robot"
DESCRIPTION = "The data partition's mount unit, plus what has to live on it that \
is not the daemon's: NetworkManager's connection profiles. The partition is \
created and formatted by the A/B wks; this puts it where the daemon already keeps \
everything a user adds -- policies, sounds, config -- so it survives a RAUC slot \
switch, and moves wifi credentials there so they survive one too."
LICENSE = "MIT"
LIC_FILES_CHKSUM = "file://${COREBASE}/meta/COPYING.MIT;md5=3da9cfbcb788c80a0384361b4de20420"

SRC_URI = " \
    file://var-lib-robot.mount \
    file://90-microduck-keyfile.conf \
    file://microduck-data.tmpfiles \
"

inherit systemd

# A mount unit's name is its mount point with / -> -, so this must be exactly
# var-lib-robot.mount or systemd refuses to load it. Not a name we chose.
SYSTEMD_SERVICE:${PN} = "var-lib-robot.mount"
SYSTEMD_AUTO_ENABLE = "enable"

do_install() {
	install -d ${D}${systemd_system_unitdir}
	install -m 0644 ${UNPACKDIR}/var-lib-robot.mount ${D}${systemd_system_unitdir}/

	# Wifi credentials on the data partition, not in the slot. The two files'
	# comments carry the why: the first A->B update on hardware produced a slot
	# that booted fine and had no network.
	install -d ${D}${nonarch_libdir}/NetworkManager/conf.d
	install -m 0644 ${UNPACKDIR}/90-microduck-keyfile.conf ${D}${nonarch_libdir}/NetworkManager/conf.d/
	install -d ${D}${nonarch_libdir}/tmpfiles.d
	install -m 0644 ${UNPACKDIR}/microduck-data.tmpfiles ${D}${nonarch_libdir}/tmpfiles.d/microduck-data.conf

	# Also in the image itself, so the wks seeds it into a fresh data partition
	# (--change-directory=var/lib/robot) and utils/seed-wifi.sh has somewhere to
	# write a keyfile before the first boot. tmpfiles covers older partitions.
	install -d -m 0700 ${D}${localstatedir}/lib/robot/network
}

# The mount point has to exist before the mount, and the daemon package already
# ships /var/lib/robot with the two directories StateDirectory= would otherwise
# create. We add one subdirectory under it and nothing else; the directory itself
# is the daemon's. networkmanager because the conf.d snippet is for it -- the
# daemon pulls it in too, but a dependency this file needs belongs here.
RDEPENDS:${PN} += "microduck-daemon networkmanager"

FILES:${PN} = " \
    ${systemd_system_unitdir}/var-lib-robot.mount \
    ${nonarch_libdir}/NetworkManager/conf.d/90-microduck-keyfile.conf \
    ${nonarch_libdir}/tmpfiles.d/microduck-data.conf \
    ${localstatedir}/lib/robot/network \
"
