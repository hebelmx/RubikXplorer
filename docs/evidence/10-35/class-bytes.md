# Story 10.35 class-byte record

Measured with `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk`.

- **Before:** the untouched baseline tree `d5662d8f614948fee3639c917c6257012e6af8f5`, `./gradlew :app:checkCameraSize :app:assembleDebug` (BUILD SUCCESSFUL).
- **After:** the removal, `./gradlew :app:checkCameraSize :app:checkReleaseSize :app:assembleDebug` (BUILD SUCCESSFUL).

## `app/build/reports/cubentia/camera-size.txt`

Before:

```text
Cubentia camera installed-size estimate (bytes)
Release estimate: 13025179
Debug camera classes excluding recorder, sticker reader, and legacy corner: 1187172
Additional runtime jars and single-ABI native libraries: 11697783
Camera model/runtime assets: 378288
Projected camera size: 28662766
Budget: 30000000
LiteRT allowed: true
Forbidden artifacts: none
```

After:

```text
Cubentia camera installed-size estimate (bytes)
Release estimate: 13025179
Debug camera classes excluding debug-only tools, sticker reader, and legacy corner: 1161682
Additional runtime jars and single-ABI native libraries: 11697783
Camera model/runtime assets: 378288
Projected camera size: 28586296
Budget: 30000000
LiteRT allowed: true
Forbidden artifacts: none
```

| Line | Before | After | Delta |
| --- | ---: | ---: | ---: |
| Release estimate | 13,025,179 | 13,025,179 | 0 |
| Debug camera classes (excluding debug-only tools, sticker reader, legacy corner) | 1,187,172 | 1,161,682 | -25,490 |
| Additional runtime jars and single-ABI native libraries | 11,697,783 | 11,697,783 | 0 |
| Camera model/runtime assets | 378,288 | 378,288 | 0 |
| Projected camera size (budget 30,000,000) | 28,662,766 | 28,586,296 | -76,470 |

The recorder package was already excluded from the class sum by `debugOnlyClassGlobs`, so the class-sum drop comes from
the removed `ScanFrameSource` lock and settle helpers (`ExposureLocks`, `SettleTracker`, `Settle`, `lockSupport`,
`applyLocks`, `settleOutcome`, `cubeMeteringRegion`, `chooseMetering`, the settle constants), `TorchButton`'s parameter and
the metering `includeAwb` parameter (removed in review);
the projection counts class bytes three times (installed APK + 3 x dex), hence -76,470 = 3 x -25,490. The report's class
line now reads "excluding debug-only tools" because the recorder glob is gone.

## `app/build/reports/cubentia/release-size.txt` (after)

```text
Cubentia release installed-size estimate (bytes)
AAB: 6345370
Universal APK: 3439603
Uncompressed dex: 3143992
App-data allowance: 153600
Installed estimate (APK + 3 x dex + data): 13025179
Budget: 30000000
Headroom: 16974821
Estimate only; measured device method: docs/pruning-tables.md (Story 3.8).
```

The release installed estimate (13,025,179) equals the "Release estimate" line of the baseline camera report: the
release build is unchanged (the recorder and the removed helpers never shipped in it).

## Debug APK `classes*.dex` (`unzip -l app/build/outputs/apk/debug/app-debug.apk`)

| | Dex files | Total bytes |
| --- | ---: | ---: |
| Before | 33 | 46,885,472 |
| After | 33 | 46,258,040 |
| Delta | 0 | -627,432 |

The debug APK dex totals were measured before the review removed the `includeAwb` parameter (which took a further 963 B off
the camera class sum); the camera report above is the final tree. The debug APK delta includes the recorder package (about 3.7k source lines with Compose UI), which the camera class sum
never counted.

## `cornerR8` assemble (the CI build job)

Run on the removal tree (`b6b081cdec0679f466b4d0705a23580e6ff9e0d1`) with the same environment as above, using the
command of the "Assemble cornerR8 (R8 + LiteRT keep rules)" step in `.github/workflows/ci.yml`:

```text
./gradlew -Pcubentia.cornerR8=true :app:assembleCornerR8 :app:assembleCornerR8AndroidTest :app:checkCornerR8ShadowReader
```

- Exit status **0** (`BUILD SUCCESSFUL in 1s`, 107 actionable tasks: 2 executed, 105 up-to-date).
- Re-run with `--rerun-tasks` added, so no task was taken from an earlier run: exit status **0**
  (`BUILD SUCCESSFUL in 1m 31s`, 107 actionable tasks: 107 executed).
