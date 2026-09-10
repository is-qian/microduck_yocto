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
# Was "0" for bring-up, on the reasoning that you want serial the next time it
# won't boot. Flipped 2026-09-10, once the board booted from SD and answered on
# ssh: no USB-UART adapter was ever attached, so the serial console UART2 was
# being held for could not be read anyway, while robotd sat in a retry loop
# ("cannot open the bus ... /dev/ttyS2: No such file or directory", attempt=330).
# The no-boot path is still covered: the dts patch in recipes-kernel/linux/patches
# puts console=tty0 in bootargs, so a kernel that dies prints to HDMI. That patch
# also still names console=ttyFIQ0, which simply fails to register once the FIQ
# debugger is gone -- harmless. Do NOT "fix" it to console=ttyS2, which would put
# printk back on the servo wires, the exact failure this flag exists to prevent.
MICRODUCK_MOTOR_UART ?= "1"

SERIAL_CONSOLES = "${@'' if d.getVar('MICRODUCK_MOTOR_UART') == '1' else '1500000;ttyFIQ0'}"
