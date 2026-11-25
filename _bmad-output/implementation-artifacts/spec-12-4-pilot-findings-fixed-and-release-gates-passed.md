---
title: 'Pilot findings fixed and release gates passed'
type: 'chore'
created: '2026-11-24'
status: 'done'
baseline_revision: '5bb90a31da74477750c5d047f1efcb905b12f5c9'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-12-context.md'
warnings: ['oversized']
deferred:
  - summary: >-
      Release order: Story 10.11 (raises camera flags, checks gate 6) runs after Story 12.5, whose last AC promotes to production.
    evidence: |-
      epics.md Story 10.11 depends on 12.3, 12.4, 12.5 and 13.6 and is "the last PR before the tag"; Story 12.5 AC4 moves the build closed testing to production. Production could therefore ship before gate 6 and the data-safety re-confirmation run for raised camera flags. Planning-doc ordering; needs a PO decision (bmad-correct-course).
    location: >-
      _bmad-output/implementation-artifacts/epic-12-context.md (Release order with camera work)
    severity: medium (unverified)
---

<intent-contract>

## Intent

**Problem:** Story 12.4 (Refs: PRD §7 gates 1–6, §8 SM-1–SM-9 · AD-14 · OQ-5) needs one release-gate checklist that links each gate to passing evidence and records how every severity-1/2 usability issue was resolved. `docs/release-gates-v1.0.md` is a 2026-11-19 draft that predates the 2026-11-23/24 device sessions, the issue-orchestrate fixes and the failed v0.10.0 release run. Separately, `epic-12-context.md` lost the AD-4 commit-before-animate, session-integrity and en.json `checkLearnerText` rules (open action item `epic-12-deferred-12.3-epic-12-context-rules`).

**Approach:** Update the existing draft in place, as a checklist run against `main` at `9862b71b9f16b0f32905d9f7e1e7406c35373990`. Link the PO's triage record: the issues, their PRs and the device-session evidence. Do not run a new triage. State plainly what evidence is missing (beginner pilot #127, SM baselines, a green device-suite run after v0.9.0) and list it as open follow-ups. The epic context is already recompiled in the working tree with the three rules. Close the action item.

## Boundaries & Constraints

