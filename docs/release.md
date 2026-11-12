# Pilot release runbook

Cubentia uses package `com.exxerpro.cubentia`, a PKCS12 upload keystore and Play App Signing.
Keep signing material outside version control. See [keys](../keys/README.md).

## Prepare Play and GitHub

1. Create the Cubentia app in the verified ExxerPro organization Play Console account with the exact package above. Complete required app declarations, including data safety: no data collected, and the required testing-track setup.
2. Create a PKCS12 upload keystore, back it up with its passwords in a password manager, and grant a Google Play Android Developer API service account access to release this app to testing tracks.
3. Configure these GitHub Actions secrets: `UPLOAD_KEYSTORE_BASE64` (base64 of the PKCS12 file), `UPLOAD_KEYSTORE_PASSWORD`, `UPLOAD_KEY_ALIAS`, `UPLOAD_KEY_PASSWORD`, `PLAY_SERVICE_ACCOUNT_JSON` (the service account JSON itself).
4. Leave repository variable `PLAY_RELEASE_STATUS` unset for the first release; the default is `draft`. Only `draft` and `completed` are accepted. A workflow-dispatch `release_status` overrides the variable.

## Build and first upload

Push a unique `vX.Y.Z` release tag, for example `v1.0.0`. This is an operator action. The first pilot tag is cut from the head of the Story 7.8 branch once its PR checks are green, because the pilot install is a human check before that PR merges; every later tag is cut from `main`. Manual dispatch must also select a `vX.Y.Z` tag, not a branch. Ensure the workflow run number exceeds all version codes already uploaded to Play; Play never reuses a version code. A rerun of the same run retains its code, so create a new run if that code was already uploaded.

A first job rejects a branch ref, a tag that is not `vX.Y.Z` and a status other than `draft` or `completed` (`.github/scripts/validate-release-inputs.sh`). The workflow then runs the instrumentation/UI/renderer-sync suite on a hosted API 34 x86_64 emulator through `.github/scripts/run-instrumentation.sh`: every androidTest class except `KeyFlowRotationTest` in one `connectedDebugAndroidTest` pass, then the Key Flow rotation suite through `tools/run-keyflow-rotation.sh`, which clears the app before each method (Story 12.2). Any failing method fails the release. Then it runs `./gradlew -p build-logic test` and `spotlessCheck build :app:bundleRelease :app:checkReleaseSize`. It signs the R8/resource-shrunk AAB, sets versionCode to the workflow run number and versionName to the tag without `v`, archives the AAB, R8 mapping and size report, and uploads to the internal track with the mapping. Secrets reach Gradle as `-P` properties from step environment variables (Gradle does not map `ORG_GRADLE_PROJECT_` names that contain dots), and the temporary keystore is removed even on failure.

For the first upload, keep status `draft`. The PO accepts Play App Signing and completes/rolls out the draft by hand in Play Console. If Play requires package bootstrap before its API accepts an upload, download the signed AAB artifact from the failed upload run, upload it manually in Console, accept Play App Signing, and finish the internal release there. Do not retry an already consumed version code. After the first rollout and required declarations are complete, set `PLAY_RELEASE_STATUS=completed` for subsequent automatic internal rollouts. `completed` affects only the internal track; promotion remains manual.

## Closed-testing pilot

Create a Google Group for the approximately eight consenting beginner testers and add it to the closed-testing track's tester list. Promote the verified internal release to closed testing in Play Console, review and roll it out, then share that track's opt-in link with the group. Testers must join the group and opt in with the same Google account used in Play. Allow Play processing time. Confirm a pilot tester can install the expected version before merging the Story 7.8 PR, and note the version and date in the PR: real pilot access is a human check, not proved by a successful CI upload.

Use the [pilot session protocol](pilot-session-protocol.md). Keep observations outside the app. Record build/version, installation confirmation and issues; triage severity 1–2 issues for fixes before production. Android vitals review belongs to release readiness (Epic 12).

## Local checks and size method

With no signing properties, `build` and `assembleRelease` remain unsigned. Signing requires all four properties: `cubentia.signing.storeFile`, `cubentia.signing.storePassword`, `cubentia.signing.keyAlias`, `cubentia.signing.keyPassword`. Optional `cubentia.versionCode` must be 1–2,100,000,000 and `cubentia.versionName` must be `X.Y.Z`. Never put real passwords in shell history.

Run `./gradlew :app:checkReleaseSize` to build the release bundle and its universal APK. The report is `app/build/reports/cubentia/release-size.txt`. Installed-size estimate = APK bytes + 3 × uncompressed dex bytes + 153,600 bytes app data. It budgets a vdex copy and compiled odex/art, using the [Story 3.8 device evidence](pruning-tables.md). It reports AAB bytes separately, estimated installed bytes, the 30,000,000-byte budget and headroom, and fails above the budget. This conservative estimate is not a device measurement. Every PR and release runs it separately; `build` does not depend on bundle, size checking or a device. Local instrumentation must target an emulator explicitly, never a connected physical device.
