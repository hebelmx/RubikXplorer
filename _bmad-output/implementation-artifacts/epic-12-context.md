# Epic 12 Context: Release-ready for learners and reviewers

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal

Make the app (Cubentia) pass every v1.0 release gate and give a hiring manager a clean five-minute evaluation. It must be accessible, fast and stable on the reference devices, private and offline by construction, with the pilot's findings fixed, a polished Play listing and a public portfolio repo. The epic also carries pre-pilot fixes (12.6–12.13: rebrand, physical-mode honesty, session seams, durable session end) and two v1 playback features added on 2026-11-17 (12.14 view switch, then 12.15 Move pictures and Move strip). Both must ship before Story 12.3 and before the store screenshots of Story 12.5.

## Stories

- Story 12.1: Accessibility audit and fixes
- Story 12.2: Performance and compatibility on the reference devices
- Story 12.3: Stability, size and privacy hardening
- Story 12.4: Pilot findings fixed and release gates passed
- Story 12.5: Store listing and public portfolio repo
- Story 12.6: Rebrand to Cubentia
- Story 12.7: Physical progress is recorded only when true
- Story 12.8: The mismatch ladder resolves a mismatch
- Story 12.9: One source for physical copy; skip-check needs the virtual cube
- Story 12.10: Prove the release-permission gate end to end
- Story 12.11: Sessions end when practice closes or progress resets
- Story 12.12: 2-look rules reconciled
- Story 12.13: Closing a session is durable when a write fails
- Story 12.14: Solution Playback in 3D or on the 2D net
- Story 12.15: Move pictures and the Move strip

## Requirements & Constraints

