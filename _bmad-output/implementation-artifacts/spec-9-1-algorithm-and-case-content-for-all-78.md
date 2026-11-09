---
title: 'Algorithm and Case content for all 78'
type: 'feature'
created: '2026-11-09'
status: 'done'
baseline_revision: 'ce30acf0b99cbb36960efabff01736fdc9211b4a'
review_loop_iteration: 0
followup_review_recommended: false
context: []
warnings: ['oversized']
deferred:
  - summary: 'AC 2 PRD memlog append withheld: this run was dispatched with a rule forbidding any write under _bmad-output/planning-artifacts, and the PRD memlog lives there. The note text is in Design Notes; the append must be run by someone allowed to write there.'
    evidence: 'Dispatch rule "Never edit planning artifacts under _bmad-output/planning-artifacts"; memlog path _bmad-output/planning-artifacts/prds/prd-RubikXplorer-2026-10-29/.memlog.md'
    severity: 'medium'
  - summary: >-
      Which Case belongs to which family is pinned only by per-family counts, and no later story owns the per-Case check.
    evidence: |-
      ShippedContentTest asserts FAMILY_COUNTS and the validator checks only the oll-/pll- prefix, so swapping two Cases between equal-sized families passes. Story 9.2 proves Notation, not family. Needs an owner by Story 9.4.
    location: >-
      core/domain/src/test/kotlin/dev/cubentia/domain/content/ShippedContentTest.kt
    severity: medium
---

<intent-contract>

## Intent

**Problem:** The Library (FR-18) and 2-look LL mini-Course (FR-17) need all 57 OLL and 21 PLL Cases and their Algorithms as data, and no content shape for them exists.

**Approach:** Add Case and Algorithm JSON under `content/algorithms/`, load it through the existing strict `ContentLoader`, and reject bad data in `ContentValidator`. Notation and Cases are data from the table in Design Notes.

## Boundaries & Constraints

**Always:** Case IDs are `case-oll-NN` and `case-pll-<letters>`; Algorithm IDs are `alg-<case suffix>-a`, and each Algorithm declares `caseId`. Each Case has `set`, `family` and a `name` TextKey `case.<caseId>.name`. Primary means the `-a` Algorithm. Glossary terms Case and Algorithm are used verbatim in type names. Notation goes through `Notation.parse` only. `content/schema/*.schema.json` stays the shape source and is checked against the Kotlin model.

**Never:** No `LastLayerMatcher`, Case-state derivation or Algorithm verification (Story 9.2). No diagrams (9.3), no Library UI (9.4), no family display labels (9.4). No Case state stored in content (AD-19). No alternatives beyond `-a`. Do not copy diagrams or prose from any source, including `docs/*.odg`. Do not write under `_bmad-output/planning-artifacts/`, `_bmad/` or `.claude/`.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Shipped content | `algorithms/oll.json`, `pll.json` | Loads; 57 OLL + 21 PLL Cases, 78 Algorithms | No error expected |
| Duplicate Case or Algorithm ID | Two entries share an ID, also across files | Not loaded | `DuplicateCaseId` / `DuplicateAlgorithmId` |
| Malformed ID | `case-oll-5`, `alg-oll-45` | Not loaded | `MalformedCaseId` / `MalformedAlgorithmId` |
| Case without primary | Case has no `-a` Algorithm | Not loaded | `NoPrimaryAlgorithm` |
| Algorithm ID disagrees with `caseId` | `alg-oll-45-a` declares `case-oll-46` | Not loaded | `AlgorithmCaseMismatch` |
| Unknown Case | `caseId` names no Case | Not loaded | `UnknownCase` |
| Bad Notation | `R Q U` | Not loaded | `BadAlgorithmNotation` with `NotationError` |
| Blank Notation | `""` | Not loaded | `EmptyAlgorithm` |
| Set or family disagrees with ID | `case-pll-t` with `set: OLL`, or family `oll-t` on a PLL Case | Not loaded | `CaseSetMismatch` / `MalformedFamily` |
| Missing name string | `name` key absent from `en.json` | Not loaded | Existing `MissingTextKey` |
| Unknown JSON key or `set` value | Extra field, `"set": "F2L"` | Not loaded | Existing `ContentLoadError.Malformed` |

