---
name: RubikXplorer
description: A precise, instrument-like cube tutor. Neutral graphite chrome so the six sticker colors carry the color, one signal-violet accent, honest state indicators, light and dark designed equally.
status: final
created: 2026-10-29
updated: 2026-11-16
sources:
  - ../../prds/prd-RubikXplorer-2026-10-29/prd.md
  - ../../architecture/architecture-RubikXplorer-2026-10-29/ARCHITECTURE-SPINE.md
colors:
  # Chrome — light
  surface-base: '#F4F5F7'
  surface-raised: '#FFFFFF'
  surface-stage: '#E8EBEF'
  ink-primary: '#15181C'
  ink-secondary: '#5A6370'
  ink-disabled: '#A3AAB3'
  hairline: '#D5DAE0'
  accent: '#5B3FD1'
  on-accent: '#FFFFFF'
  # Chrome — dark
  surface-base-dark: '#111317'
  surface-raised-dark: '#1A1D22'
  surface-stage-dark: '#0B0C0F'
  ink-primary-dark: '#E7EAEE'
  ink-secondary-dark: '#9AA3AE'
  ink-disabled-dark: '#4D545E'
  hairline-dark: '#2A2F36'
  accent-dark: '#A797FF'
  on-accent-dark: '#160F33'
  # Certainty & status (always paired with an icon + label; never color alone)
  status-verified: '#1E7A4C'
  status-likely: '#9A5B00'
  status-unknown: '#5A6370'
  status-invalid: '#B3261E'
  status-verified-dark: '#5FD39A'
  status-likely-dark: '#F2B45A'
  status-unknown-dark: '#9AA3AE'
  status-invalid-dark: '#FF8A80'
  # Cube — identical in both themes (the physical object does not change with the theme)
  sticker-white: '#F4F5F0'
  sticker-yellow: '#FFD500'
  sticker-red: '#C41E3A'
  sticker-orange: '#FF5800'
  sticker-green: '#009E60'
  sticker-blue: '#0051BA'
  cubie-body: '#121417'
  sticker-highlight: '#A797FF'
  scan-grid: '#FFFFFF'
  sticker-dimmed-overlay: '#00000099'
typography:
  display:
    fontFamily: 'IBM Plex Sans'
    fontWeight: 600
    note: 'Android Headline Medium role'
  title:
    fontFamily: 'IBM Plex Sans'
    fontWeight: 600
    note: 'Android Title Large role'
  body:
    fontFamily: 'IBM Plex Sans'
    fontWeight: 400
    note: 'Android Body Large role'
  label:
    fontFamily: 'IBM Plex Sans'
    fontWeight: 500
    letterSpacing: 0.02em
    note: 'Android Label Large role'
  meta:
    fontFamily: 'IBM Plex Sans'
    fontWeight: 400
    note: 'Android Body Small role'
  notation:
    fontFamily: 'IBM Plex Mono'
    fontWeight: 500
    note: 'Every Move, Algorithm and coordinate; sized one step above surrounding text'
  data:
    fontFamily: 'IBM Plex Mono'
    fontWeight: 400
    note: 'Counters, timers, revision numbers, percentages; tabular figures'
rounded:
  xs: 2px
  sm: 4px
  md: 8px
  lg: 12px
  full: 9999px
spacing:
  '1': 4dp
  '2': 8dp
  '3': 12dp
  '4': 16dp
  '5': 24dp
  '6': 32dp
  '7': 48dp
  touch-min: 48dp
  gutter-compact: 16dp
  gutter-expanded: 24dp
components:
  cube-stage:
    background: '{colors.surface-stage}'
    rounded: '{rounded.lg}'
  move-key:
    typography: '{typography.notation}'
    background: '{colors.surface-raised}'
    border: '{colors.hairline}'
    rounded: '{rounded.sm}'
    minSize: '{spacing.touch-min}'
  button-primary:
    background: '{colors.accent}'
    text: '{colors.on-accent}'
    rounded: '{rounded.md}'
    typography: '{typography.label}'
  button-secondary:
    background: transparent
    border: '{colors.hairline}'
    text: '{colors.ink-primary}'
    rounded: '{rounded.md}'
  step-card:
    background: '{colors.surface-raised}'
    rounded: '{rounded.md}'
    labelTypography: '{typography.label}'
  certainty-chip:
    rounded: '{rounded.full}'
    typography: '{typography.label}'
  expected-badge:
    border: 'dashed 1.5dp {colors.ink-secondary}'
    typography: '{typography.label}'
  notation-chip:
    typography: '{typography.notation}'
    background: '{colors.surface-stage}'
    rounded: '{rounded.xs}'
  algorithm-tile:
    background: '{colors.surface-raised}'
    rounded: '{rounded.md}'
  beta-label:
    border: '{colors.ink-secondary}'
    typography: '{typography.meta}'
    rounded: '{rounded.xs}'
