SUMMARY = "microduck OS update bundle -- shipping image"
DESCRIPTION = "microduck-bundle with the shipping image in its slot: the release \
candidate. CI signs it with the development key; promote-os re-signs one with the \
production key, and only that can reach a robot that runs microduck-image."

require recipes-core/bundles/microduck-bundle.bb

RAUC_SLOT_rootfs = "microduck-image"

# Its own build id, one second after the dev bundle's from the same run. CI builds
# both in one bitbake invocation, so both would otherwise carry the same DATETIME,
# and microduck-osupdate and the bucket key everything on that id: a dev board
# running dev build X would refuse candidate X as "already running".
# shortcut: two builds started within the same second collide, as two plain
# DATETIMEs already could; give the id a variant field if builds ever run that close.
def microduck_next_second(d):
    import calendar
    import time
    dt = d.getVar('DATETIME')
    # Task signatures are computed with a placeholder DATETIME ('12341234'); pass
    # anything that is not a 14-digit timestamp through.
    if len(dt) != 14 or not dt.isdigit():
        return dt
    t = calendar.timegm(time.strptime(dt, '%Y%m%d%H%M%S'))
    return time.strftime('%Y%m%d%H%M%S', time.gmtime(t + 1))
microduck_next_second[vardepsexclude] = "DATETIME"

MICRODUCK_BUNDLE_BUILD = "${@microduck_next_second(d)}"
MICRODUCK_BUNDLE_BUILD[vardepsexclude] = "DATETIME"
RAUC_BUNDLE_BUILD = "${MICRODUCK_BUNDLE_BUILD}"
# scripts/publish-os.sh reads the build id from the file name.
BUNDLE_NAME = "${BUNDLE_BASENAME}-${MACHINE}-${MICRODUCK_BUNDLE_BUILD}"