</intent-contract>

## Code Map

- `core/domain/src/main/kotlin/dev/cubentia/domain/content/Model.kt` -- `BundleManifest`, `ContentBundle` (ctor also used at `app/src/sharedTest/kotlin/dev/cubentia/app/session/TestLessons.kt:129`).
- `core/domain/src/main/kotlin/dev/cubentia/domain/content/Ids.kt` -- value-class ID pattern with `isWellFormed`; `TextKey.contentId` must equal the owner ID.
- `core/domain/src/main/kotlin/dev/cubentia/domain/content/ContentLoader.kt:122-135` -- `decode`; the local `read` hashes each file into `contentRevision`, so use it for the new files.
- `core/domain/src/main/kotlin/dev/cubentia/domain/content/ContentValidator.kt:47` -- `validate`; reuse `duplicates` and `textProblems` (line 244).
- `core/domain/src/main/kotlin/dev/cubentia/domain/content/ContentProblem.kt` -- sealed problems; `BadNotation` is Lesson-typed, so Algorithms get their own.
- `core/domain/src/main/resources/content/bundle.json`, `schema/bundle.schema.json`, `strings/en.json` -- extend.
- `core/domain/src/test/kotlin/dev/cubentia/domain/content/SchemaModelTest.kt:31,165,211` -- schema list and `SchemaComparator.node`, which has no enum arm.
- `core/domain/src/test/kotlin/dev/cubentia/domain/content/ShippedContentTest.kt:262` -- the "used and voice" test must learn Case names as titles.
- `core/domain/src/test/resources/content-fixtures/valid/bundle.json`, `missing-stage-file/bundle.json` -- strict manifests that need the new field. `schema-version-2` fails before decoding and stays unchanged.

## Tasks & Acceptance

**Execution:**
- `core/domain/src/main/kotlin/dev/cubentia/domain/content/Ids.kt` -- add `CaseId`, `AlgorithmId`, `FamilyId` value classes with `isWellFormed` (`case-oll-\d{2}` or `case-pll-[a-z]{1,2}`; `alg-oll-\d{2}-[a-z]` or `alg-pll-[a-z]{1,2}-[a-z]`; `(oll|pll)-[a-z]+(-[a-z]+)*`) -- stable namespaces (AD-6).
- `core/domain/src/main/kotlin/dev/cubentia/domain/content/Model.kt` -- add `@Serializable enum class CaseSet { OLL, PLL }`, `Case(id, set, family, name: TextKey)`, `Algorithm(id, caseId, notation)` with `isPrimary` (ID ends `-a`), `AlgorithmFile(cases, algorithms)`; add required `algorithms: List<String>` to `BundleManifest`, and required `cases`, `algorithms` to `ContentBundle` -- the content shape.
- `core/domain/src/main/kotlin/dev/cubentia/domain/content/ContentLoader.kt` -- decode each manifest `algorithms` path with `read`, concatenate in manifest order -- part of `contentRevision`.
- `core/domain/src/main/kotlin/dev/cubentia/domain/content/ContentProblem.kt`, `ContentValidator.kt` -- add the problems in the matrix and validate across all files; Case `name` through `textProblems` with the Case ID as owner -- bad data never loads.
- `core/domain/src/main/resources/content/schema/algorithms.schema.json` -- new draft 2020-12 schema for `AlgorithmFile`, closed objects; `schema/bundle.schema.json` -- require `algorithms` with pattern `^algorithms/[a-z0-9-]+\.json$`.
- `core/domain/src/main/resources/content/algorithms/oll.json`, `pll.json` -- generate from the Design Notes table, one Case and one Algorithm per row; `bundle.json` -- list both; `strings/en.json` -- add `case.<caseId>.name` for all 78.
- `app/src/sharedTest/kotlin/dev/cubentia/app/session/TestLessons.kt` -- pass `shipped.cases`, `shipped.algorithms`.
- `core/domain/src/test/resources/content-fixtures/valid/bundle.json`, `missing-stage-file/bundle.json` -- add `"algorithms": []`.
- `core/domain/src/test/kotlin/dev/cubentia/domain/content/SchemaModelTest.kt` -- add the schema to both lists; add a `SerialKind.ENUM` arm requiring `type: string` and an `enum` array equal to the descriptor's names.
- `core/domain/src/test/kotlin/dev/cubentia/domain/content/ShippedContentTest.kt` -- assert 57 OLL and 21 PLL Cases, 78 Algorithms, every Case exactly one primary, all IDs unique, the OLL numbers 1..57 and PLL names complete, each Notation parsing to Moves, and the family counts in Design Notes; treat Case names as used titles.
- `core/domain/src/test/kotlin/dev/cubentia/domain/content/ContentValidatorTest.kt`, `ContentLoaderTest.kt` -- one test per matrix error row, and one that an algorithm file's bytes change `contentRevision`.