**Always:** Binding AD from the Refs line: **AD-14** (Quality gates in CI). Every gate row links real evidence: CI or release run URLs, test class names, PRs, evidence READMEs. Severities are quoted from their source, not re-judged. Every revision recorded for the first time uses its full 40-character SHA. Dates are absolute. Facts that must appear verbatim in substance: v0.10.0 was tagged at `23e1de476278a7decdd0308e55b78f8582975582`. Its Release pilot run [45514196023](https://github.com/hebelmx/RubikXplorer/actions/runs/45514196023) failed at the instrumentation job: Key Flow flow2, flow3, flow6 and flow8 failed (flow1, 7 and 9 passed) because the androidTest suite lagged the 14.1 entry-level picker and the #352/#253 editor changes. Nothing was uploaded. PR #360 (merge `9862b71b9f16b0f32905d9f7e1e7406c35373990`) fixed the tests (test-only; 7/7 flows pass locally on the `GalaxyS24FE_API36` emulator, offline). The v0.10.0 tag was deleted. No newer release run exists, so the latest green release device-suite run is still v0.9.0 ([43358827489](https://github.com/hebelmx/RubikXplorer/actions/runs/43358827489), `efa3b589fd305702774aafe81730ac398355d80d`, 2026-11-13).

**Never:** create, push or delete git tags; touch Play Console, `.github/workflows/release.yml` or any release workflow; edit `_bmad-output/planning-artifacts/`, `_bmad/` or `.claude/skills/`; invent pilot measurements, SM baselines, vitals numbers or a PO confirmation that is not recorded; change app or test code (this story is evidence and documentation only); re-run a severity triage; push.

</intent-contract>

## Code Map

- `docs/release-gates-v1.0.md` -- the draft checklist to update (gates table, Gate 6 section, SM table, triage table, "Still needed" list).
- `docs/evidence/release-device-session/README.md` -- device-session evidence: Block 1 release-code smoke (1.1–1.4 PASS; 1.3 offline on the Lenovo benchmark build = release code, R8, debug-signed, `dfc5c3d6864968e455f09de4bfd3e0ab87e8228b`); Block 5 `Cube3DRenderSyncTest` 4/4 and `EndToEndSyncVerificationTest` pass on the Galaxy, Lenovo 10k-step test passes only with a 15-minute timeout (#314 open); 6.8 F4 = #354 proposed severity 2; 6.9 re-check of PR #357 PASS.
- `docs/evidence/12-3/README.md` -- offline Key Flow runs (emulator, debug) and PO items: the release build installed from a Play track and run offline on a physical device is still open; Android vitals "Pending" (internal track, not enough data); data-safety form Pass.
- `docs/accessibility-manual-check.md` -- PO reported the device pass 2026-11-13; the per-flow table is still empty. Device-session 6.8 adds TalkBack + 200% checks for the editor (#253).
- `core/domain/src/test/kotlin/dev/cubentia/domain/tutor/RecoveryPathScenarioTest.kt` -- gate 2 audit: one named scenario per `ErrorCategory` (7/7: wrong move or direction, wrong piece, wrong piece orientation, unexpected orientation, completed layer disturbed, invalid entered state, unknown discrepancy) plus `every category has a scenario here` asserting 7 entries. The "coverage not re-audited" caveat can be removed.
- `build-logic/src/main/kotlin/dev/cubentia/buildlogic/policy/FeatureFlagLevels.kt` -- release levels: `ADAPTIVE_ASSISTANCE` GA, `TEST_ME` BETA, every camera flag OFF (gate 6 N/A for this build; PRD gate 6 says Story 10.11 checks it for each raised feature).
- `_bmad-output/implementation-artifacts/sprint-status.yaml` -- action item `epic-12-deferred-12.3-epic-12-context-rules` (~line 1463, `status: open`); `epic-10-deferred-10-21-po-beta-quick-scan-off-pipeline-rethink` (~line 2410) records the PO's 2026-11-24 call: QUICK_SCAN stays OFF.
- `_bmad-output/implementation-artifacts/epic-12-context.md` -- recompiled in this run (uncommitted); now carries AD-4 commit-before-animate, session integrity and the en.json `checkLearnerText` rule. Commit as is.
- CI on `main`, latest green: [45520622483](https://github.com/hebelmx/RubikXplorer/actions/runs/45520622483) at `9862b71b9f16b0f32905d9f7e1e7406c35373990` (merge of #360, 2026-11-25 UTC).

Severity-1/2 triage record (sources: the 2026-11-19 draft table and the device-session README; the PO triaged through the issue-orchestrate runs and the device sessions):

| Issue | Severity (source) | Resolution |
| --- | --- | --- |
| #206 gestures misfire | 1 (internal testing) | Fixed, PR #213 (Story 12.16, `aa0ef871943fe2aff440780d93c0b6dbf99e698e`); closed |
| #207 view rotated / Reset view | 2 (internal testing) | Fixed, PR #214 (Story 12.17, `c2f56134eb52849efc698bb07a0e71431c696090`); closed |
| #354 editor Close discards without asking | 2 proposed (device session 6.8, F4) | Fixed, PR #357 (`3cf4307222fee4bc07f6bbe02823ad5ddbb2a2cd`); re-checked 6.9 (PR #359); closed |
| #194 Quick Scan re-asked face merged 180° | 1 (device session C) | Fixed, PR #217 (`1bc07e41f00d3435db09f21ac7312e883969fcdb`); closed |
| #195 Quick Scan corner guide can't be matched | 1 (device session C) | Open. Not reachable in this release: QUICK_SCAN is OFF in release (FeatureFlagLevels; PO call 2026-11-24). Must be fixed or re-triaged before a Story 10.11 run raises QUICK_SCAN |
| #196 Quick Scan corners fail on dark glossy backgrounds | 2 (device session C) | Same as #195 |

## Tasks & Acceptance

**Execution:**
- `docs/release-gates-v1.0.md` -- Rewrite in place, keeping its structure. Heading/status: "Release gates for v1.0", checklist run 2026-11-24 against `main` `9862b71b…` (full SHA). Say plainly that gates 1, 2 and 6 pass or are N/A, gate 3 is partial, gate 4 is missing and gate 5 is partial, so production is not cleared. Gates table: replace CI 44470618432 with 45520622483. Gate 2: record the 7/7 audit and drop the caveat. Gate 3: add the 2026-11-23 device-session Block 5 results and the v0.10.0 facts from Boundaries; status Partial. Gate 4: still Missing (#127 open, no pilot record). Gate 5: add Block 1 1.3/1.4 (release-code offline, no permissions) and the 6.8 TalkBack/200% checks; it stays Partial with the open items named. Gate 6: N/A for this build, add the Story 10.11 sentence. SM table: unchanged values ("Not measured yet"), each marked as an open follow-up of #127. Replace the triage section with "Severity-1 and severity-2 issues" (table above) plus one line that lower-severity and v1.x items stay in GitHub, linking the device-session README and SCP 2026-11-24. "Still needed": pilot #127 and SM baselines; any pilot sev-1/2 findings; a green release device-suite run on the release candidate (PO's next tag, after PR #360); #314 device-tiered timeout; the per-flow a11y table; a release build offline on a physical device from a Play track; closed-testing vitals; #195/#196 before any QUICK_SCAN raise.
- `_bmad-output/implementation-artifacts/sprint-status.yaml` -- set `epic-12-deferred-12.3-epic-12-context-rules` to `status: done`, using the same format other done action items use. Leave the story's own status line alone.
- `_bmad-output/implementation-artifacts/epic-12-context.md` -- commit as already recompiled. Edit only if a fact in it is wrong.

**Acceptance Criteria:**
- Given `docs/release-gates-v1.0.md`, when a reviewer reads the gates table, then gates 1–5 each link to concrete evidence (run URLs, test classes, PRs or evidence files) with an honest status, and gate 6 reads N/A for v1.0 because every camera flag is OFF in release and there is no `CAMERA` permission.
- Given the same file, when a reviewer reads the severity section, then every severity-1/2 issue listed above appears with its source, its PR or explicit resolution, and links to the device-session evidence.
- Given the same file, when a reviewer looks for SM-1–SM-9, then each shows "Not measured yet" (SM-8 N/A) and the pilot (#127) appears as an open follow-up. No number is invented.
- Given the same file, when a reviewer looks for the v0.10.0 run, then the tag commit, run 45514196023, the four failing flows, the cause, PR #360 `9862b71b…`, the deleted tag, and v0.9.0 run 43358827489 as the latest green device-suite run are all stated.
- Given `epic-12-context.md`, when it is read, then it starts with `# Epic 12 Context:` and carries AD-4 commit-before-animate, session integrity and the en.json `checkLearnerText` rule; and the action item reads `status: done`.

## Spec Change Log

## Review Triage Log

### 2026-11-24 — Review pass
- verdicts: 31 findings — high 0, medium 4, low 24, false 3, maybe-false 0
- layers: Codex unavailable (usage limit, reset ~19:42 CST), so all four layers ran as Claude subagents
- findings:
  - `[medium]` `[patch]` (gap) Gate 2 says the guard test "asserts all 7 are covered"; it only pins ErrorCategory.entries.size at 7 — reworded: one named scenario per category, the guard pins the count.
  - `[low]` `[reject]` (intent) AC1 SM baselines not written; the PO clarification (3) directs recording them as open follow-ups — done in the SM table.
  - `[low]` `[patch]` (intent) Gate 6 read "N/A for this build" instead of the AC's "N/A for v1.0" — table, heading and status line now read N/A for v1.0 (camera flags OFF, no CAMERA).
  - `[low]` `[reject]` (intent) #195/#196 resolution holds for this build only — already stated, with the rule that they are fixed or re-triaged before any QUICK_SCAN raise.
  - `[low]` `[reject]` (intent) no GitHub severity labels exist; record lives in docs — matches clarification (1), which names the device-session README and git/GitHub sources.
  - `[low]` `[reject]` (intent) gate 3 post-v0.9.0 device evidence is manual device-session runs, not a release run — stated as Partial; a release run needs a tag, which the intent forbids.
  - `[low]` `[reject]` (intent) epic context was extended rather than recompiled — clarification (4) allows either (it was in fact regenerated by the compile subagent from the planning docs).
  - `[low]` `[patch]` (blind) Block 5 link opened the section showing the Lenovo failure — both Block 5 sections linked.
  - `[medium]` `[patch]` (blind) Gate 3 omitted the flaky Lenovo physicalSwitch test and 5.3–5.5 not run — stated in the row and in Still needed.
  - `[medium]` `[patch]` (blind) "fixed or explicitly resolved" overstated open #195/#196 — now: four fixed, two open but unreachable (QUICK_SCAN OFF, PO call 2026-11-24).
  - `[low]` `[patch]` (blind) #354 severity still "proposed", PO-confirmation follow-up dropped — note plus Still-needed item added.
  - `[low]` `[patch]` (blind) other 2026-11-23/24 issues not addressed for severity — line added: #313–#326, #337 carry no recorded severity, none recorded as 1 or 2.
  - `[false]` `[reject]` (blind) story exit state undecided — build-auto never sets the story's sprint-status line (the caller owns it), and this Auto Run Result names the open AC1/gate-4 evidence.
  - `[low]` `[reject]` (blind) Verification expects three files but four change — the fix is an edit to this spec (the fourth file is the spec itself).
  - `[low]` `[patch]` (blind) sprint-status edit used an inline comment, not the neighbour's evidence: field — changed to an evidence: field; validate ok.
  - `[low]` `[patch]` (blind) epic-12-context Goal said Epics 14–15 are "all behind flags" — reworded: unflagged except camera ACs.
  - `[low]` `[patch]` (blind) mixed dates/time zones — zone stated once (CST), UTC equivalents given.
  - `[low]` `[patch]` (blind) dropped #119, #200–#205 rows and Block 1 S3 notes — traceability line added.
  - `[false]` `[reject]` (blind) Gate 5 a11y claims rest on the empty per-flow table — the 6.8/6.9 claims link the device-session README directly; the empty table is stated as open.
  - `[low]` `[patch]` (edge) same as gap Gate 2 claim — grouped with the gap finding.
  - `[low]` `[patch]` (edge) Block 5 link — grouped with blind finding.
  - `[low]` `[patch]` (edge) flaky Lenovo test — grouped with blind finding.
  - `[low]` `[patch]` (edge) 6.8/6.9 ran debug builds — build type and full revisions stated.
  - `[low]` `[patch]` (edge) Block 1 at dfc5c3d6 predates #321/#352–#354 — stated; the release-candidate smoke re-run folded into the Play-track offline item.
  - `[low]` `[patch]` (edge) time zones — grouped with blind finding.
  - `[low]` `[patch]` (edge) dropped PO severity confirmation — grouped with blind finding.
  - `[low]` `[patch]` (edge) dropped #119 — grouped with blind finding.
  - `[medium]` `[defer]` (edge) epic context: Story 10.11 runs after 12.5, but 12.5 includes the production promotion, so production could ship before gate 6 and data-safety re-confirmation — the ordering conflict comes from the planning docs, not this change; deferred to the PO (planning artifacts are read-only here).
  - `[low]` `[patch]` (edge) sprint-status format — grouped with blind finding.
  - `[low]` `[patch]` (edge) Gate 6 wording vs AC — grouped with intent finding.
  - `[false]` `[reject]` (gap) no verification gaps in behavioral code — the diff has no code; sprint_plan.py validate ran green.

### 2026-11-24 — Review pass (follow-up)
- verdicts: 29 findings — high 0, medium 3, low 20, false 6, maybe-false 0
- layers: no `codex-review.on` marker in this worktree, so all four layers ran as Claude subagents; verification-gap reported no gaps (no finding)
- findings:
  - `[low]` `[reject]` (blind) spec frontmatter says in-review while Auto Run Result says done — the fix edits this build's spec (frontmatter is in-review only while this review runs; finalize sets done).
  - `[low]` `[reject]` (blind) Verification still says "only the three files" — carried: the fix is an edit to this spec.
  - `[low]` `[patch]` (blind) epic-12-context "Release order" bullet and Goal decide the 10.11 vs 12.5 order the spec defers to the PO — reworded: the order is open for a PO decision (deferred from 12.4), 10.11's text named as the newer statement.
  - `[low]` `[reject]` (blind) v1.0 scope "grown to include camera" vs camera-free production — part of the same planning-doc ordering question already in `deferred`; no separate defect in this diff.
  - `[low]` `[patch]` (blind) 6.8 Lenovo editor-paint jank FAIL missing from the checklist — Still-needed item added with the README anchor and action item `issue-253-deferred-editor-turn-search-main-thread`.
  - `[false]` `[reject]` (blind) #325 (scan Close drops faces) not tracked before a camera raise — `gh issue view 325`: CLOSED.
  - `[low]` `[patch]` (blind) device-session Blocks 2, 3, 4, 7 and Block 6 camera items not listed as open — Still-needed item added (README resume plan; #198/#200 noted closed in GitHub).
  - `[false]` `[reject]` (blind) #127's dependency on Story 13.6 not named — sprint-status: `13-6-register-the-privacy-policy-in-play-console: done`, so no open blocker is hidden.
  - `[low]` `[patch]` (blind) 6.9 cited without its "not covered" caveat — caveat added to gate 5.
  - `[false]` `[reject]` (blind) PR #360 "7 of 7 flows" has no evidence link — PR #360 is linked and its body records `OFFLINE=1 tools/run-keyflow-rotation.sh`, all 7 flows pass.
  - `[low]` `[patch]` (blind) 6.8/6.9 links point at the README root — section anchors added.
  - `[low]` `[patch]` (blind) gate-5 cell repeats the dfc5c3d6 SHA and the benchmark-build note — duplicate SHA removed (restructuring the cell rejected as cosmetic).
  - `[low]` `[patch]` (blind) dropped "link the final CI and release run URLs for the release-candidate commit" — restored in Still needed.
  - `[low]` `[patch]` (blind) sprint-status evidence names no fixed source — this spec's path added to the evidence field; validate ok.
  - `[low]` `[patch]` (blind) "issue fixes promoted on 2026-11-20 and 2026-11-24" is vague — the two SCP files named.
  - `[low]` `[patch]` (edge) Block 5 runs cited without revision — `dfc5c3d6864968e455f09de4bfd3e0ab87e8228b`, before #357 and PR #360, stated.
  - `[low]` `[patch]` (edge) "10,000 steps in 183 s" credited to EndToEndSyncVerificationTest — moved to Cube3DRenderSyncTest's 10,000-step test (README line 68).
  - `[low]` `[patch]` (edge) gate 5 cites the release-tag offline Key Flow suite without its latest (failed) outcome — v0.10.0 run 45514196023 failure stated.
  - `[false]` `[reject]` (edge) no checklist item for re-checking gate 6 on a camera raise — the Gate 6 section already records that Story 10.11 checks gate 6 for each feature it raises; this checklist is the camera-free release.
  - `[low]` `[patch]` (edge) #338 left out of the no-severity list — added (label-review issue, same session as #337).
  - `[low]` `[reject]` (edge) spec frontmatter vs Auto Run Result — same as the blind row: fix edits this spec.
  - `[medium]` `[patch]` (edge) severities of #206, #207, #194–#196 lost the source's "proposed" label — each now reads "proposed (…, 2026-11-19 draft)"; note added that no per-issue PO confirmation is recorded, with the PO's 2026-11-24 statement; Still-needed confirmation item covers all six.
  - `[low]` `[patch]` (edge) dropped release-candidate CI/release URL item — grouped with the blind row.
  - `[medium]` `[patch]` (edge) severities quoted as final — grouped with the proposed-severity row.
  - `[medium]` `[patch]` (intent a) proposed severities relabelled as confirmed — grouped with the proposed-severity row.
  - `[low]` `[reject]` (intent b) #195/#196 resolution is a deferral — carried: already stated as open and unreachable, with the pre-raise rule.
  - `[false]` `[reject]` (intent c) story title vs status — carried: build-auto never sets the story's sprint-status line; open evidence is named in Auto Run Result.
  - `[low]` `[patch]` (intent d) epic context picks a side in the release-order conflict — grouped with the blind release-order row.
  - `[false]` `[reject]` (intent e/f) gate 2 "each scenario checks its category's Recovery Path" unbacked; minor notes — RecoveryPathScenarioTest asserts the diagnosed `recoveryPath` in every scenario (lines 60–129); f reports no defect.

## Verification

**Commands:**
- `cd /mnt/e/Dynamic/IndFun/RubikXplorer-12-4 && uv run --no-cache .claude/skills/bmad-sprint-planning/scripts/sprint_plan.py validate` (check `--help` for its arguments) -- expected: ok, or only issues that already existed before this change.
- `cd /mnt/e/Dynamic/IndFun/RubikXplorer-12-4 && git diff --stat` -- expected: only the three files above.
- Every GitHub run, PR and issue number added resolves with `gh run view` / `gh pr view` / `gh issue view`.

**Manual checks:**
- No app, test, workflow, tag or planning-artifact change; every revision recorded for the first time is a full SHA.

## Auto Run Result

Status: done

**Summary:** `docs/release-gates-v1.0.md` is now the Story 12.4 release-gate checklist, run 2026-11-24 against `main` `9862b71b9f16b0f32905d9f7e1e7406c35373990`. Gates 1–2 pass, gate 6 is N/A for v1.0, gates 3 and 5 are partial and gate 4 is missing. Production is not cleared. The severity-1/2 record lists six issues: four fixed with PRs, and #195/#196 open but unreachable while QUICK_SCAN is OFF. The v0.10.0 facts are recorded as given. `epic-12-context.md` was recompiled and carries AD-4 commit-before-animate, session integrity and the en.json `checkLearnerText` rule. Action item `epic-12-deferred-12.3-epic-12-context-rules` is done.

**Files changed:**
- `docs/release-gates-v1.0.md`: the checklist, updated in place from the 2026-11-19 draft.
- `_bmad-output/implementation-artifacts/epic-12-context.md`: recompiled from the planning docs, with the three rules.
- `_bmad-output/implementation-artifacts/sprint-status.yaml`: the context-rules action item is `status: done`, with an `evidence:` field.
- This spec.

**Review:**
- 31 findings triaged. 25 patch rows (several grouped by shared root cause), 1 deferred (release order 10.11 vs 12.5, medium, unverified) and the rest rejected with reasons (see the triage log).
- Patched by verdict: medium 3, low 22.
- Follow-up review recommended: true. Three medium entries were patched. The specific unverified risk is the overstated-evidence class: a claim in the checklist that its linked source does not support. A second pass should re-check each gate row against its linked evidence.

**Verification:**
- `sprint_plan.py validate --status-file …/sprint-status.yaml` is valid with no problems.
- `git diff --stat` shows the four files above only.
- The implementer checked every cited PR, issue and run with `gh`, and the states and SHAs match. No `v0.10.0` tag exists locally or on the remote.
- No app, test, workflow, tag or planning-artifact change.

**Residual risks / open follow-ups (not met by this story):**
- AC1: the beginner pilot (#127) has not run, so the SM-1–SM-9 baselines are not written down (all "Not measured yet").
- AC2 / gate 3: there is no green release device-suite run after v0.9.0 (43358827489). It needs the PO's next tag after PR #360.
- Gate 5: the per-flow a11y table is empty, and the release build has not been run offline from a Play track on a physical device.
- Closed-testing vitals are pending.
- #314: the 10,000-step render-sync test needs a device-tiered timeout.
- The PO has not confirmed #354's proposed severity 2.
- Codex was unavailable (usage limit), so the implementation and review ran on Claude subagents.

### Follow-up review pass (2026-11-24)

**Summary:** a second review re-checked the checklist claims against their sources. The severity column now keeps the source's "proposed" label for all six issues, with a note that no per-issue PO confirmation is recorded beside the PO's 2026-11-24 statement. Gate 3 and gate 5 name the Block 5 revision, credit the 183 s timing to the right test, state the failed v0.10.0 offline Key Flow run and add the 6.9 caveat. "Still needed" gains the release-candidate CI/release URLs, the unrun device-session blocks and the 6.8 editor-paint jank FAIL. The epic context no longer settles the 10.11 vs 12.5 order; it marks it open for the PO.

**Files changed in this pass:** `docs/release-gates-v1.0.md` (claim corrections and follow-ups), `_bmad-output/implementation-artifacts/epic-12-context.md` (release order marked open, SCPs named), `_bmad-output/implementation-artifacts/sprint-status.yaml` (evidence field cites this spec), this spec.

**Review:** 29 findings. Patched by verdict: medium 1 entry (the grouped proposed-severity entry, 3 rows), low 13 entries. Deferred: none new. Rejected: 5 low (spec-edit fixes, carried rows, the scope note covered by the existing deferral), 6 false (see triage log).

**Follow-up review recommended:** false. This is a follow-up pass and no high finding was patched.

**Verification:** `sprint_plan.py validate --status-file _bmad-output/implementation-artifacts/sprint-status.yaml` is valid with no problems. `git diff --stat` shows only the four files above. #198, #200 (closed), #314, #338 (open), #325 (closed), #127 (open) and PR #360 checked with `gh`. No app, test, workflow, tag or planning-artifact change.

**Residual risks:** the README section anchors were derived from GitHub's heading-slug rules and not checked in a rendered view. All open follow-ups from the first pass remain (pilot #127 and SM baselines, a green release device-suite run after v0.9.0, the a11y table, the Play-track offline run, vitals, #314, PO severity confirmation).
