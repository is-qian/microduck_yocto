SUMMARY = "microduck OS update bundle"
DESCRIPTION = "Signed RAUC bundle carrying one rootfs slot image."

# Bump on every shipped bundle: `rauc info` reports it as version=.
PV = "0.1.0"

inherit bundle

# Must equal compatible= in recipes-core/rauc/files/system.conf; RAUC refuses a
# bundle for another machine.
RAUC_BUNDLE_COMPATIBLE = "${MACHINE}"
RAUC_BUNDLE_FORMAT = "verity"

# Rootfs only. The kernel and DTB live in the shared `boot` partition, which RAUC
# does not update: a kernel or device-tree change still needs a reflash.
RAUC_BUNDLE_SLOTS = "rootfs"

# The dev image, so the card built alongside and every updated slot keep a
# password-less root to test with. Switch to microduck-image before shipping.
RAUC_SLOT_rootfs = "microduck-image-dev"
# Required: bundle.bbclass defaults to the first IMAGE_FSTYPES entry, tar.zst here.
RAUC_SLOT_rootfs[fstype] = "ext4"

# The signing pair lives in the build directory and never in this repo or the
# image; only the public CA is committed (recipes-core/rauc/files/ca.cert.pem).
# README.md has the one-time key generation. build-hfjob.sh writes the pair here.
RAUC_KEY_FILE ?= "${TOPDIR}/rauc-keys/development-1.key.pem"
RAUC_CERT_FILE ?= "${TOPDIR}/rauc-keys/development-1.cert.pem"
