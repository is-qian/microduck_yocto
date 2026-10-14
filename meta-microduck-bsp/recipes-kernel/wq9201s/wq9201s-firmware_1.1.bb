SUMMARY = "Firmware and board settings for the WuQi WQ9201S"
DESCRIPTION = "wq9201_*.bin (wifi, BT, dtop, PHY and OEM images) and \
wq_wlan_settings.ini, as shipped on Seeed's factory image for the beta board."
# WuQi publishes no licence for these images. CLOSED keeps the build honest about
# that; whether an image carrying them may go to customers is a question for
# Seeed/WuQi, not something this recipe answers.
LICENSE = "CLOSED"

FILESEXTRAPATHS:prepend := "${THISDIR}/files:"

SRC_URI = " \
    file://firmware/wq9201_fw_bt_1_1_uart.bin \
    file://firmware/wq9201_fw_dtop_1_1_sdio.bin \
    file://firmware/wq9201_fw_wifi_1_1_sdio.bin \
    file://firmware/wq9201_oem_1_1_0366.bin \
    file://firmware/wq9201_phy_1_1_4366_0102.bin \
    file://firmware/wq_wlan_settings.ini \
"

S = "${UNPACKDIR}"

inherit allarch

# Seeed's settings file, not the driver tree's configs/wq_wlan_settings.ini: theirs
# sets ant_mode=2 (single antenna) and coex_ant_mode=2 (BT shares the wifi
# antenna), which is this board -- one U.FL connector.
do_install() {
	install -d ${D}${nonarch_base_libdir}/firmware
	install -m 0644 ${UNPACKDIR}/firmware/* ${D}${nonarch_base_libdir}/firmware/
}

FILES:${PN} = "${nonarch_base_libdir}/firmware"
