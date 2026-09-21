# A writable U-Boot environment for RAUC. See files/rauc-env.cfg for the why.

FILESEXTRAPATHS:prepend := "${THISDIR}/files:"

# `+=`, never `=`. u-boot-rockchip's SRC_URI carries a SECOND git entry -- rkbin,
# with destsuffix=rkbin -- which make.sh requires to pack the loader and idblock.
# Replacing SRC_URI wholesale drops it and breaks the build in a way that does not
# name the cause. AGENTS.md:20.
SRC_URI += "file://rauc-env.cfg"

# Why a plain .cfg is enough, given this recipe builds via Rockchip's make.sh:
#
# make.sh does not bypass Yocto's defconfig handling. meta-rockchip hooks it in as
# do_configure:prepend and do_compile:append, so oe-core's own tasks still run in
# full: uboot_configure() does `make rk3568_defconfig`, then
# `merge_config.sh -m .config <every .cfg in SRC_URI>`, then oldconfig.
# find_cfgs() (cml1.bbclass) collects any SRC_URI entry ending in .cfg, so this
# file is merged with no further wiring. do_compile then builds against that merged
# .config, and make.sh -- sed-stripped of its own `make` lines by the prepend --
# only packs the already-built output.
#
# Do NOT reach for UBOOT_CONFIG_FRAGMENTS: it is only read on the UBOOT_CONFIG
# multi-config path, and uboot-config.bbclass:164 raises SkipRecipe if UBOOT_CONFIG
# and UBOOT_MACHINE are both set -- and the machine conf sets UBOOT_MACHINE.

# The environment must actually land where rauc-env.cfg says, and a wrong offset
# corrupts U-Boot rather than failing the build. Assert it at the end of
# do_configure, against the merged .config, where a mismatch costs a build instead
# of a flash.
python microduck_check_env_offset() {
    import os

    config = os.path.join(d.getVar("B"), ".config")
    if not os.path.exists(config):
        bb.fatal("no .config after do_configure; the merge path changed")

    want = {"CONFIG_ENV_IS_IN_BLK_DEV": "y",
            "CONFIG_ENV_OFFSET": "0x80000",
            "CONFIG_ENV_SIZE": "0x8000",
            "CONFIG_CMD_SETEXPR": "y"}
    got = {}
    with open(config) as f:
        for line in f:
            if "=" in line and not line.startswith("#"):
                key, _, value = line.strip().partition("=")
                if key in want:
                    got[key] = value

    for key, value in sorted(want.items()):
        if got.get(key) != value:
            bb.fatal("%s is %r in the merged .config, expected %r. rauc-env.cfg "
                     "did not take effect -- if merge_config.sh stopped running, "
                     "every value here silently reverts to the vendor default, and "
                     "CONFIG_ENV_OFFSET's default 0x3f8000 is inside our uboot "
                     "partition." % (key, got.get(key), value))

    bb.note("microduck: U-Boot env at %s size %s, setexpr on"
            % (got["CONFIG_ENV_OFFSET"], got["CONFIG_ENV_SIZE"]))
}
do_configure[postfuncs] += "microduck_check_env_offset"
