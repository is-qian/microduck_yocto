# meta-microduck-bsp

Board support for the microduck robot (Rockchip RK3566): the machine
configurations `microduck-zero3` (Radxa Zero 3W stand-in) and `microduck-beta`
(the custom PCB), the vendor 6.1 kernel and U-Boot appends, the AIC8800 and
WQ9201S radio drivers, board-support recipes and the A/B partition layout
(`files/wic/microduck-ab.wks.in`).

Depends on: openembedded-core, meta-oe, meta-networking, meta-rockchip.

Part of https://github.com/pollen-robotics/microduck_yocto, built with
`kas/microduck.yml`; see the repository's README.md and AGENTS.md.
