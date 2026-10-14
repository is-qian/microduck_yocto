# oe-core's default PACKAGECONFIG is "check debug tools" (verified in the pinned
# f09a0f2 gstreamer1.0_1.28.2.bb), so libgstreamer is built with
# -Dtracer_hooks=false and GST_TRACERS=latency is a silent no-op: no hook to fire
# and no plugin to load. The hooks are a property of the core library, so they go
# on for both images. The plugin that consumes them (latency, stats, rusage, log,
# leaks, factories, dots) is split out so only the dev image carries it.
#
# Both flags together: gstreamer 1.28's plugins/tracers/meson.build errors on
# coretracers=enabled without tracer_hooks.
PACKAGECONFIG:append:microduck = " tracer-hooks coretracers"

PACKAGE_BEFORE_PN:append:microduck = " ${PN}-tracers"
FILES:${PN}-tracers = "${libdir}/gstreamer-1.0/libgstcoretracers.so"
