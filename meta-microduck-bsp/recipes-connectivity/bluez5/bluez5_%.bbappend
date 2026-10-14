# btattach cannot set a baud rate against wrynose's glibc (2.43 on the image).
#
# glibc 2.42 redefined speed_t as the plain rate, so B1500000 is now 1500000
# rather than a c_cflag bit pattern, and btattach's `c_cflag |= speed` writes
# garbage into the line settings. On the beta board, `btattach -S 1500000`
# left ttyS1 at "ispeed 200 baud; ospeed 0 baud": B0 is hang-up, RTS dropped.
# The WQ9201S never answered HCI Reset, and the noise it did receive
# desynchronised its HCI parser, so nothing attached in the same boot until
# enough bytes had gone by (measured 2026-10-06). Seeed's Debian image, on
# glibc 2.36, runs the same command without trouble.
#
# hciattach is unaffected: it already sets the rate with cfsetospeed().
#
# Fixed upstream after 5.87; drop this once the bluez5 recipe carries it.
FILESEXTRAPATHS:prepend:microduck-machine := "${THISDIR}/files:"
SRC_URI:append:microduck-machine = " file://0001-btattach-Update-for-glibc-2.42-compatibility.patch"
