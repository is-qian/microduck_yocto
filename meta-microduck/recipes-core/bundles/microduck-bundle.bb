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

# ...and the hook enforces that: it refuses to install unless the card's `boot`
# holds the exact boot.img this bundle was built with. See the hook for why a
# `uname -r` match is not enough.
SRC_URI = "file://microduck-bundle-hook"
# Fetching anything makes do_populate_lic demand a checksum; bundle.bbclass only
# sets LICENSE ?= "MIT". Same line as microduck-data.bb.
LIC_FILES_CHKSUM = "file://${COREBASE}/meta/COPYING.MIT;md5=3da9cfbcb788c80a0384361b4de20420"
RAUC_BUNDLE_HOOKS[file] = "microduck-bundle-hook"
RAUC_BUNDLE_HOOKS[hooks] = "install-check"

# [meta.boot] size= and sha256= of that boot.img, which the hook reads as
# RAUC_META_BOOT_SIZE / RAUC_META_BOOT_SHA256. Filled in at build time, because
# boot.img exists only once the kernel has deployed it.
RAUC_META_SECTIONS = "boot"
do_configure[depends] += "virtual/kernel:do_deploy"
do_configure[prefuncs] += "microduck_boot_fingerprint"
python microduck_boot_fingerprint() {
    import hashlib
    with open(d.expand("${DEPLOY_DIR_IMAGE}/boot.img"), "rb") as f:
        img = f.read()
    d.setVarFlag("RAUC_META_boot", "size", str(len(img)))
    d.setVarFlag("RAUC_META_boot", "sha256", hashlib.sha256(img).hexdigest())
}

# The dev image, so the card built alongside and every updated slot keep a
# password-less root to test with. Switch to microduck-image before shipping.
RAUC_SLOT_rootfs = "microduck-image-dev"
# Required: bundle.bbclass defaults to the first IMAGE_FSTYPES entry, tar.zst here.
RAUC_SLOT_rootfs[fstype] = "ext4"
# Carries the running slot's machine-id over, so the journal on the data partition
# stays one directory across updates. See the hook.
RAUC_SLOT_rootfs[hooks] = "post-install"

# The signing pair lives in the build directory and never in this repo or the
# image; only the public CA is committed (recipes-core/rauc/files/ca.cert.pem).
# README.md has the one-time key generation. build-hfjob.sh writes the pair here.
RAUC_KEY_FILE ?= "${TOPDIR}/rauc-keys/development-1.key.pem"
RAUC_CERT_FILE ?= "${TOPDIR}/rauc-keys/development-1.cert.pem"