**Acceptance Criteria:**
- Given the shipped bundle, when `ShippedContentTest` runs, then Case and Algorithm IDs are unique and well formed, every Case has exactly one primary Algorithm, and every Notation parses.
- Given the shipped bundle, when it is loaded through `ContentLoader.load(ContentSource.SHIPPED)`, then `bundle.cases` holds 57 OLL and 21 PLL Cases with set and family, and `bundle.algorithms` holds 78 Algorithms declaring their `caseId`.
- Given the content, when it is inspected, then Algorithms and Case names are standard common-knowledge data with no copied diagrams or prose.

## Spec Change Log

## Review Triage Log

### 2026-11-09 — Review pass
- verdicts: 28 findings — high 0, medium 3, low 19, false 6, maybe-false 0
- findings:
  - `[low]` `[reject]` Blind: Case name key area and field are not validated — `textProblems` checks only the owner; ShippedContentTest pins `case.<id>.name` for all 78, and a guard is more than a direct correction.
  - `[low]` `[reject]` Blind: validator and schema accept non-`-a` Algorithm IDs — shipped data is pinned at one `-a` per Case; the wider format only admits the planned P1 alternatives.
  - `[low]` `[patch]` Blind: canonical Notation not enforced — added `Notation.format(parsed.moves) == notation` to ShippedContentTest.
  - `[low]` `[patch]` Blind: no distinctness check on Notation — added a 78-distinct assertion to ShippedContentTest.
  - `[medium]` `[defer]` Blind: provenance not in the PRD memlog — real and open; this run's dispatch forbids writes under planning-artifacts, so the append is handed back (frontmatter `deferred`, note text in Design Notes).
  - `[false]` `[reject]` Blind: `schemaVersion` should change with the manifest — manifests ship inside the build beside the reader, so no older v1 manifest can reach this loader.
  - `[low]` `[reject]` Blind: Algorithm-only edits discard Lesson resume — real but existing behavior of the AD-6 whole-bundle `contentRevision`, met only across app updates; scoping the comparison is new design.
  - `[low]` `[reject]` Blind: schema patterns and Kotlin regexes can drift, manifest path unenforced — same as the existing Stage and Lesson IDs; a parity mechanism is added complexity.
  - `[low]` `[reject]` Blind: primary-ID convention in several places — two short private helpers and one test; moving them adds public surface with no consumer yet.
  - `[low]` `[reject]` Blind: Case IDs and families too open at load — content is authored in the repo and ShippedContentTest pins exact IDs and family counts.
  - `[low]` `[reject]` Edge: Case IDs outside the known 78 load — same as the row above.
  - `[low]` `[reject]` Edge: Case name wrong area or field — same as the first Blind row.
  - `[low]` `[reject]` Edge: unknown well-formed family loads — caught by FAMILY_COUNTS on shipped data.
  - `[low]` `[reject]` Edge: Notation that disturbs F2L loads — the intent limits this story to parsing; Story 9.2 owns correctness.
  - `[low]` `[reject]` Edge: manifest path outside `algorithms/` — same as the schema-pattern row; manifests are repo-authored.
  - `[low]` `[patch]` Gap: `FamilyId` well-formedness unobserved — added `oll-T` inputs, on a well-formed and a malformed Case ID.
  - `[low]` `[patch]` Gap: PLL ID formats have no rejecting test — added `case-pll-abc`, `case-pll-UA`, `alg-pll-t`.
  - `[low]` `[patch]` Gap: `isPrimary` only evaluated on `-a` — added true and false assertions.
  - `[medium]` `[defer]` Gap: per-Case Notation and family pinned only by shape — Notation is Story 9.2; family has no owner, recorded in `deferred`.
  - `[medium]` `[defer]` Intent: AC 2 memlog note absent — same root cause and route as the Blind memlog row.
  - `[false]` `[reject]` Intent: Case names as identifiers versus nicknames — the AC constrains where names come from, and both readings satisfy it.
  - `[false]` `[reject]` Intent: set and family on Case versus Algorithm — the AC attaches them to the Cases; each Algorithm reaches them through `caseId`.
  - `[low]` `[reject]` Intent: Algorithm-to-Case correctness unproven — Story 9.2 owns it.
  - `[low]` `[defer]` Intent: family membership proven by counts only — same root cause and route as the Gap family row.
  - `[false]` `[reject]` Intent: "stable" across releases has no mechanism — this story ships the first IDs, so nothing earlier exists to compare with.
  - `[false]` `[reject]` Intent: validator enforces beyond the content test — that is the existing AD-6 pattern, and the content test still runs the same rules.
  - `[low]` `[reject]` Intent: validator allows `-b` — same as the second Blind row.
  - `[false]` `[reject]` Intent: no production consumer — Stories 9.4 and 9.5 own the Library surface.

