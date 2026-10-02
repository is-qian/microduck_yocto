# Grow / to fill its slot at boot. `rauc install` writes the bundle's ext4 image raw
# into a 2 GiB slot, so the filesystem stays the image's size (~740 MB, ~90 MB
# free), and the second daemon update after a slot switch failed updaterd's disk
# preflight (2026-10-02). systemd-fstab-generator turns this option into
# systemd-growfs-root.service, which resizes the mounted ext4 online before
# local-fs.target. A no-op on a slot that already fills its partition, e.g. a
# freshly flashed slot A.
do_install:append() {
	sed -i 's|^\(/dev/root[[:space:]]\+/[[:space:]]\+auto[[:space:]]\+\)defaults[[:space:]]|\1defaults,x-systemd.growfs |' ${D}${sysconfdir}/fstab
	grep -q '^/dev/root[[:space:]].*x-systemd\.growfs' ${D}${sysconfdir}/fstab || bbfatal "fstab root line changed upstream; x-systemd.growfs not applied"
}
