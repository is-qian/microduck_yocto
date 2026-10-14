# meta-microduck

The microduck robot's software: the images (`microduck-image`,
`microduck-image-dev`), the RAUC update bundle and configuration, the
microduck daemons and their policy set, ONNX Runtime, the data partition, the
media stack's appends, and the udev rules that give the daemons' groups access
to board devices.

Depends on: openembedded-core, meta-oe, meta-rockchip, meta-microduck-bsp.

Part of https://github.com/pollen-robotics/microduck_yocto, built with
`kas/microduck.yml`; see the repository's README.md and AGENTS.md.