---

## Brand & Style

RubikXplorer looks like a **precision instrument that happens to be patient**. Picture a well-made measuring tool or a flight instrument: everything is aligned and labeled, and nothing is decorative. The cube is the only colorful thing on screen. The chrome around it is neutral graphite, so the six sticker colors never compete with the interface.

Precision serves honesty. The app labels what it knows (*Verified*), what it guesses (*Likely*) and what it can't know (*Unknown*). The same discipline that makes it look technical is what lets a nervous beginner trust it. Warmth comes from the copy and the gentle feedback (EXPERIENCE.md), never from decoration.

Notation is first-class. Every Move and Algorithm is set in a monospace face, like code. Notation always sits next to a plain-language explanation and never replaces it.

The palette and fonts below are Fast-path proposals. [ASSUMPTION: hex values and the IBM Plex pairing are to be confirmed against key-screen mocks at Finalize.]

## Colors

The chrome is restrained so that the cube is the subject.

- **Graphite neutrals** (`surface-*`, `ink-*`, `hairline`) have a slight cool bias, so they read as engineered rather than paper-warm. `surface-stage` is a recessed well behind the cube, darker than the page in both themes, so the stickers pop.
- **Signal violet** (`{colors.accent}` / `{colors.accent-dark}`) is the only UI accent. It is used for:
  - the primary action;
  - the active Teaching Mode;
  - the highlighted target piece (`sticker-highlight`) in Demonstrations and hints.

  It was chosen because no cube sticker is violet, so a highlight can never be mistaken for a sticker color. It is never used for decoration or status.
- **Status colors** mark Certainty (`verified`, `likely`, `unknown`) and invalid states. They **always appear with an icon and a text label**, never as color alone. Red is reserved for an *invalid* entered state or scan. A Learner's mistake is never shown in red (EXPERIENCE.md, Voice and Tone).
- **Sticker colors** follow the standard scheme. They stay identical in light and dark themes, because the physical cube doesn't change when the phone does. `cubie-body` is near-black plastic.
- **Dimming:** `sticker-dimmed-overlay` mutes the pieces that don't matter right now, so the pieces in focus stand out.
- **Highlight on the cube:** a piece is highlighted with a 3dp `sticker-highlight` outline that has a 1dp `cubie-body` inner stroke. The lighter violet stays visible on the near-black cube body, and the inner stroke keeps it visible on white and yellow stickers. Stickers themselves are never recolored.

