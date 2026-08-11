SUMMARY = "GStreamer plugins written in Rust: webrtcsink/webrtcsrc and rtp"
DESCRIPTION = "The plugin reachy_mini_os ships as an 82.7 MB Git-LFS blob with no \
recipe, no source pin and four environment variables duplicated into every systemd \
unit to locate it. Built here from a pinned SRCREV, which is the clearest single \
argument for the whole Yocto trade."
HOMEPAGE = "https://gitlab.freedesktop.org/gstreamer/gst-plugins-rs"

LICENSE = "MPL-2.0"
LIC_FILES_CHKSUM = "file://LICENSE-MPL-2.0;md5=815ca599c9df247a0c7f619bab123dad"

# Tag gstreamer-1.28.2 -- note the prefix, the tags are not bare version numbers.
# Pinned to match our GStreamer core exactly (1.28.2) rather than taking the newest
# 1.28.x and assuming forward compatibility.
SRC_URI = "git://gitlab.freedesktop.org/gstreamer/gst-plugins-rs.git;protocol=https;nobranch=1"
SRCREV = "08eeb63ff2f74405447089f20c48868478a6076d"

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
