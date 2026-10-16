# The operator account (microduck-image.bb) is in group sudo: let that group sudo,
# with its password. In a package rather than written into the rootfs, so it shows
# in buildhistory and the SBOM. sudo-lib owns ${sysconfdir}, so it carries this too.
FILESEXTRAPATHS:prepend:microduck := "${THISDIR}/files:"
SRC_URI:append:microduck = " file://10-sudo-group"

do_install:append:microduck() {
	install -d -m 0750 ${D}${sysconfdir}/sudoers.d
	install -m 0440 ${UNPACKDIR}/10-sudo-group ${D}${sysconfdir}/sudoers.d/
}
