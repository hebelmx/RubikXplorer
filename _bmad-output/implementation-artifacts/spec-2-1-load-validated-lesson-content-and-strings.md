---
title: 'Story 2.1: Load validated Lesson content and strings'
type: 'feature'
created: '2026-10-30'
status: 'done'
baseline_revision: 'c46a41a5b6929377aab3a94657fc5256d7766708'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-2-context.md'
  - '{project-root}/AGENTS.md'
warnings: ['oversized']
deferred:
  - summary: >-
      Migrate existing app strings.xml entries (Story 1.6/1.7 Move plain names, debug overlay) to content strings so strings.xml holds navigation chrome only.
    evidence: |-
      app/src/main/res/values/strings.xml holds Learner-facing non-chrome text (move plain names such as "middle slice", "More turns: wide and slice"); pre-existing from Stories 1.6/1.7, and AD-6 says strings.xml holds static navigation chrome only.
    location: >-
      app/src/main/res/values/strings.xml
    severity: medium
  - summary: >-
      Add a Gradle TestKit test proving the build-logic gates (checkLearnerText, checkModuleBoundaries) fail the build on a violating tree.
    evidence: |-
      LearnerTextPolicyTest exercises only the pure policy; nothing runs CheckLearnerTextTask on a bad tree, so a broken source collection (NO-SOURCE via @SkipWhenEmpty) or a lost throw would keep CI green. No build-logic gate has a TestKit test yet, so the pattern is best added for all gates together.
    location: >-
      build-logic/src/main/kotlin/dev/rubikxplorer/buildlogic/tasks/CheckLearnerTextTask.kt
    severity: medium
  - summary: >-
      Make msg.validation.impossible-piece translatable: the {piece} argument is an English enum id (edge/corner) dropped into the message.
    evidence: |-
      CubeValidator (Story 1.4) passes mapOf("piece" to piece.id); other locales cannot translate the noun. Use an ICU select over the ids or pass a text key.
    location: >-
      core/domain/src/main/resources/content/strings/en.json
    severity: low
  - summary: >-
      Verify content/** Java resources are packaged into the release APK and survive R8 before Story 2.3 wires the loader into :app.
    evidence: |-
      Unverified (maybe-false). The loader reads content/ from the classpath; no test checks the APK contains it. Settle by inspecting the release APK or an instrumented load test.
    location: >-
      core/domain/src/main/resources/content/
    severity: medium (unverified)
---

<intent-contract>

## Intent

**Problem:** Lessons and Learner-facing words have no home yet: there is no content model, no loader, no `content/strings/en.json` (the five `msg.validation.*` keys `CubeValidator` emits resolve to nothing), and nothing stops a literal string or a broken Lesson from shipping (FR-4, FR-6, NFR-10, AD-6, AD-12, AR-10, UX-DR24).

**Approach:** Add a kotlinx.serialization content model + loader in `:core:domain` (`content/`) over a resource bundle with JSON Schemas and `strings/en.json`, a pure validator shared by the loader and a content test, and a build-logic gate that fails `check` on Learner-facing string literals in `:core:*` and `:app`.

## Boundaries & Constraints

**Always:**
- Resources under `core/domain/src/main/resources/content/`: `bundle.json` (manifest: `schemaVersion`, ordered `stages` file paths), `schema/{bundle,stage,lesson,predicate}.schema.json` (JSON Schema draft 2020-12, `additionalProperties: false`, every property `required`), `stages/` (no Stage shipped yet — `stages: []`), `strings/en.json` (flat object `key → ICU message`).
- Model (`dev.rubikxplorer.domain.content`), all fields required, no defaults: `Stage { id, title: TextKey, lessons: [Lesson] }`; `Lesson { id, stageId, prerequisites: [LessonId], objective: TextKey, entryState, demonstration, learnerAction: TextKey, completion: Predicate, preserve: Predicate, recovery: RecoveryRef, steps: [Step] }`; `Step { goal, preserve, watch, notice: TextKey, moves }`. `entryState` is a 54-char URFDLB facelet string of color letters `W Y G B R O`; `demonstration` and `moves` are Notation strings; `recovery` is an id string `recovery-…` (resolved by Epic 4). Stage IDs `stage-NN-<slug>`, Lesson IDs `lesson-NN-NN`.
- `Predicate` becomes `@Serializable` with class discriminator `"predicate"` set on the content `Json` instance (not the experimental annotation); `WhiteCrossSolved` is `@SerialName("white-cross-solved")`. `Json` is strict: `ignoreUnknownKeys = false`, no coercion, no explicit nulls.
- `ContentLoader.load(source: ContentSource): ContentLoadResult` where `ContentSource` is `fun interface (path) -> ByteArray?` with a classpath implementation. Sealed `ContentLoadResult.Loaded(ContentBundle)` / `Failed(ContentLoadError)`; errors: `MissingResource(path)`, `Malformed(path, detail)` (any decode failure incl. unknown predicate), `UnsupportedSchemaVersion(found, supported)`, `Invalid(problems)`. No exception escapes `load`.
- `ContentBundle` exposes `schemaVersion: Int`, `contentRevision: String` (lowercase SHA-256 hex over, in manifest order, each file's path and raw bytes: `bundle.json`, each stage file, `strings/en.json`), `stages`, and the `strings` map.
- `ContentValidator.validate(bundle) : List<ContentProblem>` (sealed problems, empty = valid) checks: duplicate Stage/Lesson IDs; ID format; `stageId` = containing Stage; prerequisites name known Lessons; Notation parses (`Notation.parse`); `entryState` decodes and passes `CubeValidator`; every TextKey matches `<area>.<contentId>.<field>[.<n>]` with `contentId` = the owning Stage/Lesson id, exists in `en.json`, and its message has no ICU arguments; every message parses (balanced braces).
- Content test (`core/domain/src/test/.../content/`) fails on: (1) schema/model mismatch — each schema's property names, `required` set and JSON types equal the `SerialDescriptor` of its Kotlin class; (2) the shipped bundle not loading as `Loaded` with zero problems; (3) a duplicate key in raw `en.json` text (kotlinx keeps the last silently, so detect in a raw scan); (4) a `Text("key", mapOf(...))` call in `core/domain/src/main/kotlin` whose key is missing from `en.json` or whose arg names differ from the message's top-level ICU argument names. Fixture bundles under `src/test/resources/content-fixtures/` (one valid Lesson using `white-cross-solved`, plus one broken variant per I/O row) drive the negative cases.
- `en.json` adds the five `msg.validation.*` keys with calm, neutral copy (UX-DR24: no exclamation marks; `impossible-piece` uses `{piece}`).
- Literal gate: `LearnerTextPolicy` (pure, unit-tested in build-logic) + `CheckLearnerTextTask` registered by both `JvmCoreConventionPlugin` and `AndroidAppConventionPlugin`, wired into `check`, scanning `src/main/kotlin`. After stripping comments it flags (a) a letter-bearing string literal passed as Compose `Text(` first argument or as `text`, `contentDescription`, `label`, `placeholder`, `title`, `stateDescription`, `onClickLabel` named args; (b) any string literal holding two or more whitespace-separated words, except inside programmer-error contexts (`require`, `requireNotNull`, `check`, `checkNotNull`, `error`, `throw`, `*Exception(`, `toString()`). Violations report `file:line`. Current code must pass without suppressions.
- Versions: kotlinx.serialization-json and the `org.jetbrains.kotlin.plugin.serialization` plugin (version = `kotlin`) live only in `gradle/libs.versions.toml`; plugin declared `apply false` in root `build.gradle.kts`. Use 1.12.0 (spine) after confirming it exists on Maven Central and works with Kotlin 2.4.21; otherwise the newest compatible stable, noted in Spec Change Log.

**Never:**
- No Stage 1–3 authoring, `tutor-config.json`, Algorithms, or additional predicates / `allOf` / `anyOf` (Stories 2.2, 2.7).
- No app wiring of the loader, no screen, no ICU formatting runtime, no Hilt module for content.
- No migration of existing `strings.xml` entries (Story 1.6/1.7 move names, debug overlay); record as deferred.
- No JSON Schema validator dependency; no Android/Compose imports in `:core:domain`; no edits to `_bmad/`, `.claude/skills/`, planning artifacts.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Shipped bundle | classpath `content/` | `Loaded`, `schemaVersion == 1`, 64-hex `contentRevision`, 0 problems | No error expected |
| Revision stable | same bytes loaded twice / one byte changed | same hash / different hash | No error expected |
| Missing manifest or stage file | source returns null | `Failed(MissingResource(path))` | sealed, no throw |
| Bad JSON / missing Lesson field / unknown key | e.g. no `recovery` | `Failed(Malformed(path, …))` | sealed |
| Unknown predicate | `"predicate": "nope"` | `Failed(Malformed(…))` | sealed |
| Wrong `schemaVersion` | `2` | `Failed(UnsupportedSchemaVersion(2, 1))` | sealed |
| Missing text key / message with args used as TextKey / bad key format / duplicate Lesson id / bad Notation / invalid entryState | fixture variant | `Failed(Invalid([matching problem]))` | sealed |
| Duplicate raw key in en.json | fixture text | content test check reports the key | test fails |
| Code arg mismatch | `Text("k", mapOf("a" to 1))` vs message `{b}` | scan check reports key + both arg sets | test fails |
| Literal in UI / core | `Text("Undo")`, `val s = "Turn the top"` | `LearnerTextPolicy` violation with line | `check` fails |

</intent-contract>

## Code Map

- `core/domain/src/main/kotlin/dev/rubikxplorer/domain/text/Text.kt` -- `Text(key, args: Map<String, Any>)`; key-form KDoc. Reuse; do not change shape.
- `core/domain/src/main/kotlin/dev/rubikxplorer/domain/predicate/{Predicate,WhiteCrossSolved}.kt` -- sealed interface + the only predicate (`data object`); add serialization annotations only.
- `core/domain/src/main/kotlin/dev/rubikxplorer/domain/cube/CubeValidator.kt:30-56` -- emits `msg.validation.{wrong-color-counts,impossible-piece(piece),twisted-corner,flipped-edge,parity}`; `validate(Facelets): ValidationResult`.
- `core/domain/src/main/kotlin/dev/rubikxplorer/domain/cube/{Notation,Facelets,Color}.kt` -- `Notation.parse(text): NotationResult` (sealed); `Facelets(colors)` (54, URFDLB); `Color` enum. No facelet-string codec exists — add one in `content/`.
- `core/domain/build.gradle.kts` -- add serialization plugin + `implementation(libs.kotlinx.serialization.json)`.
- `gradle/libs.versions.toml`, `build.gradle.kts` -- versions/plugins; root declares plugins `apply false` (KGP pinning).
- `build-logic/src/main/kotlin/dev/rubikxplorer/buildlogic/{JvmCoreConventionPlugin,AndroidAppConventionPlugin}.kt` -- where gates are registered; pattern: `tasks/CheckModuleBoundariesTask.kt` + `policy/ModuleBoundaryPolicy.kt` + `src/test/.../policy/*Test.kt`.
- `app/src/main/kotlin/dev/rubikxplorer/app/{ui,render,flags}/` -- must pass the literal gate as-is (uses `stringResource`; `error(...)` in `BuildConfigFeatureFlags`; single-word `SemanticsPropertyKey` names).
- `build-logic/src/main/resources/rubikxplorer-keep-rules.pro` -- R8 rules; kotlinx.serialization ships its own consumer rules, so nothing needed unless release build fails.

## Tasks & Acceptance

**Execution:**
- `gradle/libs.versions.toml`, `build.gradle.kts`, `core/domain/build.gradle.kts` -- add kotlinx.serialization json + plugin -- AD-6 parser.
- `core/domain/src/main/kotlin/dev/rubikxplorer/domain/predicate/{Predicate,WhiteCrossSolved}.kt` -- make serializable -- predicates in content.
- `core/domain/src/main/kotlin/dev/rubikxplorer/domain/content/` (model, ids, `FaceletString`, `ContentSource`, `ContentLoader`, `ContentLoadResult`, `ContentBundle`, `ContentValidator`, `ContentProblem`, `IcuArguments`) -- load + validate -- AC 1–2.
- `core/domain/src/main/resources/content/{bundle.json,schema/*.schema.json,strings/en.json}` -- bundle -- AC 1–2.
- `core/domain/src/test/kotlin/dev/rubikxplorer/domain/content/` + `src/test/resources/content-fixtures/` -- schema↔model test, shipped-bundle test, raw duplicate-key test, code `Text` key/arg scan, one test per I/O row, revision stability -- AC 1–2.
- `build-logic/src/main/kotlin/dev/rubikxplorer/buildlogic/{policy/LearnerTextPolicy.kt,tasks/CheckLearnerTextTask.kt}` + both convention plugins + `build-logic/src/test/.../policy/LearnerTextPolicyTest.kt` -- literal gate -- AC 3.

**Acceptance Criteria:**
- Given `content/schema/*.schema.json` and the Kotlin model, when `./gradlew :core:domain:test` runs, then it fails on a schema/model mismatch, a missing required Lesson field, an unknown predicate, or a duplicate, missing or argument-mismatched key in `content/strings/en.json`, and passes on the shipped bundle.
- Given a content bundle, when `ContentLoader.load` decodes it with kotlinx.serialization, then the result exposes `schemaVersion` and `contentRevision`, and every failure is a `ContentLoadResult.Failed` variant, never a thrown exception.
- Given a Learner-facing string literal in `:core:*` or `:app` main sources, when `./gradlew check` runs, then `CheckLearnerTextTask` fails with `file:line`; the current tree passes.

## Spec Change Log

- 2026-10-30 (implementation): kotlinx.serialization-json 1.12.0 confirmed on Maven Central and builds with Kotlin 2.4.21 — no version deviation. Choices within the contract: (a) the `Text(` first-argument rule exempts content-key-shaped literals (`msg.validation.parity`) so domain `Text(key)` calls pass without suppressions; (b) broken fixture variants are overlays — each variant dir holds only the files it breaks and the test source falls back to `content-fixtures/valid/`; the missing-manifest row uses an absent root; (c) templates count as non-letter word characters in the literal gate, so `"steps.$index.moves"` is one word; (d) the revision digest separates each path from its bytes with a 0x00 byte; (e) a `ContentSource` that throws becomes `Malformed(path, …)`; (f) the CI wiring guard now also requires `checkLearnerText` for `:app`, `:core:domain`, `:core:application` (`:core:solver` has no sources yet, so its task is NO-SOURCE).

## Review Triage Log

### 2026-10-30 — Review pass
- verdicts: 46 findings — high 0, medium 4, low 36, false 4, maybe-false 2
- findings:
  - `[false]` `[reject]` (intent) shipped bundle is empty, so the content test guards no real Lesson — no Lesson ships, so none is unguarded; Stage authoring is Story 2.7 and the intent says implement exactly this story's ACs.
  - `[low]` `[reject]` (intent) content data is never validated against `*.schema.json` — strict kotlinx decoding plus schema↔descriptor equality plus `ContentValidator` ID/key/entryState checks give equivalent coverage; a JSON Schema validator dependency is not worth it.
  - `[low]` `[patch]` (intent) `Text` key/arg scan covers only `:core:domain` — scan now walks every `core/*/src/main/kotlin` and `app/src/main/kotlin`.
  - `[low]` `[reject]` (intent) literal gate is heuristic (indirect literals, single words in locals slip) — inherent to a source scan; fixing needs data-flow analysis, and no such case exists today.
  - `[low]` `[reject]` (intent) a dotted literal in Compose `Text("a.b.c")` in `:app` passes — rare; fixing needs module-aware rules; the key-shape exemption is now limited to the `Text(` first argument.
  - `[medium]` `[defer]` (intent) `strings.xml` holds non-chrome Learner text — pre-existing from Stories 1.6/1.7; deferred.
  - `[low]` `[patch]` (blind) gate scans only `src/main/kotlin` — now also `src/debug/kotlin` and `src/release/kotlin`.
  - `[low]` `[patch]` (blind) CI guard omits `:core:solver:checkLearnerText` — added after confirming it is in `build --dry-run`.
  - `[low]` `[patch]` (blind) `"$count moves"` passes PROSE — template placeholder now counts as a word (sentinel `\uE000`).
  - `[low]` `[reject]` (blind) declaration bodies of `fun check(...) {}` / `: FooException() {}` are exempt — needs declaration-header detection (new branch); such names are unlikely in this codebase.
  - `[low]` `[patch]` (blind) UI rule misses literals passed indirectly — `BasicText(`/`AnnotatedString(` now flagged; constant/`append`/Toast indirection rejected as data-flow, same reason as the heuristic finding above.
  - `[low]` `[patch]` (blind) test `Text(...)` scan skips named-arg forms, covers one module, own lexer — scope widened (patched); named-arg forms and lexer sharing rejected: no such calls exist and the fix adds parsing branches.
  - `[low]` `[defer]` (blind) `msg.validation.impossible-piece` interpolates an English enum id — argument comes from Story 1.4's `CubeValidator`; deferred.
  - `[low]` `[reject]` (blind) missing validator rules (stage/lesson NN match, prerequisite cycles, empty steps, demonstration vs steps, manifest path vs id) — beyond the story's ACs, which the intent says to implement exactly; Story 2.7 authors content.
  - `[low]` `[reject]` (blind) key ownership does not check area/field/step index or orphan keys — beyond the ACs (duplicate/missing/arg-mismatch are covered).
  - `[low]` `[reject]` (blind) schema `pattern`/`const` not compared to Kotlin regexes — rare drift; fix needs exposing model regexes and a comparator extension.
  - `[medium]` `[patch]` (blind) `load` can throw a non-IAE from decoding or validation — `parse` now catches `Exception` and validation runs inside it, mapped to `Malformed`.
  - `[low]` `[patch]` (blind) `explicitNulls = false` loosens strictness — line removed.
  - `[low]` `[patch]` (blind) revision digest lacks length framing — 8-byte length now precedes each file's bytes; the "schemas excluded" part is false: schemas are test-time shape sources, not loaded content.
  - `[false]` `[reject]` (blind) new files committed as `100755` — `core.fileMode=false`, so `git add` records `100644`; the 100755 seen comes from `git diff --no-index`.
  - `[maybe-false]` `[defer]` (blind) `content/**` packaging into the APK unverified — settle by inspecting the release APK when Story 2.3 wires the loader; deferred as medium (unverified).
  - `[medium]` `[defer]` (verification-gap) `CheckLearnerTextTask` never run against a violating tree — needs the repo's first TestKit test, best added for all gates; deferred.
  - `[low]` `[patch]` (verification-gap) revision test only mutates `en.json` — test now mutates stage file and `bundle.json` separately too.
  - `[medium]` `[patch]` (verification-gap) `load` catches only IAE — same root cause as the blind `load can throw` row; same fix.
  - `[low]` `[patch]` (edge) debug/release source sets unscanned — same fix as blind source-set row.
  - `[low]` `[reject]` (edge) declaration-body exemption — same as blind row; rejected.
  - `[low]` `[patch]` (edge) any `*Exception` callee exempt — now requires an uppercase-first callee unless after `throw`.
  - `[low]` `[patch]` (edge) DECLARATION regex lacks trailing `\b` — added.
  - `[low]` `[patch]` (edge) `constructor`/`typealias` missing from DECLARATION — added.
  - `[low]` `[patch]` (edge) char literal inside a template breaks `templateEnd` — now skipped via `charEnd`.
  - `[false]` `[reject]` (edge) unterminated `${` at EOF crashes the gate — only reachable with source that does not compile, and the failure is loud.
  - `[low]` `[reject]` (edge) backslash in raw strings treated as escape — rare; fix threads a raw flag through the lexer.
  - `[low]` `[patch]` (edge) `BasicText`/`AnnotatedString`/`append` single words pass — `BasicText`/`AnnotatedString` patched; `append` rejected (StringBuilder false positives).
  - `[low]` `[reject]` (edge) self/cyclic prerequisites pass — beyond the ACs.
  - `[low]` `[reject]` (edge) blank demonstration / empty steps pass — beyond the ACs.
  - `[maybe-false]` `[reject]` (edge) duplicate field inside stage JSON may silently keep the last value — settle by decoding a stage with a repeated member; if true only low (repo-authored content, reviewed in PR).
  - `[low]` `[reject]` (edge) manifest stage path traversal — classpath-only, repo-authored manifest; no attacker surface.
  - `[low]` `[reject]` (edge) `schemaVersion: 2.0` reports Malformed not UnsupportedSchemaVersion — still a sealed failure; cosmetic.
  - `[low]` `[patch]` (edge) digest length framing — same fix as blind digest row.
  - `[low]` `[reject]` (edge) scan skips `Text(key = …)` / `args =` / `emptyMap()` forms — no such calls exist; adds parsing branches.
  - `[low]` `[reject]` (edge) scan lexer mishandles raw strings / char quotes — rare in Text call sites; sharing the build-logic lexer crosses build boundaries.
  - `[false]` `[reject]` (edge) unterminated string crashes the scan — only for non-compiling source.
  - `[low]` `[reject]` (edge) escaped duplicate key (`\u0062` vs `b`) undetected — contrived for hand-written en.json.
  - `[low]` `[patch]` (edge, claim) key-shape exemption applies to all named args — now limited to the `Text(` first argument.
  - `[low]` `[reject]` (edge, claim) declaration-body exemption — duplicate of the rejected row.
  - `[low]` `[reject]` (edge, claim) scan silently skips some call forms — duplicate of the rejected row.

## Design Notes

- **entryState as a facelet string, no orientation ID:** `CubeValidator` already derives orientation from the centers, so the 54 letters fully determine the state; this matches the "54-character facelet string" data-format convention without a redundant field.
- **Text keys in content carry no args.** Content TextKeys are plain keys; args only come from code `Text(...)` calls, so the arg check splits: content keys → message must have no arguments; code keys → arg names must equal the message's top-level `{name}` / `{name, …}` arguments (ignore nested sub-message text and `#`).
- **Gate vs test split:** the literal gate is a build gate (AGENTS: gates live in build-logic); the key/arg checks need `en.json`, so they live in the `:core:domain` content test (Gradle runs tests with the module dir as working dir, so `File("src/main/kotlin")` is stable).

## Verification

**Commands:**
- `./gradlew spotlessCheck build` -- expected: exit 0 (incl. Kover ≥ 80% on `:core:domain`, new `checkLearnerText` tasks run under `check`).
- `./gradlew -p build-logic test` -- expected: exit 0, `LearnerTextPolicyTest` green.
- `./gradlew :core:domain:test --tests '*content*'` -- expected: all content tests green; temporarily inserting `Text("Undo")` in an `:app` composable makes `./gradlew :app:check` fail (revert after).

## Auto Run Result

Status: done

**Summary:** Added the AD-6 content pipeline in `:core:domain`: a kotlinx.serialization model (Stage, Lesson, Step, IDs, facelet-string codec), a strict `ContentLoader` returning sealed `ContentLoadResult` with `schemaVersion` and a SHA-256 `contentRevision`, a `ContentValidator`, the shipped bundle (`bundle.json`, four draft 2020-12 schemas, `strings/en.json` with the five `msg.validation.*` messages), `Predicate`/`WhiteCrossSolved` made serializable, and a build-logic `checkLearnerText` gate wired into `check` for `:core:*` and `:app`.

**Files changed:**
- `gradle/libs.versions.toml`, `build.gradle.kts`, `core/domain/build.gradle.kts` — kotlinx.serialization 1.12.0 + plugin.
- `core/domain/src/main/kotlin/dev/rubikxplorer/domain/predicate/{Predicate,WhiteCrossSolved}.kt` — serializable, `white-cross-solved`.
- `core/domain/src/main/kotlin/dev/rubikxplorer/domain/content/*` — model, IDs, FaceletString, IcuArguments, ContentProblem, ContentValidator, ContentLoader.
- `core/domain/src/main/resources/content/**` — manifest, schemas, en.json, empty `stages/`.
- `core/domain/src/test/kotlin/dev/rubikxplorer/domain/content/*`, `src/test/resources/content-fixtures/**` — schema↔model, shipped bundle, raw duplicate keys, code Text scan, one test per I/O row, revision stability.
- `build-logic/.../policy/LearnerTextPolicy.kt`, `tasks/CheckLearnerTextTask.kt`, both convention plugins, `LearnerTextPolicyTest.kt` — literal gate.
- `.github/workflows/ci.yml` — dry-run guard requires the four `checkLearnerText` tasks.

**Review findings:** 46 findings; 13 patch fixes applied (one medium: `load` never throws; the rest low: digest framing, explicitNulls, revision test, Text-scan scope, gate source sets, CI solver task, six policy fixes). 4 deferred (strings.xml migration, TestKit gate test, impossible-piece ICU select, APK packaging, unverified). Rejected findings and reasons are in the Review Triage Log.

**Follow-up review recommended:** false — patched entries: high 0, medium 1, low 12.

**Verification:** `./gradlew spotlessCheck build` exit 0 (one intermittent `spotlessKotlin` "cannot fingerprint lineEndingsPolicy" failure passed on re-run, also seen by the implementer); `./gradlew -p build-logic test` exit 0 (LearnerTextPolicyTest 20/20); content tests 39/39 green; matrix rows each covered by a passing test.

**Residual risks:** source-text heuristics in the literal gate and Text scan can miss unusual code; intermittent Spotless fingerprint error may appear in CI; `kotlinx-serialization-json` is `implementation`, so other modules calling `Predicate.serializer()` later will need it added.
