SUMMARY = "microduck OS update bundle"
DESCRIPTION = "Signed RAUC bundle carrying one rootfs slot image."

# Bump on every shipped bundle: this is what `rauc info` reports as version=,
# and what a future system.conf min-bundle-version= would compare against.
PV = "0.1.0"

inherit bundle

# Must equal 'compatible' in recipes-core/rauc/files/system.conf, which is
# @MACHINE@ substituted at install time. RAUC refuses to install otherwise, and
# that refusal is the point -- it is what stops a zero3 bundle reaching the
# custom PCB.
RAUC_BUNDLE_COMPATIBLE = "${MACHINE}"
RAUC_BUNDLE_FORMAT = "verity"

# Stage 1 ships the rootfs only. Kernel and DTB live inside boot.img on a raw
# partition, and nothing can boot slot B yet, so adding boot_a/boot_b slots here
# would produce a bundle that installs and changes nothing.
RAUC_BUNDLE_SLOTS = "rootfs"

# microduck-image, not microduck-image-dev: this is the artifact that ships, and
# the dev image carries a password-less root login.
RAUC_SLOT_rootfs = "microduck-image"

# Required, not decoration. bundle.bbclass defaults the extension to the first
# entry of IMAGE_FSTYPES, which here is oe-core's "tar.zst" (rockchip-image
# appends " ext4 wic" after it), so without this the class looks for a tarball.
RAUC_SLOT_rootfs[fstype] = "ext4"

# The signing key must never enter this repo or the image -- same rule as the
# fleet token. It lives in the build directory, which kas-container already
# bind-mounts and .gitignore already excludes, so no extra mount and no chance
# of committing it:
#
#   cd $KAS_BUILD_DIR/rauc-keys && ../../meta-rauc/scripts/openssl-ca.sh
#   cp openssl-ca/dev/development-1.cert.pem openssl-ca/dev/private/development-1.key.pem .
#
# and the matching public CA certificate is copied *into* the repo, once, at
# meta-microduck/recipes-core/rauc/files/ca.cert.pem -- see README.md.
#
# In CI the key becomes an org-scoped GitHub secret written to this same path
# before the build; nothing else changes.
MICRODUCK_RAUC_KEYDIR ??= "${TOPDIR}/rauc-keys"
RAUC_KEY_FILE ?= "${MICRODUCK_RAUC_KEYDIR}/development-1.key.pem"
RAUC_CERT_FILE ?= "${MICRODUCK_RAUC_KEYDIR}/development-1.cert.pem"

# Fail here rather than at the end of `rauc bundle`, which is roughly an hour
# into a cold build.
do_bundle[prefuncs] += "check_rauc_signing_key"
check_rauc_signing_key() {
	for f in "${RAUC_KEY_FILE}" "${RAUC_CERT_FILE}"; do
		if [ ! -r "$f" ]; then
			bbfatal "RAUC signing material missing: $f\n" \
				"Generate it once with meta-rauc's script (it is NOT in this repo):\n" \
				"  mkdir -p ${MICRODUCK_RAUC_KEYDIR} && cd ${MICRODUCK_RAUC_KEYDIR}\n" \
				"  \$KAS_WORK_DIR/meta-rauc/scripts/openssl-ca.sh\n" \
				"  cp openssl-ca/dev/development-1.cert.pem .\n" \
				"  cp openssl-ca/dev/private/development-1.key.pem .\n" \
				"Then copy openssl-ca/dev/ca.cert.pem into" \
				"meta-microduck/recipes-core/rauc/files/ so the image trusts it."
		fi
	done
}
