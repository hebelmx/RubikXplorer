# Your to-do list (Product Owner)

Work top to bottom. Each line is one thing to do and how to tell it worked.
When you finish a block, tell me "block N done" and I'll close the matching items.

---

## Step 1 — One decision first (2 minutes)

**Will v1.0 ship with the camera features turned OFF?**

- **No, ship some camera features:** you'll need Step 4, and you'll decide BETA or GA for each feature.


---

## Step 2 — At your computer (about 1 hour, no phone needed)

**Play Console**
1. [ ] Open the closed-testing build's **Android vitals**. ✅ Crash-free ≥ 99.5% and ANR rate not flagged red. Write down the numbers, today's date and the versionCode. We are still on internal testing waiting for the laters build  to promote to closed testing.
2. [ ] Open **Data safety**. ✅ It says "No data collected or shared" and links the privacy page. Write down the date.
   - Put both notes in `docs/evidence/12-3/README.md`, or paste them to me and I'll add them.
  
We are still on internal testing waiting for the laters build  to promote to closed testing. 

## Play Console Verification — 2026-11-20

### 1. Android Vitals (Internal Testing)
- **Date checked:** 2026-11-20
- **Track:** Internal testing (Closed testing not yet active)
- **versionCode:** [Insert versionCode]
- **Status:** Vitals threshold not yet reached ("Not enough data" on Internal track); 0 crash/ANR clusters observed.

### 2. Data Safety
- **Date checked:** 2026-11-20
- **Store listing preview:** Confirmed "No data collection declared" and "No data shared with third parties"
- **Privacy Policy:** Linked and verified (`https://hebelmx.github.io/cubentia-site/privacy/`)
**GitHub**

### 3. Branch Protection / Required CI on `main`
- **Date checked:** 2026-11-20
- **Status:** Deferred until public open-source launch (repository is currently private on a GitHub Free plan, where branch rulesets/protections are gated until visibility is set to Public).
- **Action at launch:** Enable Repository Ruleset on `main` requiring CI status checks (`[your workflow job names]`) immediately upon switching visibility to Public.
- 

**Website (cubentia-site)**
4. [ ] Open `/`, `/privacy/` and `/support/`. ✅ For each one:
https://hebelmx.github.io/cubentia-site/privacy/#main
   - you can reach everything with Tab, and the focus is visible . ✅
   - it still reads well at 200% zoom . ✅
   - it works on a narrow window (320 px) . ✅
   - it works in light and dark mode . 
   - 
During manual verification of `cubentia-site` (`/`, `/privacy/`, `/support/`), I could not switch the site into light mode.

Please update `cubentia-site` so that:
1. All pages (`/`, `/privacy/`, `/support/`) support both **light and dark themes** with readable contrast and proper focus rings in both modes.
2. It respects the user's system preference (`prefers-color-scheme`) by default.
3. Add an accessible **Light / Dark mode toggle button** in the top navigation/header (reachable via `Tab`, with clear `aria-label` / `aria-pressed` state) so users can manually switch themes on any page and persist their choice across pages (`localStorage`).
4. 
   Note the result on site PR #4.

**Quick yes/no answers for me** (reply in chat)

1. [**Yes.** ] When the scanner can't find a missing color, should it stop suggesting stickers that are a clearly different color? (yes)

Do not suggest stickers that are a clearly different color.

Users tend to accept suggestions automatically without double-checking, so a low-confidence or clearly mismatched suggestion causes silent cube-state errors that are much harder to fix later.

When the scanner can't find a confident match for a missing color:
   1. Suppress the automatic suggestion rather than guessing a mismatched color.
   2. Prompt the user clearly to adjust the angle/lighting or tap the sticker color manually.
1. [yes ] If the Learner does a full 6-face check later, should the app re-learn the colors from it? yes
2. [yes ] Can I run a correct-course to update two planning documents so they match what was built (the architecture note on scan colors, and the website story text)? (yes with change control, what changes , who and why, and the timestamp)

---

## Step 3 — Device session: the core app (about 2 hours)

**Devices:** Lenovo tablet and Galaxy S24 FE.
**First:** restore both devices' screen-timeout and lock settings (they're still on maximum; I kept backups).

**A. Release build, offline**
1. [ ] Install the release from the Play testing track. Turn on **airplane mode**.
2. [ ] Do one Lesson in the Course, open the Library, enter a cube and solve it. ✅ Everything works with no internet.
3. [ ] ✅ All 12 Lessons are listed, and a Stage 2 Lesson finishes in "Guide me".

