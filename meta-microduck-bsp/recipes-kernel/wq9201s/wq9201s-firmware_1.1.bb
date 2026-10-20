SUMMARY = "Firmware and board settings for the WuQi WQ9201S"
DESCRIPTION = "wq9201_*.bin (wifi, BT, dtop, PHY and OEM images) and \
wq_wlan_settings.ini, from Seeed's wq9201s-wifi-bt-dkms package."
# Seeed's debian/copyright: "Proprietary firmware binaries and factory default
# settings; redistributed with vendor permission for use with WQ9201S modules."
# That file is the licence text, checksummed whole like the driver's.
LICENSE = "WuQi-firmware"
NO_GENERIC_LICENSE[WuQi-firmware] = "debian/copyright"
LIC_FILES_CHKSUM = "file://debian/copyright;md5=3727876b7206ead678f0a134cddb542b"

# The same source as wq9201s_2.2.3.94.bb: Seeed's public repo, one pinned commit.
SRC_URI = "git://github.com/Seeed-Studio/seeed_armbian_extension.git;protocol=https;branch=deb_source"
SRCREV = "1f4bd1cbf6e844291a54ca55816c1ac827b6bd02"
S = "${UNPACKDIR}/${BP}/wq9201s-wifi-bt-dkms"

inherit allarch

# configs/wq_wlan_settings.ini is Seeed's board file: ant_mode=2 (single antenna)
# and coex_ant_mode=2 (BT shares the wifi antenna), which is this board -- one
# U.FL connector.
do_install() {
	install -d ${D}${nonarch_base_libdir}/firmware
	install -m 0644 ${S}/firmwares/wq9201_*.bin ${S}/configs/wq_wlan_settings.ini \
		${D}${nonarch_base_libdir}/firmware/
}

FILES:${PN} = "${nonarch_base_libdir}/firmware"
