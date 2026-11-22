---
title: 'Move arrow casing and filled arrowhead (#260)'
type: 'feature'
created: '2026-11-22'
status: 'done'
review_loop_iteration: 1
followup_review_recommended: false
context: []
warnings: []
deferred:
  - summary: >-
      On-device look at the cased Move arrow and ghost arrow (SCP 2026-11-22 success criterion), including whether the notation label overlaps the larger arrowhead.
    evidence: |-
      Pixel tests run on synthetic polylines over white in Robolectric; the SCP asks for an on-device check and the label placement is unchanged by design, so only a device session can settle visibility over stickers and label overlap.
    location: >-
      app/src/main/kotlin/dev/cubentia/app/render/Cube3DRenderer.kt (overlay Canvas)
    severity: low
baseline_revision: '799faae15a9d49c3b9f541a63901642d59424c48'
---

<intent-contract>

## Intent

**Problem:** The 3D Move arrow (and the ghost Move arrow during a drag) is a 4dp `sticker-highlight` (lavender) stroke with a small open-V tip (12dp back, 6dp half-width), which is hard to see on bright or busy faces; the turn direction is hard to read at a glance (GitHub #260).

**Approach:** Per SCP 2026-11-22 and DESIGN.md lines 232-233, keep `sticker-highlight` as the fill, add a dark `cubie-body` casing (outline on both sides) around the stroke and the arrowhead, and replace the open-V with a filled triangular arrowhead about 18dp long by 9dp half-width, also cased. The ghost arrow keeps 60% opacity with the same casing and tip. Expose the arrowhead geometry and colours as small internal companion helpers so a renderer test can pin them.

## Boundaries & Constraints

**Always:** Fill colour is `CubeTokens.stickerHighlight`, casing colour is `CubeTokens.cubieBody` (existing tokens, no new token); ghost arrow (drag preview active) applies alpha 0.6 to both fill and casing; arrowhead is a filled triangle with its apex on the arrow tip, 18dp long along the direction of travel and 9dp half-width at its base; casing surrounds both the shaft and the arrowhead; arrow animation, label placement (`arrowLabelTopLeft`), semantics (`RenderedArrowKey`, `PreviewedMoveKey`) and `arrowPoints` are unchanged.

**Never:** Do not change arc geometry, animation timing, the notation label style, DESIGN.md or any planning artifact, theme tokens, or other screens; no new dependencies.

</intent-contract>

## Code Map

- `app/src/main/kotlin/dev/cubentia/app/render/Cube3DRenderer.kt` -- line ~600 `arrowColor` (sticker-highlight, alpha 0.6 for ghost; also used for the notation label, keep that); lines ~661-686 overlay `Canvas`: builds the shaft `Path` from `arrowPoints` up to `tip`, draws `Stroke(4.dp)`, then the open-V via two `drawLine`s (12dp/6dp). Companion object (~line 750+) holds `arrowLabelTopLeft`, `arrowPoints`, `curvature` as `internal` testable helpers -- add the new helpers/constants there.
- `app/src/main/kotlin/dev/cubentia/app/ui/theme/Tokens.kt` -- read-only: `CubeTokens.cubieBody = 0xFF121417`, `CubeTokens.stickerHighlight = 0xFFA797FF`.
- `app/src/test/kotlin/dev/cubentia/app/render/Cube3DRendererTest.kt` -- Robolectric test class; existing pattern `arrowLabelStaysInsideTheStage...` calls companion helpers directly. Add the new test here.

## Tasks & Acceptance

**Execution:**
- `app/src/main/kotlin/dev/cubentia/app/render/Cube3DRenderer.kt` -- (a) companion `internal` constants `ARROW_STROKE` (4.dp), `ARROW_CASING` (1.5.dp per side), `ARROWHEAD_LENGTH` (18.dp), `ARROWHEAD_HALF_WIDTH` (9.dp), `GHOST_ARROW_ALPHA` (0.6f); `internal data class ArrowColors(fill, casing)` + `internal fun arrowColors(): ArrowColors` returning OPAQUE `stickerHighlight` fill on `cubieBody` casing; `internal fun arrowhead(tip, direction, length, halfWidth): List<Offset>` returning [apex, base+side, base-side]. (b) a pure `internal fun pointBack(points: List<Offset>, distance: Float): Offset` (or equivalent) that walks back from the polyline's last point by arc length, clamped at the first point, plus a matching trim so the shaft polyline can end at a given arc length back from the tip. (c) an `internal fun DrawScope.drawMoveArrow(points: List<Offset>, ghost: Boolean)` that draws the arrow along an already-cut polyline whose last point is the tip (see Design Notes). The overlay Canvas builds the cut polyline (points up to `tip`) and calls it; the notation label keeps its own `overlayProgress > 0f` condition and stays at 60% for the ghost arrow as today. -- DESIGN.md 232-233.
- `app/src/test/kotlin/dev/cubentia/app/render/Cube3DRendererTest.kt` -- unit test: `ARROWHEAD_LENGTH == 18.dp`, `ARROWHEAD_HALF_WIDTH == 9.dp`, `ARROW_CASING > 0.dp`, `GHOST_ARROW_ALPHA == 0.6f`, `arrowColors()` is stickerHighlight on cubieBody, `arrowhead` geometry (apex on tip, base midpoint `length` back, corners `halfWidth` either side), and `pointBack` on a curved polyline measures arc length and clamps at the start. -- done-criterion (3).
- `app/src/test/kotlin/dev/cubentia/app/render/MoveArrowDrawScreenshotTest.kt` (new, Robolectric `@GraphicsMode(NATIVE)` + `captureToImage().toPixelMap()` like `ViewRotatedScreenshotTest`) -- draw `drawMoveArrow` in a plain `Canvas` on a known background (e.g. white) along a straight and a curved polyline; assert: a pixel inside the arrowhead (about 9dp back from the apex, on the axis) is near stickerHighlight; a pixel on the shaft axis is near stickerHighlight; a pixel ~1dp beyond the shaft stroke edge and one ~0.75dp outside a head side edge are near cubieBody; a pixel at the shaft/head join on the axis is stickerHighlight (no dark seam); a pixel ~12dp outside the head is the background; with `ghost = true` the arrowhead interior is near the 60% blend of stickerHighlight over the background (not darkened by casing). -- proves the drawn surface, not only helpers.

**Acceptance Criteria:**
- Given a teaching Move arrow on the 3D stage, when it is drawn, then the shaft and a filled triangular arrowhead (18dp by 9dp half-width) appear in sticker-highlight with a cubie-body casing on both sides, no dark seam at the join and no shaft or casing sticking past the apex.
- Given a drag preview is active, when the ghost Move arrow is drawn, then the whole cased arrow appears at a uniform 60% opacity.
- Given the renderer test suite, when the renderer tests run, then they check arrowhead size and casing/fill colours on drawn pixels and all existing tests still pass.

## Spec Change Log

### 2026-11-22 — Loopback 1 (review pass 1)
- Triggering findings: ghost arrow alpha stacks per path (fill over casing, casings overlapping) instead of one 60% arrow; dark casing seam across the shaft at the head base; shaft folds back along a chord and its casing pokes past the apex because the cut is measured on a straight line, not along the arc; tests cover only helpers, not drawn pixels.
- Amended: Tasks & Acceptance (opaque `arrowColors()`, ghost via one offscreen layer, arc-length `pointBack`/trim helper, `drawMoveArrow` DrawScope extension, label keeps its own progress condition, new pixel test file, test pins `GHOST_ARROW_ALPHA`), new Design Notes, Verification command.
- Known-bad state avoided: per-colour 0.6 alpha on four overlapping paths; shaft ending exactly at the base with a butt end under a centred casing stroke; `lineTo(base)` back from `points[whole]`; helper-only tests.
- KEEP: constants ARROW_STROKE 4dp, ARROW_CASING 1.5dp, ARROWHEAD_LENGTH 18dp, ARROWHEAD_HALF_WIDTH 9dp; `arrowhead()` helper and its geometry test; casing-before-fill order; round-join head casing; existing tests unchanged.

## Review Triage Log

### 2026-11-22 — Review pass
- verdicts: 23 findings — high 0, medium 11, low 9, false 2, maybe-false 1
- findings:
  - `[medium]` `[bad_spec]` (intent) new test checks helpers only, not what the Canvas draws — amended: pixel test of `drawMoveArrow`.
  - `[medium]` `[bad_spec]` (intent) ghost arrow blends fill over translucent casing — amended: one offscreen layer at 0.6.
  - `[low]` `[defer]` (intent) SCP device check of the arrow not addressed — needs a device; the orchestrator's device session covers it.
  - `[false]` `[reject]` (intent) shortened shaft is behaviour beyond the intent — it is required so the butt end does not stick past the apex; not a defect.
  - `[medium]` `[bad_spec]` (blind) ghost alpha stacks across four translucent paths — same root cause as intent row 2; amended.
  - `[medium]` `[bad_spec]` (blind) dark seam where the head's centred casing stroke crosses the shaft end at the base — verified: shaft fill ends at the base, outer 1.5dp of head casing uncovered; amended: shaft ends inside the head.
  - `[medium]` `[bad_spec]` (blind) shaft folds back / head off the arc — verified: arc has 33 points, segments are a few dp, so `lineTo(base)` doubles back from `points[whole]` and the cased stroke at `points[whole]` pokes past the narrow apex; amended: arc-length trim.
  - `[low]` `[bad_spec]` (blind) head direction from one short segment, unstable near joins — same root cause (chord geometry); amended: direction over the head's own arc length.
  - `[medium]` `[bad_spec]` (blind) tests pin numbers, not drawing order or casing visibility — grouped with intent row 1; amended.
  - `[low]` `[bad_spec]` (blind) test repeats 0.6f instead of pinning `GHOST_ARROW_ALPHA` — amended: test pins the constant.
  - `[low]` `[reject]` (blind) `arrowhead` does not enforce a unit direction — internal helper with one caller that normalizes; a guard adds complexity for no reachable case.
  - `[low]` `[bad_spec]` (blind) label drawn under `headPath != null` instead of its own progress check — amended: label keeps `overlayProgress > 0f`.
  - `[low]` `[reject]` (blind) leftover `arrowColor` alias — cosmetic; the re-derived code changes this anyway.
  - `[maybe-false]` `[reject]` (blind) label may overlap the larger head — label placement is unchanged by intent and offset 8dp beside the tip; settling it needs a device look; if true only low.
  - `[medium]` `[bad_spec]` (verification-gap) Canvas drawing of cased shaft and filled head has no test — pre-verified; amended: `MoveArrowDrawScreenshotTest`.
  - `[low]` `[reject]` (edge) full 18dp head extends behind the arrow start in the first frames — transient (200ms animation start), fix adds branches.
  - `[medium]` `[bad_spec]` (edge) shaft overshoots to `points[whole]` then turns back along the chord — same root cause as blind fold-back row; amended.
  - `[low]` `[bad_spec]` (edge) head follows chord from `points[whole-1]`, not the curve — same root cause; amended.
  - `[false]` `[reject]` (edge) zero-length direction draws a +x head — with progress > 0 the tip is strictly past `points[whole]` or equals it with `points[whole-1]` distinct (33 distinct arc points), so the length is never 0.
  - `[medium]` `[bad_spec]` (edge) ghost fill tinted by casing under it — ghost group; amended.
  - `[medium]` `[bad_spec]` (edge) darker seam where shaft and head casings overlap on the ghost — ghost group; amended.
  - `[low]` `[reject]` (edge) shaft butt end sticks out when the tip is within ~4dp of the start — first frame only; transient.
  - `[medium]` `[bad_spec]` (edge, claim) ghost is not a uniform 60% sticker-highlight — ghost group; amended.

### 2026-11-22 — Review pass 2
- verdicts: 24 findings — high 0, medium 3, low 16, false 4, maybe-false 1
- findings:
  - `[medium]` `[patch]` (intent) the Content call site (cut polyline, ghost flag) is untested — grouped with verification-gap row; fixed: extracted `arrowCut(points, progress)` + `arrowCutEndsAtTheAnimatedTip` test.
  - `[low]` `[reject]` (intent) contrast against the six sticker colours is not tested — casing is drawn independently of the background and its pixels are proven on white; a per-sticker test adds branches for little signal; device session covers the look.
  - `[low]` `[reject]` (intent) `arrowColors()` unit assertion is circular — the pixel test is the real colour check; harmless.
  - `[low]` `[patch]` (intent) head no longer gated on `overlayProgress > 0f` — fixed: `drawMoveArrow` called only inside that branch.
  - `[maybe-false]` `[reject]` (intent) label may overlap the larger head — carried: label placement unchanged by intent; device look settles it; if true only low.
  - `[low]` `[defer]` (intent) SCP on-device check not addressed — carried; deferred to the device session.
  - `[false]` `[reject]` (intent) no commits on the branch yet — commits are created at finalize, each subject carries #260.
  - `[medium]` `[patch]` (verification-gap) renderer's call into `drawMoveArrow` (cut polyline, ghost flag) unchecked — pre-verified; fixed with `arrowCut` helper and test.
  - `[low]` `[patch]` (verification-gap, other) JUnit reports predate the last renderer edit — re-ran both classes with `--rerun-tasks` after the patches (13/13, 4/4 at 09:53Z) plus full `spotlessCheck build`.
  - `[low]` `[reject]` (blind) full-size head on short polylines during draw-in — carried from pass 1 (transient first frames; accepted in Design Notes).
  - `[low]` `[patch]` (blind) on tight curves the arc-trimmed shaft end sags out of the chord-aligned head — fixed: shaft's last point replaced by the on-axis point `tip - unit * headLength / 2`.
  - `[low]` `[reject]` (blind) `trimBack` KDoc wrong at the exact-length boundary ([p0, p0]) — a zero-length butt-capped stroke draws nothing; negligible.
  - `[low]` `[reject]` (blind) screenshot test uses dp literals instead of constants — the literals deliberately pin the DESIGN.md numbers (18/9dp); a constant change should fail loudly.
  - `[low]` `[reject]` (blind) no test on sticker-coloured or dark backgrounds — same as intent contrast row.
  - `[low]` `[reject]` (blind) notation label gets no casing and stacks alpha over the ghost — intent scopes the change to the stroke and arrowhead ("nothing more"); label unchanged from before.
  - `[low]` `[reject]` (blind) ghost saveLayer is stage-sized every drag frame — only while a drag preview is active; hardware layer; no measured cost; tightening adds code.
  - `[medium]` `[patch]` (blind) Cube3DRenderer integration (cut at partial progress, nothing at 0) untested — same group as verification-gap row; fixed.
  - `[low]` `[patch]` (blind) head drawing relies on cut-size invariant instead of the progress gate — same group as intent gate row; fixed.
  - `[low]` `[patch]` (edge) shaft end sags off the head axis below ~40dp projected radius — same group as blind sag row; fixed.
  - `[false]` `[reject]` (edge) ghost saveLayer reach (19.5dp) smaller than the head corner distance (21.6dp) — bounds are the union with the whole canvas and every arc point; the corners lie within ~9dp of an arc point, and anything past the canvas is clipped anyway.
  - `[false]` `[reject]` (edge) `pointBack` on an empty list throws — only caller passes ≥ 2 points (early return).
  - `[false]` `[reject]` (edge) NaN distance drops the shaft — distances are fixed dp constants.
  - `[low]` `[patch]` (edge, deletion) removed `overlayProgress > 0f` gate — same group as intent gate row; fixed.
  - `[low]` `[patch]` (edge, claim) shaft trim measured along the arc but head on the chord — same group as sag row; fixed.

## Design Notes

Drawing order inside `drawMoveArrow`: if `ghost`, wrap everything in one offscreen layer at `GHOST_ARROW_ALPHA` (e.g. `drawContext.canvas.saveLayer(bounds, Paint().apply { alpha = 0.6f })` ... `restore()`), and draw opaque colours inside, so overlaps never stack alpha. Head direction = unit vector from `pointBack(points, ARROWHEAD_LENGTH)` to the tip (a chord over the head's own length, fallback to the last non-zero segment, else no head). Shaft = the polyline trimmed by arc length to end ARROWHEAD_LENGTH / 2 back from the tip (inside the head, where the head's half-width 4.5dp exceeds the cased shaft's 3.5dp half-width), so the head fill covers the shaft end and its casing: no seam at the base and nothing poking past the narrow apex. Order: shaft casing (stroke ARROW_STROKE + 2*ARROW_CASING), head casing (stroke 2*ARROW_CASING, round join), shaft fill (ARROW_STROKE), head fill. Very short arcs in the first animation frames may leave an empty shaft; keep the full-size head (accepted, transient).

## Verification

**Commands:**
- `./gradlew :app:testDebugUnitTest --tests 'dev.cubentia.app.render.Cube3DRendererTest' --tests 'dev.cubentia.app.render.MoveArrowDrawScreenshotTest'` -- expected: all pass
- `./gradlew spotlessCheck build` -- expected: BUILD SUCCESSFUL

## Auto Run Result

Status: done

**Summary:** The 3D Move arrow and the ghost Move arrow now draw a 4dp `sticker-highlight` shaft and a filled triangular arrowhead (18dp long, 9dp half-width), both with a 1.5dp-per-side `cubie-body` casing. The ghost arrow is drawn opaque into one offscreen layer at 60%, so overlaps never stack alpha. The shaft is trimmed by arc length to end inside the head on its axis, so there is no dark seam and nothing pokes past the apex.

**Files changed:**
- `app/src/main/kotlin/dev/cubentia/app/render/Cube3DRenderer.kt` -- arrow constants, `arrowColors`, `arrowhead`, `arrowCut`, `pointBack`/`trimBack` helpers and the `DrawScope.drawMoveArrow` extension; overlay Canvas calls it.
- `app/src/test/kotlin/dev/cubentia/app/render/Cube3DRendererTest.kt` -- tests for the constants/colours/arrowhead geometry, arc-length trimming and the animated cut.
- `app/src/test/kotlin/dev/cubentia/app/render/MoveArrowDrawScreenshotTest.kt` -- new Robolectric pixel test: fill, casing, no seam, nothing past the apex, uniform 60% ghost (straight and curved).

**Review:** pass 1 looped back once (bad_spec: ghost alpha stacking, seam at the head base, chord-based shaft cut, helper-only tests). Pass 2: 3 medium + 4 low entries patched (arrowCut helper and test, progress gate, on-axis shaft end, re-run of stale test reports); 1 deferred (on-device look, label overlap); rejected findings and reasons are in the Review Triage Log.

**Follow-up review recommended:** false (pass 2 patched 0 high and 1 medium entry after grouping: the call-site test; the rest were low).

**Verification:** `./gradlew :app:testDebugUnitTest --tests '...Cube3DRendererTest' --tests '...MoveArrowDrawScreenshotTest' --rerun-tasks` -- 13/13 and 4/4 pass; `./gradlew spotlessCheck build` -- BUILD SUCCESSFUL.

**Residual risks:** not yet seen on a device; the full 18dp head shows on its own for the first frames of the 200ms draw-in; the label is unchanged and may touch the larger head on some moves.