## Design Notes

**Provenance (OQ-6).** Notation, OLL numbers and PLL letter names are community common knowledge. They were written from general knowledge; no diagram or prose was copied. Each entry was checked in a scratch simulator: the inverse leaves F2L and centers intact, all 57 OLL masks and 21 PLL classes are distinct, and the Case identity agrees with `docs/oll-algorithms.odg` and `docs/pll-algorithms.odg`, used as a read-only numbering check. Those sheets are third-party and must not ship.

PRD memlog text, for the permitted writer: `Story 9.1 (OQ-6): 78 OLL/PLL Algorithms, OLL numbers and PLL letter names are community common knowledge, written from general knowledge with no copied diagrams or prose; docs/oll-algorithms.odg and docs/pll-algorithms.odg used only as a read-only check of Case numbering.`

**Scope note.** The checks above were one-off. The committed proof that Algorithms are right is Story 9.2.

**Names.** OLL Cases are `OLL <n>`, because nicknames vary between sources. PLL Cases are `<Letters> perm`, for example `Ua perm`.

**File shape.**
```json
{ "cases": [{ "id": "case-oll-45", "set": "OLL", "family": "oll-t", "name": "case.case-oll-45.name" }],
  "algorithms": [{ "id": "alg-oll-45-a", "caseId": "case-oll-45", "notation": "F R U R' U' F'" }] }
```

**Family counts.** oll-dot 8, oll-cross 7, oll-t 2, oll-square 2, oll-c 2, oll-w 2, oll-p 4, oll-line 4, oll-fish 4, oll-knight 4, oll-awkward 4, oll-l-shape 6, oll-lightning 6, oll-corners-oriented 2; pll-edges-only 4, pll-corners-only 3, pll-adjacent-swap 10, pll-diagonal-swap 4.

**Data.** Use these rows exactly. Algorithm ID is `alg-` + Case suffix + `-a`.

