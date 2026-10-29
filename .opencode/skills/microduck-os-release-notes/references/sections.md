# Section mapping

Classify by labels, title and body. A section exists only if an item lands in it.
When a PR fits two, pick the one a robot owner would look in; a bug fix goes under
`🐛 Bug fixes` unless the fix is the headline of its area.

## 💔 Needs a reflash

Only when the PR body says a reflash is needed, or it changes what a RAUC update
does not write: U-Boot, the partition layout (`.wks`, `microduck-ab`), the data
partition's format, the first 16 MiB. Keywords: "reflash", "breaking", "partition",
"u-boot", "bootloader", "wks".

## 🔄 Updates

RAUC, A/B slots, OS update channels, `microduck-osupdate`, the update page,
signing, rollback, health gate. Keywords: "rauc", "bundle", "slot", "update", "osupdate",
"channel", "rollback", "mark-good".

## 🦆 Robot software

The daemon the image seeds and what it needs: `microduck-daemon`, policies, sounds,
robot config, ONNX Runtime, systemd units of the robot services. Keywords: "daemon",
"robotd", "mediad", "policies", "config", "onnx", "robot".

## 🎥 Camera & media

Camera sensor and tuning, rkaiq/ISP, MPP, GStreamer, WebRTC. Keywords: "camera",
"imx219", "gc2093", "rkaiq", "isp", "mpp", "gstreamer", "webrtc", "encoder", "video".

## 🔊 Audio

Keywords: "audio", "alsa", "dac", "speaker", "mic", "volume", "sound card".

## 📡 Connectivity

Wi-Fi, Bluetooth/BLE, NetworkManager, provisioning, time sync. Keywords: "wifi",
"wi-fi", "wlan", "aic8800", "wq9201s", "bluetooth", "ble", "hciattach",
"networkmanager", "provisioning", "ntp", "timesyncd".

## 🔩 Boards & kernel

Machine confs, device tree, kernel and its config, drivers, the beta board, fan, USB,
UART. Keywords: "kernel", "dts", "device tree", "driver", "beta", "zero3", "machine",
"uart", "usb", "fan", "pmic", "bsp".

## 🔧 Other improvements

User-visible changes with no clearer home. Keywords: "add", "support", "allow",
"improve", "default".

## 📖 Documentation

Keywords: "docs", "README", "AGENTS", "guide", "typo".

## 🐛 Bug fixes

Keywords: "fix", "bug", "regression", "crash", "broken", "error".

## 🏗️ Build & CI

What changes the build and not the robot: kas, HF Jobs, workflows, sstate, oelint,
layer pins with no visible effect. Keywords: "ci", "workflow", "kas", "hfjob",
"sstate", "build", "oelint", "bump", "pin", "refactor".
