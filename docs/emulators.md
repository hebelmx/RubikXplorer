# Emulators and KVM

Local Android Virtual Devices (AVDs) that simulate the project's two reference devices, the Galaxy S24 FE and the Galaxy Tab S7 FE 5G (`SM-T738U`). Set up and verified 2026-11-07 on the Kali dev host.

Emulator numbers are only a rough guide. Performance and camera acceptance still run on the physical devices (`docs/pruning-tables.md`, `docs/ARCHITECTURE.md`, `docs/benchmarks.md`).

## AVDs

| AVD | Simulates | Android | Screen / density | RAM |
|---|---|---|---|---|
| `GalaxyS24FE_API36` | Galaxy S24 FE | 16 (API 36, targetSdk) | 1080×2340 @ 450 dpi, portrait | 6 GB |
| `GalaxyTabS7FE_API34` | Galaxy Tab S7 FE 5G | 14 (API 34, its last OS update) | 2560×1600 @ 320 dpi, landscape | 4 GB |
| `GalaxyS24FE_API35` | Galaxy S24 FE | 15 (API 35) | 1080×2340 @ 450 dpi, portrait | 6 GB |
| `GalaxyS24FE_API37` | Galaxy S24 FE | 17 (API 37, compileSdk) | 1080×2340 @ 450 dpi, portrait | 6 GB |
| `spike3d_api29` | Pixel 4 (OQ-3 spike) | 10 (API 29, minSdk) | 1080×2280 | default |

All except the spike AVD use `system-images;android-<N>;google_apis;x86_64` (Android 17 is `android-37.0`). Shared settings: host GPU (`hw.gpu.mode=host`), 4 cores, 512 MB VM heap, 8 GB data partition, hardware keyboard, accelerometer, gyroscope and orientation sensors on, back camera `virtualscene` (a 3D room you can move around in), front camera `emulated`.

AVD files live in `~/.android/avd/<name>.avd/config.ini`.

## KVM

The emulator needs KVM for usable speed. Check it with:

```sh
grep -Ec '(vmx|svm)' /proc/cpuinfo   # > 0: virtualization is enabled in the CPU/BIOS
ls -l /dev/kvm                       # must exist, group kvm
groups | grep -w kvm                 # your user must be in kvm
emulator -accel-check                # expect "KVM ... is installed and usable"
```

If `/dev/kvm` is missing, enable VT-x/AMD-V in the BIOS and load the module (`sudo modprobe kvm_intel` or `kvm_amd`). If you are not in the group, run `sudo usermod -aG kvm $USER` and log in again.

## PATH

The SDK tools are not on PATH by default. Add this to `~/.zshrc`:

```sh
export ANDROID_HOME=$HOME/Android/Sdk
export PATH=$ANDROID_HOME/emulator:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH
```

## Daily use

```sh
emulator -list-avds
emulator -avd GalaxyS24FE_API36                # with a window
emulator -avd GalaxyTabS7FE_API34 -port 5556   # second emulator at the same time
emulator -avd GalaxyS24FE_API36 -no-window -no-audio -no-snapshot-save   # headless, for agents/CI-style runs

adb wait-for-device shell 'while [ "$(getprop sys.boot_completed)" != 1 ]; do sleep 2; done'
./gradlew installDebug
adb -s emulator-5554 emu kill                  # shut one down
```

When several targets are connected (emulators plus the physical phone), choose one with `ANDROID_SERIAL=emulator-5554 ./gradlew installDebug` or `adb -s <serial> ...`. Emulator serials are `emulator-<port>`; the default first port is 5554.

## Recreating an AVD

```sh
sdkmanager "system-images;android-36;google_apis;x86_64"
echo no | avdmanager create avd -n GalaxyS24FE_API36 \
  -k "system-images;android-36;google_apis;x86_64" -d pixel_7 --force
```

Then set these keys in `~/.android/avd/GalaxyS24FE_API36.avd/config.ini` (replace the existing lines):

- S24 FE: `hw.lcd.width=1080`, `hw.lcd.height=2340`, `hw.lcd.density=450`, `hw.ramSize=6144`, `hw.initialOrientation=portrait`
- Tab S7 FE (create it from `-d pixel_tablet`): `hw.lcd.width=2560`, `hw.lcd.height=1600`, `hw.lcd.density=320`, `hw.ramSize=4096`, `hw.initialOrientation=landscape`
- Both: `hw.gpu.enabled=yes`, `hw.gpu.mode=host`, `hw.cpu.ncore=4`, `vm.heapSize=512`, `disk.dataPartition.size=8G`, `hw.keyboard=yes`, `hw.camera.back=virtualscene`, `hw.camera.front=emulated`

`sdkmanager` warns that it is deprecated in favor of `android sdk`; both work. Its `--list` output uses `/` separators (`system-images/android-36/...`), but install arguments still use `;`.

## Troubleshooting

- `Failed to create EGL fence sync: 12288` in the API 36 log is harmless; the AVD boots and renders. If you see rendering glitches, start it with `-gpu swiftshader_indirect`. Note that swiftshader frame times are excluded from benchmark evidence (`docs/spikes/oq-3-3d-rendering.md`).
- To use a real webcam as the back camera, set `hw.camera.back=webcam0` (list webcams with `emulator -webcam-list`).
