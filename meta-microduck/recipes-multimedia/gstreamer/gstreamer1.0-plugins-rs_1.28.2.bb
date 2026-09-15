SUMMARY = "GStreamer plugins written in Rust: webrtcsink/webrtcsrc and rtp"
DESCRIPTION = "The plugin reachy_mini_os ships as an 82.7 MB Git-LFS blob with no \
recipe, no source pin and four environment variables duplicated into every systemd \
unit to locate it. Built here from a pinned SRCREV, which is the clearest single \
argument for the whole Yocto trade."
HOMEPAGE = "https://gitlab.freedesktop.org/gstreamer/gst-plugins-rs"

LICENSE = "MPL-2.0"
LIC_FILES_CHKSUM = "file://LICENSE-MPL-2.0;md5=815ca599c9df247a0c7f619bab123dad"

FILESEXTRAPATHS:prepend := "${THISDIR}/files:"

# Two repositories, and the second one is the whole difficulty of this recipe.
#
# gst-plugins-rs takes its GStreamer bindings from git rather than crates.io:
#
#   gst = { package = "gstreamer",
#           git = "https://gitlab.freedesktop.org/gstreamer/gstreamer-rs",
#           branch = "0.25", version = "0.25.0" }
#
# and cargo-update-recipe-crates only vendors crates.io entries --
#
#   crates_candidates = list(filter(lambda c: 'crates.io' in c.get('source', ''), ...))
#
# -- so none of those ~20 crates reaches the generated crates.inc, and cargo under
# --frozen cannot resolve them. Every 1.28.x tag (1.28.0 .. 1.28.6) does this, so no
# version sidesteps it. It is why no upstream Yocto recipe for gst-plugins-rs exists.
#
# The fix is to fetch gstreamer-rs ourselves and point cargo at the checkout with a
# [patch] section; see microduck_gstreamer_rs_patch_paths below for why the stock
# mechanism cannot do it.
#
# SRCREV_bindings is NOT "whatever branch 0.25 is today" -- it is the exact commit
# gst-plugins-rs's own Cargo.lock pins:
#
#   source = "git+https://gitlab.freedesktop.org/gstreamer/gstreamer-rs?branch=0.25#f49606f2..."
#
# Taking the branch tip instead would build a combination upstream never resolved,
# and would change under us between two builds of the same SRCREV_plugins.
SRC_URI = "git://gitlab.freedesktop.org/gstreamer/gst-plugins-rs.git;protocol=https;nobranch=1;name=plugins \
           git://gitlab.freedesktop.org/gstreamer/gstreamer-rs.git;protocol=https;nobranch=1;name=bindings;destsuffix=gstreamer-rs \
           file://0001-webrtcsink-no-converter-for-mpph264enc.patch \
"

# Tag gstreamer-1.28.2 -- note the prefix, the tags are not bare version numbers.
# Pinned to match our GStreamer core exactly (1.28.2) rather than taking the newest
# 1.28.x and assuming forward compatibility.
SRCREV_plugins = "08eeb63ff2f74405447089f20c48868478a6076d"
SRCREV_bindings = "f49606f22450e992556221e5c9dfeb628b8b54b0"
SRCREV_FORMAT = "plugins_bindings"

# No S assignment. oe-core's bitbake.conf sets S = "${UNPACKDIR}/${BP}" and
# BB_GIT_DEFAULT_DESTSUFFIX = "${BP}", so the gst-plugins-rs checkout -- the one
# without an explicit destsuffix -- already lands exactly there. Setting
# S = "${UNPACKDIR}/git" by hand is now a hard do_unpack error rather than a
# harmless leftover, which is how this recipe failed its first build.
#
# gstreamer-rs does not collide with it: its destsuffix puts it beside S, at
# ${UNPACKDIR}/gstreamer-rs, which is where the postfunc below looks for it.

require ${BPN}-crates.inc

# cargo_c drives `cargo cbuild`/`cargo cinstall` and pulls cargo-c-native itself.
# net/webrtc/Cargo.toml already carries [package.metadata.capi] with
# install_subdir = "gstreamer-1.0", so cinstall lands the plugins in the right place.
inherit cargo_c cargo-update-recipe-crates

# gstreamer-webrtc-1.0 and gstreamer-sdp-1.0 live in plugins-bad, which is also where
# the webrtcbin element comes from -- see the bbappend enabling PACKAGECONFIG webrtc.
DEPENDS += "gstreamer1.0 gstreamer1.0-plugins-base gstreamer1.0-plugins-bad"

# Two of 65 workspace members. Building the whole repo would be hundreds of crates of
# plugins nothing here needs. gst-plugin-rtp comes along because the webrtc plugin's
# payloading path uses it.
CARGO_BUILD_FLAGS += "--package gst-plugin-webrtc --package gst-plugin-rtp"

