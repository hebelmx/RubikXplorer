# Story 10.23: release size before and after (AC7, NFR-11)

The shadow sticker reader, its toggle receiver and `sticker_reader.tflite` live only in
`app/src/camera/debug/sticker/`, composed into a build type that is debuggable and has a corner-model flag above OFF.
The release, benchmark and `cornerR8` builds carry none of them (`check<Variant>ShadowReader`), so the release size
report is unchanged.

Method (2026-11-22): `./gradlew :app:checkReleaseSize` on two clean source exports without `.git` (`git archive` of the
baseline, and the working tree's tracked plus new files), so AGP's embedded VCS metadata
(`META-INF/version-control-info.textproto`) cannot differ between them. Same machine, JDK 25, Gradle 9.8.2.

## Before: baseline `0861996f` (chore(sprint): start story 10.23)

```text
Cubentia release installed-size estimate (bytes)
AAB: 6323732
Universal APK: 3439603
Uncompressed dex: 3129160
App-data allowance: 153600
Installed estimate (APK + 3 x dex + data): 12980683
Budget: 30000000
Headroom: 17019317
Estimate only; measured device method: docs/pruning-tables.md (Story 3.8).
```

## After: Story 10.23 working tree

```text
Cubentia release installed-size estimate (bytes)
AAB: 6323732
Universal APK: 3439603
Uncompressed dex: 3129160
App-data allowance: 153600
Installed estimate (APK + 3 x dex + data): 12980683
Budget: 30000000
Headroom: 17019317
Estimate only; measured device method: docs/pruning-tables.md (Story 3.8).
```

**Identical** (`diff` reports no difference).

Built inside the git worktree instead, the AAB line reads `6323728` (4 bytes less): the only differing
AAB entry is `base/root/META-INF/version-control-info.textproto` (the commit AGP records), every other entry has the same
size and CRC; the universal APK, dex and installed estimate are the same.

## Packaging check

- `app-debug.apk`: `assets/sticker_reader.tflite` 107,848 bytes, stored uncompressed (`noCompress tflite`).
- `app-release-unsigned.apk`, `app-benchmark.apk`, `app-cornerR8.apk`: no `sticker_reader` entry
  (`unzip -l … | grep sticker_reader` finds nothing).
- `checkReleaseShadowReader`, `checkBenchmarkShadowReader` (in `build`) and `checkCornerR8ShadowReader` (CI's cornerR8 step)
  pass with empty reports.