**B. Playing with your finger (both devices)**

4. [ ] Play Stage 1 and Stage 2 Lessons by swiping the cube. ✅ No moves you didn't intend; cancelling a swipe works; it feels smooth.
5. [ ] ✅ The outline of the layer you're about to turn is visible.
6. [ ] Tap a move. ✅ The cube reacts right away, with no noticeable delay.

**C. Screen reader (turn on TalkBack)**

7. [ ] In a Lesson, the solution playback and the Manual Cube Editor: ✅ TalkBack reads the faces, the certainty and the move strip, and doesn't skip any announcement.

**D. Big text (Settings → font size at maximum, 200%)**

8. [ ] ✅ The cube, its arrows and the "N moves" counter all fit on screen and line up.

**E. Galaxy S24 FE only**

9. [ ] ✅ The status bar icons are readable in light and dark mode on the Placement screen.
10. [ ] ✅ In physical-cube playback, nothing covers the cube net.
11. [ ] ✅ The move pictures (S, wide moves, x/y/z, B) look right to you.

**F. Automated device tests:** plug in a device and tell me. I run them; you only watch.

12. [ ] The render sync test, the end-to-end test and the playback benchmarks.

---

## Step 4 — Device session: the camera (only if you said "No" in Step 1)

**Devices:** both devices plus both test cubes. I'll prepare a debug build with the camera turned on.

1. [ ] **Scan the cube** in different light: daylight, cool LED and dim light, with each cube. ✅ The colors come out right.
2. [ ] **Quick Scan from two corners** on both devices. ✅ It works at least 95% of the time with ≤ 3 corrections in under 15 s. Then you decide: **GA or BETA**.
3. [ ] **Recognize a case** from a photo of the top. ✅ The stickers come out in the right order, in under 1 s.
4. [ ] **Placement from the Welcome screen:** scan it, then try typing the colors instead, then refuse camera permission. ✅ Each path ends correctly.
5. [ ] **Retake faces** on the review screen: retake one face, then several; back out halfway; type the colors instead. ✅ No screen flicker, and the title stays visible.
6. [ ] **Robustness:** ✅ The shutter click sounds; rotating the device on the last face still opens Review; red and orange aren't confused in warm or dim light.
7. [ ] **After you accept a scan:** ✅ it goes straight to the solution, or says "Already solved".

---

## Step 5 — The last 3 stories: what I need from you

| Story | You provide | I do |
|---|---|---|
| **12.4 Pilot fixes and release gates** | Cut the first release tag. Set up Play App Signing. Move the build to closed testing, run the pilot with real people, then send me the notes (where they got stuck, which hints they used) | Fix the serious issues and assemble the release-gate evidence |
| **12.5 Store listing and public repo** | The trademark check for "Cubentia". A short promo video. The content-rating questionnaire in Play Console. A yes to making the repo public and renaming it to Cubentia. The final click to move closed testing → production | The README, the screenshots, the badges and the website updates |
| **10.11 Turn on a camera feature** *(skip if camera OFF)* | Your BETA or GA choice per feature. Step 4 done. Approve the privacy-page PR. Re-check Data safety after the camera permission appears | The release flag change, the privacy page and the store wording |

**Order:** 12.4 → 12.5 → (10.11). v1.0 can go live right after 12.5.

---

<details>
<summary>Appendix — tracking IDs (for me, not for you)</summary>

- Step 2.1–2.2: `epic-12-deferred-12.3-play-vitals-review`, `-play-data-safety-form`
- Step 2.3: Epic 1 retro (Story 1.1 AC3)
- Step 2.4: Epic 13 a11y pass (13.2/13.4 AC3)
- Step 2.5–2.7: issues #174, #172; 13.4 reconcile
- Step 3.A: `epic-12-deferred-12.3` offline release check; Epic 2 release smoke
- Step 3.B: `epic-12-deferred-12.16-device-session-gestures`; Epic 5/6 hardware items
- Step 3.C–E: #204, #201, #139, #205, #200; `epic-12-deferred-12.15-po-device-review-pictures`
- Step 3.F: `epic-12-deferred-12.14-device-render-sync-bench-talkback`; Epic 8 E2E test
- Step 4: OQ-8 matrix (10.8); `epic-11-deferred-11.1-device-session-c`, `-11.3-device-capture-and-sticker-order`, `-11.2-device-placement-scan`; `epic-10-deferred-10.16-device-session`; ai-b1 device items; #158, #194, #203
- Step 5: issue #127 (7.8 pilot)
</details>