# Point cargo at the gstreamer-rs checkout, one [patch] entry per crate.
#
# cargo_common_do_patch_paths already does this for git dependencies, but it can only
# express *one crate per repository*: it reads `name` and `destsuffix` off each SRC_URI
# entry and emits `name = { path = <UNPACKDIR>/<destsuffix> }`. gstreamer-rs is one
# repository holding some 39 crates -- gstreamer, gstreamer-app, gstreamer-video,
# gstreamer-webrtc and a -sys crate beside most of them -- each in its own
# subdirectory. One entry cannot name them, and adding the repository 39 times would
# clone it 39 times and still point every entry at the repository root.
#
# So the class writes one useless entry (`bindings`, from the name above) and this
# function replaces it. The name is not wasted: giving that SRC_URI entry both `name`
# and `destsuffix` is exactly what makes the class do the other half of its job, which
# we do want -- stripping the `source = "git..."` lines out of Cargo.lock so --frozen
# still passes, after keeping a .orig copy we read below.
#
# The crate list is derived, never hand-maintained: the wanted set comes from the
# lockfile, the paths come from walking the checkout. A layout change upstream is then
# a build failure naming the missing crates, rather than a patch that silently does
# not apply -- which is the failure this area has already produced once, in the v3
# binary release whose MANIFEST claims a patch its .so does not contain.
python microduck_gstreamer_rs_patch_paths() {
    import os
    import re

    repo = "https://gitlab.freedesktop.org/gstreamer/gstreamer-rs"
    root = os.path.join(d.getVar("UNPACKDIR"), "gstreamer-rs")
    config = os.path.join(d.getVar("CARGO_HOME"), "config.toml")

    if not os.path.isdir(root):
        bb.fatal("gstreamer-rs checkout is not at %s -- destsuffix changed?" % root)

    # Read the lockfile as it was *before* cargo_common stripped the git sources.
    lock = d.getVar("CARGO_LOCK_PATH")
    orig = lock + ".orig"
    source = orig if os.path.exists(orig) else lock

    wanted = set()
    name = None
    with open(source) as f:
        for line in f:
            line = line.strip()
            match = re.match(r'name = "([^"]+)"', line)
            if match:
                name = match.group(1)
            elif name and line.startswith('source = "git+') and "gstreamer-rs" in line:
                wanted.add(name)

    if not wanted:
        bb.fatal("no gstreamer-rs git packages in %s. If upstream moved these crates "
                 "to crates.io this whole function is obsolete and crates.inc covers "
                 "them; if the lockfile moved, this recipe is reading the wrong file."
                 % source)

    # Where each crate actually lives. Walk rather than assume: the -sys crates sit
    # under their parent (gstreamer/sys), and the GL ones nest further still.
    found = {}
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [x for x in dirnames if x not in (".git", "target")]
        if "Cargo.toml" not in filenames:
            continue
        package, section = None, None
        with open(os.path.join(dirpath, "Cargo.toml")) as f:
            for line in f:
                stripped = line.strip()
                if stripped.startswith("["):
                    section = stripped
                    continue
                if section == "[package]":
                    match = re.match(r'name\s*=\s*"([^"]+)"', stripped)
                    if match:
                        package = match.group(1)
                        break
        if package:
            found[package] = dirpath

    missing = sorted(wanted - set(found))
    if missing:
        bb.fatal("gstreamer-rs %s provides no directory for: %s. The two SRCREVs have "
                 "to move together -- SRCREV_bindings must stay the commit that "
                 "SRCREV_plugins' Cargo.lock names."
                 % (d.getVar("SRCREV_bindings"), " ".join(missing)))

    # Drop any gstreamer-rs [patch] block already in the file (the class's useless
    # one), keep everything else, then append the real thing. "gstreamer-rs" is not a
    # substring of "gst-plugins-rs", so this cannot eat an unrelated section.
    keep, skipping = [], False
    if os.path.exists(config):
        with open(config) as f:
            for line in f:
                if line.startswith("[patch."):
                    skipping = "gstreamer-rs" in line
                elif line.startswith("["):
                    skipping = False
                if not skipping:
                    keep.append(line)

    with open(config, "w") as f:
        f.writelines(keep)
        f.write('\n[patch."%s"]\n' % repo)
        for package in sorted(wanted):
            f.write('%s = { path = "%s" }\n' % (package, found[package]))

    bb.note("gstreamer-rs: %d crates patched to %s" % (len(wanted), root))
}
do_configure[postfuncs] += "microduck_gstreamer_rs_patch_paths"

# GStreamer plugins are unversioned .so files inside a plugin directory, so they
# belong in the runtime package and dev-so has to be skipped.
FILES:${PN} += "${libdir}/gstreamer-1.0/*.so"
FILES:${PN}-dev += "${libdir}/gstreamer-1.0/*.a ${libdir}/pkgconfig"
INSANE_SKIP:${PN} += "dev-so"

# webrtcsink builds a pipeline around webrtcbin at runtime; without this the plugin
# loads and then fails at pipeline construction.
RDEPENDS:${PN} += "gstreamer1.0-plugins-bad-webrtc"

# Cargo.lock has 1139 crates, so crates.inc is large and the first fetch is long.
# Regenerate after a SRCREV bump with:
#   ./scripts/build-coach.sh shell -c "bitbake -c update_crates gstreamer1.0-plugins-rs"
