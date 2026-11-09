---
title: 'Set my Algorithm Status'
type: 'feature'
created: '2026-11-09'
status: 'done'
baseline_revision: 'f92af35fcf740d19c6e8f21d18b6b2db1ba75619'
review_loop_iteration: 0
followup_review_recommended: false
context: []
warnings: ['oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** A Learner cannot record which Algorithms they are learning: nothing stores an Algorithm Status, so Library tiles show none and the status filter always treats everything as *Not started* (FR-18, FR-19).

**Approach:** Add the append-only `algorithm_status_event` table with `SetAlgorithmStatus` as its single writer, expose `Flow<Map<AlgorithmId, AlgorithmStatus>>` through a port, add an explicit status control to Algorithm detail, show the status on every tile, and scope statuses to the current progress epoch so that the existing `ProgressReset` event also resets them.

## Boundaries & Constraints

**Always:** Each status change appends exactly one row and never updates or deletes one; only `SetAlgorithmStatus` writes the table. The current status of an Algorithm is its latest event in the current epoch; an Algorithm with no such event is *Not started*. Reset is the one `ProgressReset` row that *Reset progress* already appends, so a reset is atomic and no status row is touched; statuses from earlier epochs stay in the table and no longer count. The epoch is read inside the same SQL statement that inserts or reads, never cached. All status writes go through the `SerialWriter`, as *Reset progress* does. The status changes only on the detail screen, through the three-option control, showing *Not started*, *Learning* and *Learned* with the current one selected; selecting the option already selected appends nothing. *Not started* shows "Not in your set yet." in detail and no label on the tile; *Learning* and *Learned* show as small text on the tile. Statuses survive restarts. The change ships a Room migration and a migration test. Learner text is content strings or Android resources and every text sets an ink color. The status control is neutral, reusing the Library's filter-chip style, never violet.

**Never:** No `SessionEvent`, session or checkpoint for a status change. No automatic status change anywhere, and no "Add these algorithms to Learning?" offer (9.7). No stars, scores, badges or Learned totals, no status counts on the You tab or in the Library header (not in these ACs). No status control on tiles. No `@Update`, `@Delete`, `@Upsert`, or SQL `UPDATE`/`DELETE` on the table. No write to `algorithm_status_event` from `ResetProgress`. No change to reset preferences or calibration. No writes under `_bmad-output/planning-artifacts/`, `_bmad/` or `.claude/`.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Default | No events | Detail selects *Not started* and reads "Not in your set yet."; tile has no status text | No error expected |
| Set Learning | Tap *Learning* in detail | One row appended; detail selects *Learning*, the line is gone; after back, the tile reads `Learning` | No error expected |
| Set Learned | Tap *Learned* from *Learning* | A second row appended; tile reads `Learned` | No error expected |
| Back to Not started | Tap *Not started* from *Learned* | A row appended; "Not in your set yet." returns; tile has no status text; status filter *Not started* includes it | No error expected |
| Re-select current | Tap the selected option | No row appended | No error expected |
| Every tile updates | Two Algorithms set | Each tile shows its own status; the *Learning* filter lists only those marked *Learning* | No error expected |
| Restart | Status set, database closed and reopened | The same status map is emitted | No error expected |
| Reset sheet | Open *Reset progress* | The sheet text lists Algorithm Statuses as erased | No error expected |
| Reset confirmed | Statuses set, confirm reset | One `ProgressReset` appended, status row count unchanged, map empty, tiles show no status | No error expected |
| Set after reset | Status set after a reset | Event carries the new epoch; only it counts | No error expected |
| Reset cancelled | Dismiss the sheet | Statuses unchanged | No error expected |
| Write failure | Repository insert fails | `SetAlgorithmStatus` returns `AlgorithmStatusWrite.Failed`; the control keeps showing the previous status | Sealed result |
| Unknown stored status | Row whose status name is not an enum value | Row ignored; other rows still count | Skipped row |
| Upgrade | Version 5 database | Migrates to 6; existing rows kept; new table empty | No error expected |

</intent-contract>

## Code Map

- `core/domain/src/main/kotlin/dev/cubentia/domain/content/AlgorithmLibrary.kt:4` -- `AlgorithmStatus`; `:30-37` the filter treats an absent entry as `NOT_STARTED`.
- `core/domain/src/main/kotlin/dev/cubentia/domain/tutor/StageProgress.kt:26-27` -- epoch rule mirrored by the SQL: the last `ProgressReset`; epochs only grow (`ResetProgress`: current + 1).
- `core/application/src/main/kotlin/dev/cubentia/application/port/ProgressRepository.kt` -- port and sealed-result shape to follow.
- `core/application/src/main/kotlin/dev/cubentia/application/usecase/ResetProgress.kt` -- reset appends only `ProgressReset`; read-only here.
- `core/application/src/test/kotlin/dev/cubentia/application/usecase/ProgressWriterTest.kt` -- source-scan pattern for the writer guard; its `APPEND_OR_INSERT` check flags any non-allowed file naming `ProgressRepository` or `progress()` and calling `append(`, so `SessionHost` must not call a method named `append`.
- `app/src/main/kotlin/dev/cubentia/app/data/RubikDatabase.kt` -- version 5, `autoMigrations`; `ProgressEventEntity.kt:75` `TYPE_PROGRESS_RESET`; `ProgressDao.kt`, `RoomProgressRepository.kt` -- DAO and adapter patterns.
- `app/schemas/` -- exported Room schemas; `6.json` is generated by the build and must be committed.
- `app/src/main/kotlin/dev/cubentia/app/di/PersistenceModule.kt` -- providers.
- `app/src/main/kotlin/dev/cubentia/app/session/SessionHost.kt:59-96` -- internal and `@Inject` constructors with optional adapters; `:244` `resetProgress()` with the in-memory and `writer.read` branches.
- `app/src/main/kotlin/dev/cubentia/app/ui/library/LibraryScreen.kt:174` -- private `LibraryChip`; `:159` tile call site.
- `app/src/main/kotlin/dev/cubentia/app/ui/library/AlgorithmTile.kt` -- tile body.
- `app/src/main/kotlin/dev/cubentia/app/ui/library/AlgorithmDetailScreen.kt` -- `controls` lambda holds transport and card; add the status below the card.
- `app/src/main/kotlin/dev/cubentia/app/ui/shell/AppShell.kt:368` -- detail call; `:372` `LibraryScreen` call; `ShellContent` top holds the `collectAsState` calls and `scope`.
- `app/src/main/res/values/strings.xml:163` -- `settings_reset_progress_description`.
- `core/domain/src/main/resources/content/strings/en.json:471-473` -- status strings.
- `app/src/test/kotlin/dev/cubentia/app/data/SessionDatabaseMigrationTest.kt` -- `MigrationTestHelper` pattern; `RoomProgressRepositoryTest.kt` -- Room Robolectric pattern.
- `app/src/test/kotlin/dev/cubentia/app/ui/library/AlgorithmDetailScreenTest.kt:103` -- the test asserting no status UI, which 9.6 replaces; `app/src/test/kotlin/dev/cubentia/app/ui/shell/AppShellLibraryTest.kt:40` -- shell harness; `app/src/test/kotlin/dev/cubentia/app/ui/settings/SettingsScreenTest.kt:124` -- reset through the shell.

## Tasks & Acceptance

**Execution:**
- `core/domain/src/main/kotlin/dev/cubentia/domain/content/AlgorithmStatusEvent.kt` -- add `data class AlgorithmStatusEvent(algorithmId: AlgorithmId, status: AlgorithmStatus, at: Instant)` and `object AlgorithmStatuses { fun current(events: List<AlgorithmStatusEvent>): Map<AlgorithmId, AlgorithmStatus> }` (oldest first, last wins) -- the pure fold.
- `core/domain/src/test/kotlin/dev/cubentia/domain/content/AlgorithmStatusesTest.kt` -- empty log, latest wins, independent Algorithms.
- `core/application/src/main/kotlin/dev/cubentia/application/port/AlgorithmStatusRepository.kt` -- port with `fun statuses(): Flow<Map<AlgorithmId, AlgorithmStatus>>` (current epoch only) and `suspend fun append(event: AlgorithmStatusEvent): AlgorithmStatusSave`; sealed `AlgorithmStatusSave { Saved; Failed }` and `AlgorithmStatusWrite { Written(event); Failed }`.
- `core/application/src/main/kotlin/dev/cubentia/application/usecase/SetAlgorithmStatus.kt` -- `suspend operator fun invoke(algorithmId, status, at): AlgorithmStatusWrite`; the single writer.
- `core/application/src/test/kotlin/dev/cubentia/application/usecase/SetAlgorithmStatusTest.kt` -- saved and failed results with a fake repository.
- `core/application/src/test/kotlin/dev/cubentia/application/usecase/AlgorithmStatusWriterTest.kt` -- source scan as in `ProgressWriterTest`: outside `SetAlgorithmStatus` and the adapter, no main file naming `AlgorithmStatusRepository` or `AlgorithmStatusDao` calls `append`/`insert`; the DAO has no mutation besides `INSERT`.
- `app/src/main/kotlin/dev/cubentia/app/data/AlgorithmStatusEventEntity.kt` -- `@Entity(tableName = "algorithm_status_event", indices = [Index("algorithm_id")])` with `seq` autogenerate key, `algorithm_id`, `status` (enum name), `epoch`, `at_ms`; `event(): AlgorithmStatusEvent?` returns null for an unknown status.
- `app/src/main/kotlin/dev/cubentia/app/data/AlgorithmStatusDao.kt` -- `@Query` `INSERT INTO algorithm_status_event (algorithm_id, status, epoch, at_ms) SELECT :algorithmId, :status, COALESCE((SELECT MAX(epoch) FROM progress_event WHERE type = '<TYPE_PROGRESS_RESET>'), 0), :atMs` and `fun currentEpoch(): Flow<List<AlgorithmStatusEventEntity>>` selecting rows whose `epoch` equals that same subquery, `ORDER BY seq`; use the `TYPE_PROGRESS_RESET` constant in both queries -- the epoch is read atomically, and Room re-emits when either table changes.
- `app/src/main/kotlin/dev/cubentia/app/data/RoomAlgorithmStatusRepository.kt` -- adapter: `statuses()` maps through `AlgorithmStatuses.current` and on upstream failure emits an empty map; `append` returns `Saved`/`Failed`, rethrowing cancellation.
- `app/src/main/kotlin/dev/cubentia/app/data/RubikDatabase.kt` -- add the entity, version 6, `AutoMigration(from = 5, to = 6)`, `abstract fun algorithmStatuses(): AlgorithmStatusDao`.
- `app/schemas/dev.cubentia.app.data.RubikDatabase/6.json` -- generated schema, committed (use the actual directory under `app/schemas/`).
- `app/src/main/kotlin/dev/cubentia/app/di/PersistenceModule.kt` -- provide `AlgorithmStatusRepository` and `SetAlgorithmStatus`.
- `app/src/main/kotlin/dev/cubentia/app/session/SessionHost.kt` -- add constructor adapters `algorithmStatusRepository` and `setAlgorithmStatus` (before `reduced` in the internal constructor; required in the `@Inject` one); `val algorithmStatuses: Flow<Map<AlgorithmId, AlgorithmStatus>>` from the repository, or an in-memory `MutableStateFlow` when none is given; `suspend fun setAlgorithmStatus(id, status): Boolean` through `writer.read` when a writer exists, catching as `resetProgress` does; with no repository, every successful `resetProgress()` clears the in-memory map.
- `app/src/main/kotlin/dev/cubentia/app/ui/library/LibraryScreen.kt` -- make `LibraryChip` `internal`; pass `statuses[entry.algorithm.id]` to the tile.
- `app/src/main/kotlin/dev/cubentia/app/ui/library/AlgorithmTile.kt` -- add `status: AlgorithmStatus? = null`; below the Notation, `Text` in `labelSmall`, `inkSecondary`, tag `library:status-label`, for `LEARNING` and `LEARNED` only.
- `app/src/main/kotlin/dev/cubentia/app/ui/library/AlgorithmDetailScreen.kt` -- add `status: AlgorithmStatus = NOT_STARTED`, `onSetStatus: (AlgorithmStatus) -> Unit = {}`; under the card a `FlowRow` of three `LibraryChip`s tagged `algorithmDetail:status:<NAME>`, ignoring a click on the selected option; under it, for `NOT_STARTED`, `msg.library.status.not-in-set` in `inkSecondary`, tag `algorithmDetail:status-line`.
- `app/src/main/kotlin/dev/cubentia/app/ui/shell/AppShell.kt` -- at the top of `ShellContent`, `val algorithmStatuses by host.algorithmStatuses.collectAsState(emptyMap())`; pass statuses to `LibraryScreen`; pass status and `{ scope.launch { host.setAlgorithmStatus(id, it) } }` to detail.
- `core/domain/src/main/resources/content/strings/en.json` -- add `"msg.library.status.not-in-set": "Not in your set yet."`.
- `app/src/main/res/values/strings.xml` -- `settings_reset_progress_description` becomes "Stage progress, attempts and Algorithm Statuses will be erased. Color calibration and preferences are kept."
- `app/src/test/kotlin/dev/cubentia/app/data/RoomAlgorithmStatusRepositoryTest.kt` -- real Room: one row per event, latest wins, unknown status row skipped, file-backed database close and reopen, `ResetProgress` empties the map with the row count unchanged, a later event carries the new epoch, Flow re-emits on set and on reset, failure returns `Failed`.
- `app/src/test/kotlin/dev/cubentia/app/data/SessionDatabaseMigrationTest.kt` -- add 5 to 6: existing rows kept, new table empty.
- `app/src/test/kotlin/dev/cubentia/app/ui/library/AlgorithmDetailScreenTest.kt` -- replace the no-status assertion; default selection and line, each option calls `onSetStatus`, re-select does not, line hidden when not *Not started*.
- `app/src/test/kotlin/dev/cubentia/app/ui/library/LibraryScreenTest.kt` -- tile text for each status, none for *Not started*.
- `app/src/test/kotlin/dev/cubentia/app/ui/shell/AppShellLibraryTest.kt` -- through `AppShell`: set in detail, back, tile shows it, *Learning* filter lists it, set back to *Not started* clears it.
- `app/src/test/kotlin/dev/cubentia/app/ui/settings/SettingsScreenTest.kt` -- sheet lists Algorithm Statuses; through the shell, confirm clears statuses and dismiss keeps them.

**Acceptance Criteria:**
- Given an `algorithm_status_event` table keyed by `algorithmId` and written only by `SetAlgorithmStatus`, when the Learner sets *Not started*, *Learning* or *Learned* in detail, then an event is appended, `Flow<Map<AlgorithmId, Status>>` updates every tile, and *Not started* reads "Not in your set yet."
- Given *Reset progress*, when confirmed, then the sheet has listed Algorithm Statuses and the reset is an appended event, never a delete.

## Spec Change Log

## Review Triage Log

### 2026-11-09 — Review pass
- verdicts: 20 findings — high 0, medium 0, low 17, false 3, maybe-false 0
- findings:
  - `[low]` `[reject]` Blind: a second tap before the Flow updates is dropped by the click guard when it targets the displayed status — needs a write to lose a race measured in milliseconds; the fix adds pending state that conflicts with the matrix row keeping the previous status on failure, and the control shows the truth so the Learner can tap again.
  - `[low]` `[reject]` Blind: repeated taps before persistence can append duplicate events — same millisecond window; a duplicate event does not change the fold.
  - `[low]` `[reject]` Blind: a failed save shows no message — a local insert failure is rare, the control keeps the true status as the matrix specifies, and a message and retry are new surface.
  - `[low]` `[reject]` Blind: `.catch` ends the subscription after a read error — needs a Room read failure on a local table; recovery adds a retry path, and the Flow restarts with the shell.
  - `[low]` `[reject]` Blind: `collectAsState(emptyMap())` presents unloaded data as Not started — the shell collects from its first composition, so the read finishes well before a Learner can reach a tile; a loading state adds branches to two screens.
  - `[low]` `[patch]` Blind: the three chips have no selectable-group relationship — added `Modifier.selectableGroup()` to the status row in `AlgorithmDetailScreen`.
  - `[low]` `[patch]` Blind: shell tests use the in-memory host and bypass the use case, writer and Room — added `AppShellAlgorithmStatusRoomTest`, which runs the shell over real Room.
  - `[low]` `[reject]` Blind: no test interleaves status writes with reset — both go through the one FIFO `SerialWriter`, and `RoomAlgorithmStatusRepositoryTest` covers writes before and after a reset; the verification-gap fix now pins the queueing.
  - `[low]` `[reject]` Blind: the writer guard is lexical and can be bypassed by an inferred DAO — it is the established `ProgressWriterTest` pattern; a semantic guard is new infrastructure for a rule reviewers also check.
  - `[low]` `[reject]` Edge: Learning then Not started before the write lands loses the final choice — same root cause and reasoning as the first row.
  - `[low]` `[patch]` Verification gap: the serialization test asserted zero writes before the child had started — now starts with `CoroutineStart.UNDISPATCHED` and also asserts the result is incomplete before release.
  - `[low]` `[patch]` Intent: set-to-tile is exercised only as separate UI and Room segments — closed by `AppShellAlgorithmStatusRoomTest`.
  - `[low]` `[patch]` Intent: reset through Settings never meets the epoch mechanism — same test confirms from Settings, then asserts one `PROGRESS_RESET`, an unchanged status row count, and no tile label.
  - `[false]` `[reject]` Intent: the sheet-lists reading — the audit records that the actual dialog text is asserted; no bad outcome is claimed.
  - `[low]` `[reject]` Intent: multi-tile display is shown only with a static map — every tile reads the one map, and the shell test proves the map reaches a tile reactively.
  - `[low]` `[reject]` Intent: the writer guard tests text rather than runtime — same as the Blind guard row.
  - `[false]` `[reject]` Intent: the re-select no-op exists only at the composable — this is the specified placement; the status control is the only caller that can re-select, and `SetAlgorithmStatus` appends one event per request by design.
  - `[low]` `[reject]` Intent: no UI test uses a failing host — selection derives only from the Flow, and the host test proves a failed write leaves the Flow unchanged.
  - `[low]` `[reject]` Intent: no forced-process-death test and no `(sessionId, revision)` key — Room is durable once the insert returns and a close-and-reopen test covers restart; status events come from no session, so they have no revision, and no retry path can duplicate them.
  - `[false]` `[reject]` Intent: the control's on-screen reachability — the chips sit in the same `verticalScroll` column as the card, so they are reachable by scrolling at every height.

## Design Notes

**Why an epoch, not a reset row.** `ProgressReset` already opens a new epoch and is written by `ResetProgress`. Tagging each status event with the epoch current at insert makes that one row the reset: the two logs cannot disagree after a partial failure, and `ResetProgress` never writes the status table, which keeps the Ownership Matrix exact. A separate reset row in the status table would need a second, non-atomic write. Cross-table ordering by timestamp is ruled out because times can tie or move backward.

**Why the epoch is in SQL.** A subquery in the `INSERT` and in the observed `SELECT` needs no cached epoch, so a status can't be attributed to a stale one, and the tile state doesn't wait on `SessionHost.restore`. Room observes both tables named in the query, so a reset re-emits the map with no extra plumbing.

**Host placement.** `SessionHost` already owns the serial-writer path and `resetProgress`, and 9.7 calls `SetAlgorithmStatus` from a Lesson end. The method is not a `SessionEvent`.

## Verification

**Commands:**
- `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk; ./gradlew spotlessCheck build` -- expected: BUILD SUCCESSFUL, including `AlgorithmStatusesTest`, `SetAlgorithmStatusTest`, `AlgorithmStatusWriterTest`, `ProgressWriterTest`, `RoomAlgorithmStatusRepositoryTest`, `SessionDatabaseMigrationTest`, `AlgorithmDetailScreenTest`, `LibraryScreenTest`, `AppShellLibraryTest`, `SettingsScreenTest`, and `app/schemas/.../6.json` exists.

## Auto Run Result

**Summary.** A Learner can set *Not started*, *Learning* or *Learned* on Algorithm detail. Each change appends one row to the new `algorithm_status_event` table through `SetAlgorithmStatus`, and `Flow<Map<AlgorithmId, AlgorithmStatus>>` updates every tile and the status filter. *Not started* reads "Not in your set yet." in detail and shows no tile label. The *Reset progress* dialog now lists Algorithm Statuses, and the reset is the one `ProgressReset` row that already existed, because status rows are scoped to the current epoch.

**Files changed.**
- `core/domain/.../content/AlgorithmStatusEvent.kt` -- event type and pure fold.
- `core/domain/src/main/resources/content/strings/en.json` -- "Not in your set yet."
- `core/application/.../port/AlgorithmStatusRepository.kt` -- port and sealed results.
- `core/application/.../usecase/SetAlgorithmStatus.kt` -- the single writer.
- `app/.../data/AlgorithmStatusEventEntity.kt`, `AlgorithmStatusDao.kt`, `RoomAlgorithmStatusRepository.kt` -- table, epoch-scoped SQL, adapter.
- `app/.../data/RubikDatabase.kt`, `app/schemas/dev.cubentia.app.data.RubikDatabase/6.json` -- version 6 and automigration.
- `app/.../di/PersistenceModule.kt`, `app/.../session/SessionHost.kt` -- wiring, status Flow and serialized write.
- `app/.../ui/library/AlgorithmDetailScreen.kt`, `AlgorithmTile.kt`, `LibraryScreen.kt`, `app/.../ui/shell/AppShell.kt` -- control, tile label, shared chip, shell wiring.
- `app/src/main/res/values/strings.xml` -- reset dialog text.
- Tests: `AlgorithmStatusesTest`, `SetAlgorithmStatusTest`, `AlgorithmStatusWriterTest`, `RoomAlgorithmStatusRepositoryTest`, `SessionHostAlgorithmStatusTest`, `AppShellAlgorithmStatusRoomTest`, and additions to `SessionDatabaseMigrationTest`, `AlgorithmDetailScreenTest`, `LibraryScreenTest`, `AppShellLibraryTest`, `SettingsScreenTest`.

**Review.** 20 findings. Patched: 5 rows (3 root causes), all low. Deferred: none. Rejected: 15 (low 12, false 3), each with its reason in the Review Triage Log.

**Follow-up review: not recommended.** Patched by verdict: high 0, medium 0, low 5 rows.

**Verification.** `./gradlew spotlessApply spotlessCheck build` was BUILD SUCCESSFUL after the patches: 1467 tests, 0 failed, 0 skipped, by the JUnit XML reports. All 14 matrix rows have a covering test that ran. No instrumented or device runs, so the drawn appearance, TalkBack and real process death are unverified.

**Residual risks.** A tap made in the milliseconds before the previous write lands can be dropped or duplicated, and the control then shows the stored status. A failed save shows no message. The status chips reuse `FilterChip`, so TalkBack announces them as checkable items inside a selectable group.