- **Solution Playback (FR-16):** step forward/back, pause, replay, with a templated note per Move that never claims strategic purpose. In Physical-cube Mode each *Done* advances one step. The Learner can switch between the 3D cube and the 2D net at any step without losing their place. Each Move can show an optional picture; the picture is never the only way the Move is given (notation and note always present).
- **Default playback view (PO decision, binding):** opens in 3D in virtual playback and on the 2D net in Physical-cube Mode (all 54 stickers to compare with EXPECTED). After the Learner's first switch, the remembered choice wins in both modes, across restarts.
- **Preferences (FR-19):** persisted preferences are animation speed, color-blind display, last Teaching Mode, the Solution Playback view (unset until first switch) and *Move pictures* (default on). *Reset preferences* is separate from progress reset and returns both to their defaults (view becomes unset again).
- **Responsiveness and rendering (NFR-1, NFR-2):** input acknowledged within 100 ms; ≥ 30 FPS on the minimum device and 60 FPS on mid-range during face turns and View Rotation. 3D Solution Playback must meet the Story 12.2 baseline. The 2D-net NFR-2 baseline is waived for v1.0 and deferred to v1.1 (#106).
- **Accessibility (NFR-7):** content labels on every control, WCAG AA contrast, dynamic type to 200% with reflow and no truncation, a non-gesture route for every essential action, color-blind glyphs. Learner-facing strings come from `en.json` content keys, not `strings.xml`.
- **Privacy, offline, stability, size:** no account, analytics, ads or crash SDK; no `INTERNET` and no runtime permission in v1.0; works offline from a fresh install; crash-free ≥ 99.5%; installed size < 30 MB.
- **Session integrity (12.7–12.13):** physical progress is written only after explicit confirmation; closing practice or resetting progress really ends the session; a closed session never comes back after a failed write.

## Technical Decisions

- **AD-4 (Commit before animate; the renderer is a projection):** every input becomes a `MoveIntent` → `MoveCommitted`. Presentation is a queue of `Animate`/`Snap` items, each carrying its target `revision` and `CubeState`; more than 3 queued items fast-forward, never dropping a committed Move. `CubeRenderer` never mutates state and exposes `renderedFacelets()`, which must end exactly at the last target state. View Rotation is never a `SessionEvent`. **Playback exception (SCP 2026-11-17):** the 3D adapter replaces the 2D net everywhere except Solution Playback, where both adapters stay and the Learner picks one. The playback view is a preference written by `UpdatePreference`, never a `SessionEvent`; switching builds the other adapter at the committed state (`Snap`) with the same playback position, and nothing in the session changes.
- **AD-20 (Shared playback, generated notes):** one pure `PlaybackController` over `List<Move>` (play, pause, step, back, replay, speed) drives Demonstrations, Solution Playback and the Library through `CubeRenderer`; never checkpointed. Solution Playback text comes only from `MoveNote.of(move): Text`. The Move strip and pictures (12.15) live in the shared transport, so they also appear in Demonstrations and Algorithm detail.
- **AD-17 (Accessibility and adaptive layout):** one `StickerAppearance` table for every sticker surface (renderer and 2D net included); every gesture has a `MoveButtonBar` equivalent; the cube exposes a semantics description derived from `CubeState`; layout keyed on `WindowSizeClass`; composables hold only ephemeral UI state.
- **Ownership:** Preferences have a single writer, `UpdatePreference`. 12.14/12.15 add two preference fields under it — no new port, module, persisted entity or feature flag.
- **AD-3 / AD-10:** one `SessionHost` with a pure `reduce`; progress/resume flags live in `SessionState` and the snapshot; `session/TRANSITIONS.md` is normative.
- **AD-1:** pure logic (e.g. the 12.15 move-to-picture function) lives in a `:core:*` module with no Android imports, JVM-tested; `:app` only draws.
- **Reference devices:** minimum Lenovo Tab M10 Plus 3rd Gen (API 33); mid Samsung Galaxy S24 FE. Galaxy Tab S7 FE results are dated evidence only.
- **GL frame timing:** Macrobenchmark `FrameTimingMetric` cannot see `GLSurfaceView` frames; measure via the in-app `onDrawFrame` interval log on the GL thread (optionally `TraceSectionMetric`). A p90 above 33.3 ms reopens the rendering choice.
- **Benchmark tag:** `Net2DFrameTimingBenchmark.requireNet()` must select the 2D net through the view switch's exact test tag, replacing the `2D|Net` text fallback.
- **Render sync:** `Cube3DRenderSyncTest` checks `renderedFacelets()` against committed state; runs on an emulator in phase 3, otherwise in the phase 5 device session.

## UX & Interaction Patterns

- **Cube stage toolbar (Solution Playback):** a view-switch icon button beside Undo · Redo · Reset. Its icon and TalkBack label name the view it switches *to* ("Show the 2D net" / "Show the 3D cube").
- **Mid-turn switch:** jumps straight to the current step's committed state with no animation; the frame label (VIRTUAL / EXPECTED / VERIFIED), certainty chip and SEEN strip are unchanged. In Physical-cube Mode, *Done*, Undo and any Quick Check result stay shown.
- **3D view on playback:** shows the Move arrow overlay (curved `sticker-highlight` arrow following the turning layer, Move in `notation` at its tip; static under reduce motion) on the current Move, and carries the cube description plus *Read faces* for TalkBack.
- **Playback transport (12.15):** current Move in large `notation` with its MoveNote in `meta`; Move strip of `notation-chip`s with the current Move outlined in `ink-primary` and kept in view; visible scrubber snapping to Move boundaries; chip tap or scrub jumps with no animation. Physical-cube Mode: strip read-only, scrubber hidden. Move pictures are ink-only, decorative (TalkBack skips them), next to the current label and on each chip (≥ 20dp); *Move pictures* toggle in Settings next to color-blind glyphs.
- **Focus order and motion:** frame label → cube description → Move key bar → panel → mode switcher; every Certainty change announced; reduce motion makes Demonstrations step and transitions snap.
- **Banned patterns:** shake or red flash on mistakes, countdowns, streaks, points, badges, push notifications, auto-advance past a Notice, carousels.

## Cross-Story Dependencies

- **Order:** 12.14 → 12.15 → 12.3 → 12.5 screenshots. 12.15 depends on 12.14 settling the preference schema and playback stage.
- 12.14 builds on Story 5.4 (Move arrow), 5.5 (cube description / *Read faces*), 12.2 (performance baseline) and Epic 8 physical follow-along; Epic 10 Quick Check in playback must keep working in either view.
- 12.15 closes the Story 3.6 deferral `epic-3-deferred-3-6-ux-dr14-move-strip`.
- 12.3 absorbs epic-6-retro-item-1 (`PreferenceStore` must not overwrite saved prefs with defaults after a read error), which then covers the 12.14/12.15 preferences. 12.3 depends on Story 13.6; 12.5 on 13.4 and 13.5; 12.4 on the 7.8 pilot record.
- Visual, TalkBack and FPS acceptance of 12.14/12.15 happens in the phase 5 device session.
