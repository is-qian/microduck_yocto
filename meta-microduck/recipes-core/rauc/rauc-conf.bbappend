# Our system.conf and CA certificate win over meta-rauc's examples.
FILESEXTRAPATHS:prepend := "${THISDIR}/files:"

# system.conf names machine-specific devices, so this package is per-machine.
PACKAGE_ARCH = "${MACHINE_ARCH}"

# @MACHINE@ rather than a literal, because 'compatible' has to match
# RAUC_BUNDLE_COMPATIBLE in recipes-core/bundles/microduck-bundle.bb or RAUC
# refuses to install, and the custom PCB will be a second machine conf.
do_install:prepend() {
	sed -i -e 's!@MACHINE@!${MACHINE}!g' ${UNPACKDIR}/system.conf
}