| Case ID | set | family | name (en.json) | primary Algorithm Notation |
|---|---|---|---|---|
| case-oll-01 | OLL | oll-dot | OLL 1 | `R U2 R2 F R F' U2 R' F R F'` |
| case-oll-02 | OLL | oll-dot | OLL 2 | `F R U R' U' F' f R U R' U' f'` |
| case-oll-03 | OLL | oll-dot | OLL 3 | `f R U R' U' f' U' F R U R' U' F'` |
| case-oll-04 | OLL | oll-dot | OLL 4 | `f R U R' U' f' U F R U R' U' F'` |
| case-oll-05 | OLL | oll-square | OLL 5 | `r' U2 R U R' U r` |
| case-oll-06 | OLL | oll-square | OLL 6 | `r U2 R' U' R U' r'` |
| case-oll-07 | OLL | oll-lightning | OLL 7 | `r U R' U R U2 r'` |
| case-oll-08 | OLL | oll-lightning | OLL 8 | `l' U' L U' L' U2 l` |
| case-oll-09 | OLL | oll-fish | OLL 9 | `R U R' U' R' F R2 U R' U' F'` |
| case-oll-10 | OLL | oll-fish | OLL 10 | `R U R' U R' F R F' R U2 R'` |
| case-oll-11 | OLL | oll-lightning | OLL 11 | `r U R' U R' F R F' R U2 r'` |
| case-oll-12 | OLL | oll-lightning | OLL 12 | `M' R' U' R U' R' U2 R U' M` |
| case-oll-13 | OLL | oll-knight | OLL 13 | `F U R U' R2 F' R U R U' R'` |
| case-oll-14 | OLL | oll-knight | OLL 14 | `R' F R U R' F' R F U' F'` |
| case-oll-15 | OLL | oll-knight | OLL 15 | `l' U' l L' U' L U l' U l` |
| case-oll-16 | OLL | oll-knight | OLL 16 | `r U r' R U R' U' r U' r'` |
| case-oll-17 | OLL | oll-dot | OLL 17 | `F R' F' R2 r' U R U' R' U' M'` |
| case-oll-18 | OLL | oll-dot | OLL 18 | `r U R' U R U2 r2 U' R U' R' U2 r` |
| case-oll-19 | OLL | oll-dot | OLL 19 | `M U R U R' U' M' R' F R F'` |
| case-oll-20 | OLL | oll-dot | OLL 20 | `r U R' U' M2 U R U' R' U' M'` |
| case-oll-21 | OLL | oll-cross | OLL 21 | `R U2 R' U' R U R' U' R U' R'` |
| case-oll-22 | OLL | oll-cross | OLL 22 | `R U2 R2 U' R2 U' R2 U2 R` |
| case-oll-23 | OLL | oll-cross | OLL 23 | `R2 D R' U2 R D' R' U2 R'` |
| case-oll-24 | OLL | oll-cross | OLL 24 | `r U R' U' r' F R F'` |
| case-oll-25 | OLL | oll-cross | OLL 25 | `F' r U R' U' r' F R` |
| case-oll-26 | OLL | oll-cross | OLL 26 | `R U2 R' U' R U' R'` |
| case-oll-27 | OLL | oll-cross | OLL 27 | `R U R' U R U2 R'` |
| case-oll-28 | OLL | oll-corners-oriented | OLL 28 | `r U R' U' M U R U' R'` |
| case-oll-29 | OLL | oll-awkward | OLL 29 | `R U R' U' R U' R' F' U' F R U R'` |
| case-oll-30 | OLL | oll-awkward | OLL 30 | `F R' F R2 U' R' U' R U R' F2` |
| case-oll-31 | OLL | oll-p | OLL 31 | `R' U' F U R U' R' F' R` |
| case-oll-32 | OLL | oll-p | OLL 32 | `L U F' U' L' U L F L'` |
| case-oll-33 | OLL | oll-t | OLL 33 | `R U R' U' R' F R F'` |
| case-oll-34 | OLL | oll-c | OLL 34 | `R U R2 U' R' F R U R U' F'` |
| case-oll-35 | OLL | oll-fish | OLL 35 | `R U2 R2 F R F' R U2 R'` |
| case-oll-36 | OLL | oll-w | OLL 36 | `L' U' L U' L' U L U L F' L' F` |
| case-oll-37 | OLL | oll-fish | OLL 37 | `F R' F' R U R U' R'` |
| case-oll-38 | OLL | oll-w | OLL 38 | `R U R' U R U' R' U' R' F R F'` |
| case-oll-39 | OLL | oll-lightning | OLL 39 | `L F' L' U' L U F U' L'` |
| case-oll-40 | OLL | oll-lightning | OLL 40 | `R' F R U R' U' F' U R` |
| case-oll-41 | OLL | oll-awkward | OLL 41 | `R U R' U R U2 R' F R U R' U' F'` |
| case-oll-42 | OLL | oll-awkward | OLL 42 | `R' U' R U' R' U2 R F R U R' U' F'` |
| case-oll-43 | OLL | oll-p | OLL 43 | `F' U' L' U L F` |
| case-oll-44 | OLL | oll-p | OLL 44 | `F U R U' R' F'` |
| case-oll-45 | OLL | oll-t | OLL 45 | `F R U R' U' F'` |
| case-oll-46 | OLL | oll-c | OLL 46 | `R' U' R' F R F' U R` |
| case-oll-47 | OLL | oll-l-shape | OLL 47 | `F' L' U' L U L' U' L U F` |
| case-oll-48 | OLL | oll-l-shape | OLL 48 | `F R U R' U' R U R' U' F'` |
| case-oll-49 | OLL | oll-l-shape | OLL 49 | `r U' r2 U r2 U r2 U' r` |
| case-oll-50 | OLL | oll-l-shape | OLL 50 | `r' U r2 U' r2 U' r2 U r'` |
| case-oll-51 | OLL | oll-line | OLL 51 | `F U R U' R' U R U' R' F'` |
| case-oll-52 | OLL | oll-line | OLL 52 | `R U R' U R U' B U' B' R'` |
| case-oll-53 | OLL | oll-l-shape | OLL 53 | `r' U' R U' R' U R U' R' U2 r` |
| case-oll-54 | OLL | oll-l-shape | OLL 54 | `r U R' U R U' R' U R U2 r'` |
| case-oll-55 | OLL | oll-line | OLL 55 | `R' F R U R U' R2 F' R2 U' R' U R U R'` |
| case-oll-56 | OLL | oll-line | OLL 56 | `r U r' U R U' R' U R U' R' r U' r'` |
| case-oll-57 | OLL | oll-corners-oriented | OLL 57 | `R U R' U' M' U R U' r'` |
| case-pll-aa | PLL | pll-corners-only | Aa perm | `x R' U R' D2 R U' R' D2 R2 x'` |
| case-pll-ab | PLL | pll-corners-only | Ab perm | `x R2 D2 R U R' D2 R U' R x'` |
| case-pll-e | PLL | pll-corners-only | E perm | `x' L' U L D' L' U' L D L' U' L D' L' U L D x` |
| case-pll-f | PLL | pll-adjacent-swap | F perm | `R' U' F' R U R' U' R' F R2 U' R' U' R U R' U R` |
| case-pll-ga | PLL | pll-adjacent-swap | Ga perm | `R2 U R' U R' U' R U' R2 U' D R' U R D'` |
| case-pll-gb | PLL | pll-adjacent-swap | Gb perm | `R' U' R U D' R2 U R' U R U' R U' R2 D` |
| case-pll-gc | PLL | pll-adjacent-swap | Gc perm | `R2 U' R U' R U R' U R2 U D' R U' R' D` |
| case-pll-gd | PLL | pll-adjacent-swap | Gd perm | `R U R' U' D R2 U' R U' R' U R' U R2 D'` |
| case-pll-h | PLL | pll-edges-only | H perm | `M2 U M2 U2 M2 U M2` |
| case-pll-ja | PLL | pll-adjacent-swap | Ja perm | `L' U' L F L' U' L U L F' L2 U L` |
| case-pll-jb | PLL | pll-adjacent-swap | Jb perm | `R U R' F' R U R' U' R' F R2 U' R'` |
| case-pll-na | PLL | pll-diagonal-swap | Na perm | `R U R' U R U R' F' R U R' U' R' F R2 U' R' U2 R U' R'` |
| case-pll-nb | PLL | pll-diagonal-swap | Nb perm | `R' U R U' R' F' U' F R U R' F R' F' R U' R` |
| case-pll-ra | PLL | pll-adjacent-swap | Ra perm | `R U' R' U' R U R D R' U' R D' R' U2 R'` |
| case-pll-rb | PLL | pll-adjacent-swap | Rb perm | `R' U2 R U2 R' F R U R' U' R' F' R2` |
| case-pll-t | PLL | pll-adjacent-swap | T perm | `R U R' U' R' F R2 U' R' U' R U R' F'` |
| case-pll-ua | PLL | pll-edges-only | Ua perm | `R U' R U R U R U' R' U' R2` |
| case-pll-ub | PLL | pll-edges-only | Ub perm | `R2 U R U R' U' R' U' R' U R'` |
| case-pll-v | PLL | pll-diagonal-swap | V perm | `R' U R' U' y R' F' R2 U' R' U R' F R F y'` |
| case-pll-y | PLL | pll-diagonal-swap | Y perm | `F R U' R' U' R U R' F' R U R' U' R' F R F'` |
| case-pll-z | PLL | pll-edges-only | Z perm | `M' U M2 U M2 U M' U2 M2` |