**Contrast (WCAG AA, NFR-7):**
- **Text:** `ink-primary` and `ink-secondary` meet 4.5:1 on every surface token, in both themes.
- **Status tokens:** meet 3:1 as icons and 4.5:1 as chip text.
- **Cube highlight:** about 7:1 against `cubie-body`.
- **Scan grid:** `scan-grid` (#FFFFFF) is drawn at 70% opacity over the camera feed, with a 1dp dark halo. It does not change with the theme.

[ASSUMPTION: the exact ratios are checked by a contrast test when the tokens move into code.]

Avoid gradients, decorative color blocks, colored backgrounds behind text, and any second accent.

## Typography

There are two families, both bundled in the APK so the app needs no network (NFR-5):
- **IBM Plex Sans** for UI and explanations.
- **IBM Plex Mono** for anything you could type into a solver: Moves, Algorithms, coordinates, counts and timers.

Each role maps to the Material3 type scale (see each token's `note`), so system font scaling is respected at every level (NFR-7). Body text is never smaller than Material3 Body Medium.

Rules:
- `notation` is one step larger than the surrounding text, so `R U R' U'` is never the smallest thing on screen.
- Use `data` with tabular figures anywhere digits line up.
- Headlines are sparse: one `display` per screen at most, used for Stage names and the first-solve moment.
- No all-caps text, except the `label` style on very short status chips (`EXPECTED`, `BETA`).

## Layout & Spacing

The spacing scale is 4dp-based: 4 / 8 / 12 / 16 / 24 / 32 / 48. Gutters are 16dp on compact widths and 24dp on medium and expanded widths. Every touch target is at least 48dp (`touch-min`).

Lesson layouts are a **split**: the cube stage and the lesson panel.
- **Compact portrait:** stacked. The cube stage takes about 45% of the height at the top, and the lesson panel scrolls below it.
- **Medium, expanded or landscape:** side by side. The cube stage takes about 55% of the width on the left, and the lesson panel sits on the right.
- **Move key bar:** always attached to the cube stage edge nearest the thumb. It's horizontal and scrollable when compact, and a vertical grid when expanded.

Layout keys on Material3 window size classes. Full breakpoints are in EXPERIENCE.md, Responsive & Platform.

## Elevation & Depth

Hierarchy comes from tone, not shadow:
- `surface-raised` cards sit on `surface-base`.
- The cube sits *in* a recessed `surface-stage` well. The only real depth in the app is the 3D cube itself.
- Material3 tonal elevation is used for sheets and dialogs only.
- No drop shadows on cards.

## Shapes

Shapes are tight and precise:
- `rounded.sm` (4dp) for move keys and inputs;
- `rounded.md` (8dp) for cards, buttons and Algorithm tiles;
- `rounded.lg` (12dp) only for the cube stage well and bottom sheets;
- `rounded.full` only for certainty chips.

No pill-shaped buttons. Sticker squares in 2D nets and diagrams use `rounded.xs` (2dp), a nod to real stickers.

## Components

- **Cube stage**:
  - A toolbar along the stage's top edge holds **Undo · Redo · Reset**, as icon buttons with labels in TalkBack. Disabled buttons use `ink-disabled`.
  - On Solution Playback, the toolbar also holds a **view switch** icon button that switches between the 3D cube and the 2D net. Its icon and its TalkBack label name the view it switches *to* ("Show the 2D net" / "Show the 3D cube") (SCP 2026-11-17 playback view and move glyphs).
  - The 3D cube in a `surface-stage` well. The 2D net is the P0.1 stage, and stays as the second view on Solution Playback: it shows all 54 stickers at once, so Solution Playback opens on the net in Physical-cube Mode until the Learner picks a view.
  - A small **frame label** sits in the top-left corner: `VIRTUAL`, `EXPECTED` (with a dashed `expected-badge` border) or `VERIFIED` (with a `status-verified` icon).
  - There is no separate reset-view button in the toolbar at any width; Reset view lives on the View rotated badge. The ⋮ view menu with the View Rotation arrow buttons stays.
  - While the view is off home, a **View rotated** badge sits in the stage's top-left overlay, right after the frame label when one is shown: an outlined `rounded.sm` tag in `label` with a ⟲ icon and the text "View rotated · Reset view", with a 48dp touch target. Tapping it resets the view. The overlay wraps at large font sizes.
  - While the view is off home, **face labels** U, D, F, B, R, L in `notation` sit outside the cube's silhouette beside each face that clearly faces the camera, in `ink-secondary`, kept inside the stage. They are never drawn on a sticker.
  - Pieces not involved in the current step can be dimmed with `sticker-dimmed-overlay`.
  - **Mismatch view:** when an observation differs from the Expected State, the observed faces are drawn as a flat strip of face thumbnails under the cube, labeled `SEEN`, with each differing sticker outlined in `status-likely` or `status-verified`. A Partial observation is never drawn as a whole cube.
- **Move arrow overlay**: during a Demonstration, on hint rung 2, and on the current Move of Solution Playback in the 3D view, a curved arrow in `sticker-highlight` follows the turning layer, with the Move shown in `notation` at its tip. With reduce motion on, the arrow appears without animating.
- **Drag preview**: while a turn gesture is locked, the dragged layer turns with the finger (at most a quarter turn) and all its cubies get the `sticker-highlight` outline, added to any Lesson emphasis and without the pulse tint. A ghost Move arrow (the Move arrow overlay at 60% opacity) carries the Move in `notation` near the finger; a teaching arrow on the stage is hidden while the ghost shows. With reduce motion on, the layer stays still and only the arrow and label show.
- **Move key bar**: a row or grid of `move-key` buttons, each labeled in `notation` (`U`, `U'`, `U2`, …), styled like keyboard keys.
  - When a key is pressed it inverts briefly to `ink-primary` on `surface-raised`.
  - A **⟳ x/y/z** key group holds the Cube Rotations.
  - A **"more"** key reveals wide and slice turns.
- **Lesson panel**: from top to bottom:
  1. the Objective, as a `title`;
  2. the current `step-card`;
  3. the Teaching Mode switcher;
  4. a row with the Hint button (secondary) and **Next** (`button-primary`).

  When disabled, Next uses a `hairline` fill and `ink-disabled` text. An optional **Assistance** line sits under the step card, in `meta`, when adaptive help has changed.
- **Step card**: four labeled lines in `label` + `body`: **Goal**, **Keep**, **Watch**, **Notice**. *Notice* appears only after the Move. The step's Moves show as `notation-chip`s.
- **Teaching Mode switcher**: a segmented control with *Show me*, *Guide me*, *Test me* and *Let me try*. The active segment is filled with `accent`, and a mode flagged off is hidden.
- **Hint button**: a secondary button labeled **Hint (1/3)**. The counter shows the current rung of the Hint Ladder.
- **Certainty chip**: a `rounded.full` chip with an icon plus a label: ✓ *Verified*, ≈ *Likely*, ? *Unknown*. Its color comes from the status tokens.
- **Expected badge**: a dashed outline with the text `EXPECTED`, shown on any rendering of a predicted physical state.
- **Playback transport**: |◀ ◀ ▶/❚❚ ▶ ▶| plus a speed control (0.5× / 1× / 2×).
  - The step counter (`12 / 20`) is set in `data`.
  - The current Move is shown in large `notation`, with its generated MoveNote line in `meta` beneath it. When *Move pictures* is on, the Move's **move picture** sits just before the notation, at the notation's cap height.
  - The **Move strip** sits under the transport: one `notation-chip` per Move of the sequence, in order, scrolled to keep the current Move in view. The current Move is marked with an `ink-primary` outline, not violet. When *Move pictures* is on, each chip carries its move picture (at least 20dp) above the notation.
  - A visible **scrubber** (a slider in `ink-secondary` with an `ink-primary` thumb) runs under the strip and snaps to Move boundaries. In Physical-cube Mode the strip is read-only and the scrubber is hidden, because each step advances only on *Done* (FR-16) (SCP 2026-11-17).
- **Move picture** (glyph): a small ink-only drawing of one Move, generated in code from the Move, never from sticker colors, so it works with color-blind glyphs. Drawn in `ink-primary` lines on a transparent ground, with the moving layer shaded in `ink-secondary` at about 35%. It is decorative: TalkBack skips it, because the MoveNote already says the Move. (SCP 2026-11-17)
  - The base is a flat 3×3 face as seen from the front (U on top), drawn with `hairline`-weight grid lines.
  - **R / L / U / D**: the moving column or row is shaded, with a curved arrow along it in the direction its stickers move on the front face (R up, L down, U left, D right).
  - **F / B**: drawn as seen from the front (the standard convention). F is the front grid with a clockwise round arrow over it. B is a round arrow drawn behind the grid (the grid's lines cross over it), so it reads counter-clockwise from the front, while the MoveNote still says "Turn the Back face clockwise".
  - **M / E / S**: a straight arrow. M shades the middle column with a downward arrow (it follows L). E shades the middle row with a rightward arrow (it follows D). S follows F, so it is drawn on the Up face seen from above (a small "top" tick on the far edge marks it), with the middle row shaded and a rightward arrow.
  - **Prime**: the arrow is reversed (the plain Move's picture mirrored). **Half turn**: the plain Move's picture with a superscript `2` at the arrowhead.
  - **Wide (Rw, Lw, Uw, Dw, Fw, Bw)**: the two outer layers are shaded with the face's arrow. Fw and Bw, whose second layer is hidden from the front, show a doubled round arrow.
  - **Cube Rotations (x / y / z)**: no layer shading. The arrow of R (x), U (y) or F (z) is drawn across the whole face, with the face outline doubled to mean "the whole cube".
- **Scan viewfinder**: a camera preview with a 3×3 hairline grid in `scan-grid` at 70% opacity. On a phone in portrait it fills the top ~45% of the screen, so the face net and palette fit below it (SCP 2026-11-15).
  - The **Quick Scan variant** replaces the grid with a corner outline (a Y-shaped three-face guide) and a capture counter in `data` ("1 / 2"). When a face can't be read, it hands off to the per-face grid for that face only. Above it, the face prompt names the center color ("**Green** center facing you"); "· **White** on top" is added only when the app asks for a face again (SCP 2026-11-16) and shows a swatch of that sticker with its glyph. A mini net shows progress through the faces.
- **Sticker review net**: a 2D cube net drawn on the cube body, like a real cube: each 3×3 face sits on `cubie-body`, which also shows as the gaps between stickers, with a 1dp `hairline` outline so the body separates from a dark page. A white sticker therefore always sits on black (UX update 2026-11-16).
  - An *uncertain* sticker gets a dashed `accent` ring and a small "?" badge.
  - A *blank* sticker (a close call left for the Learner) is a `surface-stage-dark` well with a dashed `sticker-highlight` edge and a "?", with no sticker color, so it can never be read as white.
  - The *next sticker to mark* (the selected one) has the solid `accent` ring, which pulses slowly: opacity 100% → 35% → 100%, about 1.5 s per cycle. With reduce motion the ring is static at 3dp.
  - *Update cue:* when a color is set, the sticker scales to about 110% and settles in about 200 ms, and a small ✓ in `on-accent` on an `accent` dot fades out over it. With reduce motion, only the ✓ shows.
  - An *invalid piece* follows the cube-highlight rule: a 3dp `sticker-highlight` outline with a 1dp `cubie-body` inner stroke, plus its label. `status-invalid` red is not used on the cube body, where it has about 2.6:1 contrast.
  - *Close* is a normal, enabled outlined button with `ink-primary` text, never greyed.
- **Color palette**: the Manual Cube Editor's six sticker swatches, in a 2×3 block next to the face net, each at least `touch-min` with its glyph, in the same order as the editor palette: white, yellow, red, orange, green, blue.
- **Algorithm tile**: the Case recognition diagram (U face plus side bands, derived from state), the Case name in `label`, the Algorithm in `notation`, and the Algorithm Status as a small text label.
  - The status reads `Learning` or `Learned`. *Not started* shows no label on the tile, and the detail screen says "Not in your set yet."
  - No stars, scores or badges. The Learned count is never framed as an achievement (SM-C3).
  - If recognition diagrams are cut, the tile falls back to the Case name and notation only, with no placeholder art.
- **Filter chips**: neutral Material3 filter chips. A selected chip uses `surface-raised` with a ✓; violet is never used for selection.
- **Trend row** (You tab, Stage detail):
  - One row per Stage: the Stage name, then a small dot sparkline of hints per attempt in date order, in `ink-secondary`, with the latest point emphasized.
  - Beside it, the attempt count in `data`.
  - No totals as a score, no red, and no "bad" framing for hint use (SM-C1).
- **Mastery progress line**: the Stage's Mastery Condition in `meta`, for example "Mastery: 1 of 2 solves · 1 with reduced help", with a two-segment progress indicator.
- **Stage row**: the Stage number in `data`, the name in `title`, and the state as text plus icon: *Locked*, *In progress*, *Completed*, *Skipped*, *Skipped by placement* or *Mastered*.
  - *Mastered* uses `accent` with a filled ◆.
  - Both skipped states use an outlined ◇. Placement adds the subtitle "by placement".
  - The same row is reused for the 2-look mini-Course.
- **Beta label**: a tiny outlined `BETA` tag next to a feature name when its feature flag is BETA (AD-18).
- **Milestone moment**: shown only for the first solve and for each Stage mastered. In Physical-cube Mode, it plays only after a Quick Check or the Learner's explicit "Yes, it's solved" (EXPERIENCE.md). A short, restrained burst of the six sticker colors around the solved cube (≤ 1.2 s), then a `display` line. No confetti loops and no sound by default.
- **Color-blind glyphs** (AD-17, NFR-7): every sticker surface — the 3D cube, nets, the editor, scan review and diagrams — can show a single centered letter: W Y R O G B. The setting is on/off. The glyphs are drawn in `cubie-body` on light stickers and in `sticker-white` on dark ones, in a weight that stays legible at 16dp. They are defined once in `StickerAppearance` and used everywhere. Orange counts as a light sticker, so its glyph is dark. Patterns are deferred: MVP ships glyphs only, and the `pattern` field stays empty. [ASSUMPTION]

Mockups showing these components: `mockups/key-lesson-phone.html`, `mockups/key-lesson-tablet.html`, `mockups/key-physical-and-scan.html` and `mockups/key-library.html`. Where a mockup and this document disagree, this document wins.

## Do's and Don'ts

| Do | Don't |
| --- | --- |
| Let the cube carry the color; keep the chrome graphite | Tint backgrounds, cards or headers with sticker colors |
| One accent (signal violet), used for action and highlight | A second accent, gradients, or a decorative color |
| Certainty shown as icon + label + color | Color alone for Verified / Likely / Unknown |
| Set every Move in `notation` next to plain language | Show notation without an explanation for a beginner |
| Mark predicted physical states with the dashed `EXPECTED` badge | Render an Expected State as if it had been observed |
| Show a Learner's mistakes in neutral ink, highlighting the pieces involved | Red fills, ✗ marks or shaking for a Learner's mistake |
| Keep sticker colors identical across themes | Re-tint stickers for dark mode |
| Draw sticker glyphs from one shared table | A different color-blind treatment on each surface |
