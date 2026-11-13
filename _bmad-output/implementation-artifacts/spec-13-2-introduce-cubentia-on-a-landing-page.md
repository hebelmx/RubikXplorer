---
title: 'Introduce Cubentia on a landing page'
type: 'feature'
created: '2026-11-13'
status: 'done'
implementation_repo: '/mnt/e/Dynamic/IndFun/cubentia-site'
implementation_branch: 'feat/13-2-landing-page'
baseline_revision: '569e450232395e599e9e11220f8467f6fa14b0de'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/AGENTS.md'
  - '{project-root}/_bmad-output/implementation-artifacts/epic-13-context.md'
warnings: ['multiple-goals', 'oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** The site's landing page (`hebelmx/cubentia-site`, Story 13.1) is one sentence long. It does not say what Cubentia does or link to the privacy policy, and nothing links to a support page. The checker passes text contrast at 3:1, below WCAG 2.2 AA (hebelmx/cubentia-site#2). Nothing checks the pages' accessibility structure. "ExxerPro" is still on the landing page, the 404 page, the README and the LICENSE.

**Approach:** In the site repo (`/mnt/e/Dynamic/IndFun/cubentia-site`, branch `feat/13-2-landing-page`), rewrite `site/index.html` in calm, plain copy that links to `privacy/` and `support/`. Add a minimal real `site/support/index.html`. Raise the privacy checker's text contrast to 4.5:1, and add 3:1 for border and outline colors. Add a stdlib accessibility-structure checker, `scripts/check_a11y.py`, wired into `check.sh`. Replace "ExxerPro" with the approved publisher wording.

## Boundaries & Constraints

**Always:**
- Landing copy says, in short declarative sentences with no exclamation marks: Cubentia is an offline Android tutor that teaches a beginner to solve a 3×3 cube, on a virtual cube on screen or a real cube in hand. It works without an account and without an internet connection. It links to `privacy/` ("Privacy policy") and `support/` ("Support"). Learners are called "Learners" where they are named. Do not use "Rubik", screenshots, image placeholders or "coming soon" text.
- Support page (`site/support/index.html`), minimal and real: `h1` "Support", one or two calm sentences saying to email `exxerpro@exxerpro.com` (a `mailto:` link) for help or to report a problem, that no personal data is needed, and links to the privacy policy and home. It has the same skeleton as the other pages. Story 13.5 expands it.
- Publisher wording: "Exxerpro Solutions, S.A. de C.V." replaces "ExxerPro" in the `site/index.html`, `site/404.html` and new support footers (`© 2026 Exxerpro Solutions, S.A. de C.V.`), in `README.md` (lines 3, 17, 114, 115) and in `LICENSE` (lines 3, 33). Where the README names the publisher in prose (line 3 and the rule on line 17), add "(published on Google Play as Abel Briones)". `exxerpro@exxerpro.com` is the only email. Do not add a postal address or phone number. After the change, `grep -rn ExxerPro` over `site/`, `README.md` and `LICENSE` finds nothing.
- Contrast (#2), in `scripts/check_privacy.py`, which checks every stylesheet the policy page loads (all pages share `site/styles.css`):
  - Rename `MIN_CONTRAST` to `MIN_TEXT_CONTRAST = 4.5`. Every text color (ink tokens and the browser's default text colors) must reach it against every surface, in both schemes. The checker cannot tell large text from normal text, so it uses 4.5:1 for all text.
  - Add `MIN_NON_TEXT_CONTRAST = 3.0` (WCAG 1.4.11): every color token used in a `border` or `outline` declaration must reach it against every surface, in both schemes.
  - Split `UA_COLORS` into the default text and canvas colors (always checked) and the default link colors (`#0000ee #551a8b #ee0000` light, `#9e9eff #d0adf0 #ff9e9e` dark). The link colors are checked only when no rule with selector exactly `a` outside the dark media block sets `color`. Author styles override the browser's link colors whatever the specificity, so that rule decides every link's color.
  - Failure messages name the threshold.
- `scripts/check_a11y.py` (stdlib, CLI `[SITE_DIR]`, default `site`, prints `file: message` lines or `OK`, exit 1 or 0) checks every `.html` under the site:
  - `<html lang="en">` and a non-empty `<title>`;
  - a viewport meta that does not block zoom (no `user-scalable=no`/`0`, no `maximum-scale`);
  - exactly one `header`, `main` and `footer`, with `main` having `id="main"`;
  - a skip link: the body's first focusable element is an `<a href="#main">` with text;
  - exactly one `h1`, the first heading is the `h1`, and heading levels never skip (`h2` → `h4` fails);
  - every `img` has an `alt` attribute and every `<a>` has an `href` and non-empty text (or an `img` alt);
  - no `tabindex` other than `0` or `-1`;
  - each page loads exactly one stylesheet, the site's `styles.css` (relative, or `/cubentia-site/styles.css`), so the contrast check above covers every page.
- `site/styles.css` and `site/privacy/index.html` stay byte-identical. `privacy-policy.json` and the effective date stay unchanged. Any change to either file is a policy change under the README rules. The existing CSS (headings, `ul`, links, footer) is enough for the new pages.
- `bash scripts/check.sh` passes, including vnu. LF endings, Conventional Commits, and every commit ends with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

**Never:**
- Do not add JavaScript, cookies, other-origin resources, new CSS or new checker allowlist entries. Do not weaken any existing check.
- Do not touch the privacy page or the policy record. Do not add a support link from the privacy page; that is Story 13.5 and needs a new effective date.
- Do not build the full support page (report contents, issues pointer): that is Story 13.5.
- No tester data, no edits to RubikXplorer app code or planning artifacts, no push, no PR.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Shipped site | the three pages plus 404 | `check_a11y.py` OK, `check_privacy.py` OK | — |
| Low text contrast | `--ink-muted: #888888` on `#f4f5f7` (about 3.25:1) | fails, naming 4.5 | exit 1 |
| Link colors overridden | `a { color: var(--accent) }` present | UA link colors not checked | — |
| No `a` color rule | only `body { color }` | UA link colors checked; `#ee0000` on `#f4f5f7` (4.15) fails | exit 1 |
| Low-contrast outline | `outline: 3px solid var(--surface-raised)` | fails, naming 3 | exit 1 |
| Missing or wrong `lang` | `<html>` or `lang="es"` | fails | exit 1 |
| Zero or two `h1`, first heading `h2`, skipped level | — | fails | exit 1 |
| No skip link, or another link first | — | fails | exit 1 |
| Missing landmark, two `main`, `main` without `id="main"` | — | fails | exit 1 |
| `img` without `alt`, empty `<a>`, `<a>` without `href` | — | fails | exit 1 |
| `tabindex="1"` | — | fails | exit 1 |
| Zoom blocked | `user-scalable=no` or `maximum-scale=1` | fails | exit 1 |
| Other stylesheet | `<link rel="stylesheet" href="extra.css">`, or none | fails | exit 1 |

</intent-contract>

## Code Map

- `cubentia-site/site/index.html` -- the current landing page: one paragraph, and the footer `© 2026 ExxerPro` (L20). Keep the skeleton: skip link, `.site-header` wordmark, `main#main`, `.site-footer`. Relative links: `privacy/`, `support/`.
- `cubentia-site/site/404.html` -- footer L20 `© 2026 ExxerPro`. Uses absolute `/cubentia-site/...` paths (check_site rule).
- `cubentia-site/site/privacy/index.html` -- READ-ONLY. The skeleton to mirror for `support/` (`../styles.css`, `../` home link). Its text, links and loaded CSS are hashed into `privacy-policy.json`.
- `cubentia-site/site/styles.css` -- READ-ONLY. It is hashed by the privacy check. Tokens: light ink `#15181c`/`#5a6370`/accent `#5b3fd1` on `#f4f5f7`/`#ffffff`, and the dark equivalents. All are at least 5.5:1. `a { color: var(--accent) }` (L64), `.skip-link` border and `:focus-visible` outline use `--accent`.
- `cubentia-site/scripts/check_privacy.py` -- `UA_COLORS` L121, `MIN_CONTRAST` L125, `check_styles` L472-511: tokens are gathered per scheme from `sheet.rules` (`(dark, selector, declarations)`), then every ink token is checked against every surface token. `css_line` L162 accepts `ink` or `surface` tokens in `border`/`outline`. Use `CSS_VAR` to find the token in a line value. The module docstring and the L106-114 comment describe the contrast rule; update both.
- `cubentia-site/scripts/test_check_privacy.py` -- contrast tests L525 and L538 (`assertCssFails(css, needle)`). The 4.5/3 tests go next to them. Do not change `DATE_MARKER` or the page literals.
- `cubentia-site/scripts/check_site.py` -- the `PageParser` pattern (HTMLParser with `convert_charrefs`) to mirror. Leave it unchanged. It already checks internal links, so `support/` must exist.
- `cubentia-site/scripts/test_check_site.py` -- the unittest style (temp site dir, `build()` helper) to mirror in `test_check_a11y.py`. `check.sh` discovers `test_*.py`.
- `cubentia-site/scripts/check.sh` -- add an "Accessibility structure checks" step after the site checks, and update its header comment.
- `cubentia-site/README.md` -- the publisher wording (L3, L17, L114-115), the layout (add `support/index.html`, `check_a11y.py`, `test_check_a11y.py`), the Check locally list of steps, and one line on the 4.5:1 text and 3:1 border/outline contrast in the privacy-facts bullet on CSS.
- `cubentia-site/LICENSE` -- L3 and L33.

## Tasks & Acceptance

**Execution:**
- `cubentia-site/scripts/check_privacy.py` -- the 4.5:1 text and 3:1 border/outline contrast, and the conditional UA link colors -- closes #2.
- `cubentia-site/scripts/test_check_privacy.py` -- tests: `#888888` muted ink fails naming 4.5; a 4.5-to-5 ink passes; a low border or outline token fails naming 3; UA link colors are checked without an `a` color rule (`#ee0000` fails) and skipped with one; the shipped CSS passes -- proves the contrast rows.
- `cubentia-site/scripts/check_a11y.py` -- the structure checker above -- automates the checkable part of AC3.
- `cubentia-site/scripts/test_check_a11y.py` -- one test per a11y matrix row, plus a test that the shipped `site/` passes -- proves the checker.
- `cubentia-site/scripts/check.sh` -- run `check_a11y.py site`.
- `cubentia-site/site/index.html` -- the landing copy, the links and the footer -- AC1.
- `cubentia-site/site/support/index.html` -- the minimal support page -- no broken link.
- `cubentia-site/site/404.html` -- the footer wording.
- `cubentia-site/README.md`, `cubentia-site/LICENSE` -- the publisher wording, the layout, the check steps and the contrast note.

**Acceptance Criteria:**
- Given `site/index.html`, when it is read, then it says that Cubentia is an offline Android tutor that teaches a beginner to solve a 3×3 cube on a virtual or real cube, without an account or an internet connection, and links to `privacy/` and `support/`. It has no "Rubik", no screenshots and no placeholders.
- Given `site/support/index.html`, when it is read, then it gives `exxerpro@exxerpro.com` as a `mailto:` link, says that no personal data is needed, and links to the privacy policy and home. It has no placeholder text.
- Given `site/`, `README.md` and `LICENSE`, when they are searched for "ExxerPro", then nothing matches. The publisher reads "Exxerpro Solutions, S.A. de C.V.", and the README prose adds "(published on Google Play as Abel Briones)".
- Given `bash scripts/check.sh` on the shipped tree, when it runs locally, then the self-tests, `check_site.py`, `check_a11y.py`, `check_privacy.py` and vnu all pass, and it exits 0. `site/styles.css`, `site/privacy/index.html` and `privacy-policy.json` are unchanged against the baseline (`git diff --quiet 569e450 -- site/styles.css site/privacy privacy-policy.json`).
- Given the PR (PR-recording item, not unmet work), when the PO checks the pages with the keyboard only, at 200% zoom, at 320 CSS px wide, and in light and dark themes, then the results are recorded in the PR. This build automates the structure and contrast part.

## Spec Change Log

## Review Triage Log

### 2026-11-13 — Review pass
- verdicts: 34 findings — high 0, medium 3, low 21, false 10, maybe-false 0
- findings:
  - Blind Hunter
    - `[low]` `[reject]` the 3:1 border/outline rule never decides anything: ink tokens already reach 4.5:1, and a surface-token border is compared with itself. True, so in effect a border or outline must use an ink token. The only harm is that a future decorative surface-colored border is refused, and loudly. A neighbour-aware check would need selector and layout modelling.
    - `[false]` `[reject]` `border-*`/`outline-*` longhands would bypass the 3:1 rule. `CSS_PROPERTIES` allowlists only the `border` and `outline` shorthands, so any longhand is refused before it gets that far.
    - `[medium]` `[patch]` inline `style=""` on the non-policy pages is never contrast-checked, because `check_site.py` allows `style` everywhere, so the claim "the contrast check covers every page" is false. Fixed: `check_a11y.py` rejects any `style` attribute, with a test. No shipped page has one (`grep style= site/` is empty).
    - `[low]` `[patch]` the passing case in `test_text_contrast_needs_4_5` could replace nothing and still pass. Fixed: it now asserts that the original token is present first.
    - `[low]` `[reject]` `test_browser_link_colors_skipped_with_an_a_color_rule` proves only that the shipped CSS passes. Together with the companion test (removing the `a` rule makes `#ee0000` fail), the pair shows the skip. The redundant assertion is harmless.
    - `[low]` `[patch]` "No personal data is needed to get help" is inaccurate, because an email address is personal data. Fixed: the sentence now says that nothing beyond the sender's email address is needed.
    - `[low]` `[reject]` the privacy page has no © footer and no support link. The privacy page is frozen in this story (any change needs a new effective date), and Story 13.5's AC already requires the privacy-to-support link.
    - `[low]` `[patch]` (in part) the landing page has no install link, and the "More" heading is vague. Fixed: the heading is now "Privacy and support". Rejected: the install link, because the app is not public on Play yet and the AC does not ask for one.
    - `[low]` `[reject]` `check_a11y.py` matches landmarks and focusability by tag name (a sectioning `<header>`, `role=`, a `disabled` button, `contenteditable`). No page uses any of these. A false positive fails loudly, and modelling them adds branches.
    - `[false]` `[reject]` several `<title>` elements, an unclosed `<a>`, and `maximum-scale` being stricter than WCAG. vnu in `check.sh` rejects a duplicate title and an unclosed `<a>`. "No `maximum-scale`" is the spec's deliberate rule.
    - `[low]` `[patch]` README line 3 repeats "published", and the LICENSE line overruns the wrap. Fixed: the README now reads "… tutor from Exxerpro Solutions, S.A. de C.V. (published on Google Play as Abel Briones)", and the LICENSE paragraph is re-wrapped.
  - Edge Case Hunter
    - `[low]` `[patch]` a viewport separated by whitespace (`initial-scale=1 user-scalable=no`) is not flagged. Fixed: the parser also splits on whitespace, with a test.
    - `[low]` `[reject]` `user-scalable=false` or `0.5` passes. This is contrived, and the fix adds value-parsing branches.
    - `[medium]` `[patch]` inline `style` colors on other pages. Grouped with the Blind Hunter inline-style row (same fix).
    - `[low]` `[reject]` a `<header>` inside `<article>` is a false positive. Grouped with the Blind Hunter tag-name row.
    - `[false]` `[reject]` a duplicate `id="main"` before `<main>`. vnu reports duplicate IDs as errors, and `check.sh` runs it on every page.
    - `[low]` `[reject]` `lang="EN"` is rejected. The failure is loud, and the site's convention is lowercase `en`.
    - `[low]` `[reject]` a link named only by `aria-label` is reported. The failure is loud, and no page uses `aria-label`.
    - `[false]` `[reject]` an unclosed or nested `<a>` swallows the following text. vnu rejects both, so the page fails `check.sh`.
    - `[low]` `[reject]` a `disabled` or `contenteditable` element before the skip link. Grouped with the Blind Hunter tag-name row.
    - `[low]` `[reject]` `a, a:visited { color }` is not recognized as coloring links. The failure is loud (false positive), and the shipped CSS uses a plain `a` rule.
  - Verification Gap
    - `[low]` `[patch]` (pre-verified) no test puts an inline border or outline on the policy page. Fixed: a test with an inline border expects "3:1".
    - `[low]` `[patch]` (pre-verified) the existing 3:1 cases fail against the browser canvas alone. Fixed: a case passes against the canvas and fails only against a site surface token.
    - `[low]` `[patch]` (pre-verified) an empty `href` is not tested. Fixed: `<a href="">` is now in `test_link_without_href`.
    - `[low]` `[patch]` (pre-verified, filed as defer) a visible `<input>` before the skip link is not tested. Patched instead of deferred, because the test is one line.
    - `[low]` `[reject]` the error paths in `check_a11y` (missing directory, unreadable page, no pages) are untested. Each fails loudly, and `check_site.py` already requires the pages.
    - `[low]` `[reject]` nothing tests that `check.sh` calls `check_a11y.py`. `test_shipped_site_passes` runs the checker on `site/` inside `check.sh`'s unittest step. This follows the 13.4 precedent of not testing `check.sh` itself.
  - Intent Alignment
    - `[medium]` `[patch]` the other pages' contrast is covered only by inference, and an inline style is unchecked. Grouped with the Blind Hunter inline-style row.
    - `[false]` `[reject]` the "automated accessibility checker" is structural only. PO clarification (4) moves the rendered checks to the PO's pass on the PR.
    - `[false]` `[reject]` nothing checks that a focus indicator exists. Rendered focus visibility is part of the PO's manual keyboard pass, which clarification (4) allows.
    - `[false]` `[reject]` "no screenshot placeholders" has no check. The AC is met in the content (there is no `img`), and a reviewer reads it in the PR.
    - `[false]` `[reject]` the boundary with 13.5 (no privacy-to-support link, minimal support page). This matches clarification (1) and 13.5's ownership.
    - `[false]` `[reject]` the PR recording cannot be seen in the diff. It is a PR-recording item by clarification (4).
    - `[false]` `[reject]` "Rubik", script, cookie and origin checks come from existing code. The existing `check_site.py` walks every page, including the new support page, and passes.

## Design Notes

- **Why the CSS stays unchanged:** the privacy check hashes every stylesheet the policy page loads. A change to `site/styles.css` would force a new effective date, and the PO said not to change it unless the rules require it. The new pages use only existing classes and elements.
- **Why the UA link colors are conditional:** with a 4.5 floor, Chrome's default active-link red `#ee0000` on `--surface` `#f4f5f7` (4.15:1) would fail. That color can never show, because author rules override browser colors regardless of specificity, and `a { color: var(--accent) }` colors every link. The checker can prove that rule exists, so it skips the link defaults only then.
- **Why a separate checker:** `check_site.py` covers privacy and branding. Accessibility structure is a separate concern, and a standalone stdlib checker needs no browser or network. It covers what static HTML can prove. Keyboard flow, zoom, reflow and the rendered themes stay with the PO's manual pass on the PR.
- Landing copy shape (illustrative):
  ```html
  <h1>Cubentia</h1>
  <p>Cubentia is an offline Android tutor. It teaches a beginner to solve a 3×3 cube, one step at a time.</p>
  <p>Learners can practice on a virtual cube on screen, or follow along with a real cube in hand.</p>
  <h2>No account, no internet</h2> ...
  ```

## Verification

**Commands (in `/mnt/e/Dynamic/IndFun/cubentia-site`):**
- `bash scripts/check.sh` -- expected: tests OK, site OK, a11y OK, privacy OK (fixture), vnu no errors, exit 0.
- `PRIVACY_BASE=569e450232395e599e9e11220f8467f6fa14b0de bash scripts/check.sh` -- expected: exit 0 (the policy hash is unchanged).
- `git diff --quiet 569e450232395e599e9e11220f8467f6fa14b0de -- site/styles.css site/privacy privacy-policy.json` -- expected: exit 0.
- `grep -rn ExxerPro site README.md LICENSE` -- expected: no output.
- `git ls-files --eol` -- expected: all `i/lf`.

**Manual checks:**
- On the PR: the PO's keyboard, 200% zoom, 320 CSS px and light/dark pass, recorded in the PR.

## Auto Run Result

Status: done

**Summary:** Story 13.2 is implemented in `/mnt/e/Dynamic/IndFun/cubentia-site` on `feat/13-2-landing-page`, in 4 commits on top of `569e450232395e599e9e11220f8467f6fa14b0de`. Nothing is pushed.
- **Landing page:** it says, in calm copy, that Cubentia is an offline Android tutor that teaches a beginner to solve a 3×3 cube on a virtual or a real cube, without an account or an internet connection. It links to `privacy/` and `support/`.
- **Support page:** a new minimal, real `/support/` gives `exxerpro@exxerpro.com`. Story 13.5 expands it.
- **Contrast (closes hebelmx/cubentia-site#2):** the privacy checker now requires 4.5:1 for every text color and 3:1 for border and outline colors (WCAG 1.4.11).
- **Accessibility checker:** the new stdlib `scripts/check_a11y.py` runs inside `check.sh`.
- **Publisher wording:** "ExxerPro" is gone from the site, README and LICENSE.
- **Policy unchanged:** `site/styles.css`, `site/privacy/` and `privacy-policy.json` are byte-identical to the baseline, so the policy hash and its effective date (13 November 2026) are unchanged.

**Files changed (cubentia-site):**
- `site/index.html`: the landing copy, the privacy and support links, and the publisher footer.
- `site/support/index.html`: the new minimal support page.
- `site/404.html`: the publisher footer.
- `scripts/check_privacy.py`:
  - `MIN_TEXT_CONTRAST` 4.5 and `MIN_NON_TEXT_CONTRAST` 3.0 for border and outline tokens, from stylesheets and from `style=""`;
  - the browser's default link colors are checked only when no plain `a { color }` rule exists.
- `scripts/test_check_privacy.py`: the 4.5:1, 3:1 (stylesheet, inline, site surface) and link-color tests.
- `scripts/check_a11y.py`:
  - `lang="en"` and the title;
  - a viewport that allows zoom;
  - the landmarks and `main#main`;
  - the skip link as the first focusable element;
  - one `h1` and heading order;
  - `alt` on images, and an `href` and text on links;
  - `tabindex`;
  - exactly one stylesheet (`styles.css`), and no `<style>` element or `style=""` attribute.
- `scripts/test_check_a11y.py`: one or more tests per matrix row, plus a test that the shipped site passes.
- `scripts/check.sh`: runs the accessibility step.
- `README.md`: the publisher wording, the layout, the check steps, the accessibility checker and the contrast rule.
- `LICENSE`: the publisher wording, re-wrapped.

**Review findings:** 34 in total: high 0, medium 3, low 21, false 10.
- **Patched (10 entries):**
  - medium: inline `style=""` is now rejected on every page (3 findings, one root cause);
  - low: the guard on the 4.5:1 test replace;
  - low: the support-page personal-data wording;
  - low: the "Privacy and support" heading;
  - low: the README and LICENSE wording and wrap;
  - low: the whitespace-separated viewport;
  - low: the inline-border 3:1 test;
  - low: the site-surface 3:1 test;
  - low: the empty-`href` test;
  - low: the visible-`<input>` skip-link test.
- **Deferred:** none.
- **Rejected:** each finding and its reason is in the Review Triage Log. In short:
  - **false:**
    - border and outline longhands are not allowlisted;
    - vnu catches duplicate titles and IDs and unclosed or nested `<a>`;
    - "no `maximum-scale`" is deliberate;
    - rendered checks and focus visibility belong to the PO's manual pass (clarification 4);
    - placeholders are met in the content;
    - the 13.5 boundary;
    - the PR recording;
    - the existing `check_site.py` covers the new page.
  - **low and unlikely, or the fix adds complexity:**
    - the 3:1 rule in effect means "use an ink token";
    - the redundant skip-test assertion;
    - the privacy footer and support link (13.5);
    - no install link (the app is not public);
    - tag-name landmark and focus heuristics;
    - `user-scalable=false`;
    - `lang="EN"`;
    - `aria-label`-only links;
    - `a, a:visited` selector lists;
    - untested error paths;
    - `check.sh` wiring.

**Follow-up review recommended: false.** This was a first pass, and the patched entries by verdict were high 0, medium 1, low 9. No high entry was patched and fewer than two medium entries were patched.

**Verification (after the patches):**
- `bash scripts/check.sh` exits 0. It reports:
  - the checker self-tests OK;
  - `check_site.py` OK;
  - `check_a11y.py` OK;
  - `check_privacy.py` OK against the fixture;
  - vnu: no errors.
- `PRIVACY_BASE=569e450232395e599e9e11220f8467f6fa14b0de bash scripts/check.sh` exits 0, so the policy hash is unchanged.
- `git diff --quiet 569e450… -- site/styles.css site/privacy privacy-policy.json` exits 0.
- `grep -rn ExxerPro site README.md LICENSE` finds nothing.
- `git ls-files --eol` shows every file as LF.

**PR-recording item (not unmet work):** the PO's manual pass goes in the PR:
- keyboard only;
- 200% zoom;
- 320 CSS px wide;
- light and dark themes.

This build automates the structure and contrast part.

**Residual risks:**
- Only the last commits pass `check.sh` on their own. At the first two site commits the self-tests still find "ExxerPro" in the footers. CI runs on the PR head, so this matters only for bisecting.
- `epic-13-context.md` still lists "ExxerPro" and `abel.briones@exxerpro.com`, and Story 13.5's AC in `epics.md` names the old email. The PO's clarifications override both, so 13.5 should use `exxerpro@exxerpro.com`.
- The privacy page still has no link to `/support/`. Story 13.5 owns that link, and adding it needs a new effective date under the README rules.