## Verification

**Commands:**
- `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk; ./gradlew spotlessCheck build` -- expected: BUILD SUCCESSFUL, including `:core:domain` content tests and Kover.

## Auto Run Result

**Summary.** All 57 OLL and 21 PLL Cases and their 78 primary Algorithms ship as validated JSON, loaded through `ContentLoader` into `ContentBundle.cases` and `.algorithms`.

**Open item: AC 2 is not fully met.** The sources note is not in the PRD memlog, because this run was dispatched with a rule against writing under `_bmad-output/planning-artifacts`. To close it, someone permitted to write there runs `uv run _bmad/scripts/memlog.py append --path _bmad-output/planning-artifacts/prds/prd-RubikXplorer-2026-10-29/.memlog.md --type decision --text "<the note in Design Notes>"`.

**Files changed.**
- `core/domain/src/main/kotlin/dev/cubentia/domain/content/` -- `Ids.kt`, `Model.kt`, `ContentLoader.kt`, `ContentProblem.kt`, `ContentValidator.kt`: the new IDs, model, loading and 11 validation problems.
- `core/domain/src/main/resources/content/` -- `algorithms/oll.json`, `algorithms/pll.json`, `schema/algorithms.schema.json`, `schema/bundle.schema.json`, `bundle.json`, `strings/en.json`: the data, shape and 78 names.
- `core/domain/src/test/` -- `SchemaModelTest`, `ShippedContentTest`, `ContentValidatorTest`, `ContentLoaderTest` and two fixture manifests.
- `app/src/sharedTest/kotlin/dev/cubentia/app/session/TestLessons.kt` -- passes the new bundle fields.

**Review.** 5 patches applied, all low. 2 entries deferred: the memlog note, and per-Case Notation and family (Notation is Story 9.2; family needs an owner by Story 9.4). Every rejected finding and its reason is in the Review Triage Log.

**Follow-up review:** not recommended. Patched by verdict: high 0, medium 0, low 5.

**Verification.** `./gradlew spotlessCheck build` passed after the patches; `ContentValidatorTest` ran 35 tests and `ShippedContentTest` 21, with no failures or skips. The scratch simulator confirmed the shipped data: F2L and centers preserved, 57 distinct OLL masks and 21 distinct PLL classes. No instrumented or device runs.

**Residual risks.** The committed tests prove parsing and shape only, until Story 9.2. Any build with these files gets a new `contentRevision`, so saved sessions from an earlier build will not resume. `docs/oll-algorithms.odg` and `docs/pll-algorithms.odg` are third-party sheets tracked in the repo; they do not ship, but OQ-6 may warrant a decision on keeping them.
