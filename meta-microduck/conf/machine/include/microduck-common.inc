# Shared by every microduck machine (the Radxa Zero 3W now, the custom PCB later).

# UART2 is both the RK3566 debug console -- exposed as ttyFIQ0 by the vendor
# BSP's FIQ debugger -- and the wire robotd drives 15 Dynamixel XL330 servos
# plus the imu_to_dxl board (ID 200) on at 1 Mbaud.
#
# They cannot share it. A getty does not merely hold the port open, it *reads*
# it, consuming the servo replies before robotd sees them, and kernel printk
# mid-transaction corrupts a reply. microduck_daemon's scripts/setup-board.sh
# records this costing an afternoon of debugging a correctly wired robot.
#
# MICRODUCK_MOTOR_UART = "1" hands UART2 to robotd:
#   - drops the serial console (debug moves to HDMI console + SSH)
#   - disables CONFIG_FIQ_DEBUGGER, so the port enumerates as /dev/ttyS2
#     (see recipes-kernel/linux/files/motorbus.cfg)
#
# Leave it "0" during bring-up: you want serial the next time it won't boot.
MICRODUCK_MOTOR_UART ?= "0"

SERIAL_CONSOLES = "${@'' if d.getVar('MICRODUCK_MOTOR_UART') == '1' else '1500000;ttyFIQ0'}"
