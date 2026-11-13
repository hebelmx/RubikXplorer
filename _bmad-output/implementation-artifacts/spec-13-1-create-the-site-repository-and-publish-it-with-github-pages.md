---
title: 'Create the site repository and publish it with GitHub Pages'
type: 'feature'
created: '2026-11-13'
status: 'done'
implementation_repo: '/mnt/e/Dynamic/IndFun/cubentia-site'
implementation_branch: 'feat/13-1-site-repo-and-pages'
baseline_revision: 'e4287b9ff6b3e9f9f458ceea25dda22a7477fdea'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/AGENTS.md'
  - '{project-root}/_bmad-output/implementation-artifacts/epic-13-context.md'
warnings: ['oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** Cubentia has no public web home. Play needs a privacy-policy URL that never changes (Stories 13.4, 13.6), and the site must live in its own repository `hebelmx/cubentia-site` so the app repo's rename (12.5) cannot move it.

**Approach:** In the site repo (`/mnt/e/Dynamic/IndFun/cubentia-site`, branch `feat/13-1-site-repo-and-pages`), add a README, a LICENSE, a minimal `site/index.html`, `site/404.html`, one stylesheet, a standard-library Python check script with self-tests, and `.github/workflows/pages.yml` with a `check` job that gates a `deploy` job. Pull requests run `check` only.

## Boundaries & Constraints

**Always:**
- Plain static HTML and CSS. No JavaScript, no cookies, no tracking, and no resource from another origin. External `<a href>` navigation links are allowed. Everything else (`src`, `<link href>`, CSS `url()`, `@import`) must be same-origin.
- Write links that work on a GitHub Pages project site, which is served under `/cubentia-site/`. Relative links are fine in pages at fixed paths. `404.html` is served at any missing path, so it uses `/cubentia-site/...` absolute paths. The checker treats `/cubentia-site/` as the `site/` root and rejects any other root-relative path.
- Licence (PO decision): MIT for the code (HTML/CSS markup, workflow, scripts), Copyright (c) 2026 ExxerPro. The site content (text, logo, screenshots, the Cubentia name and branding) is © ExxerPro, all rights reserved. State this in both `LICENSE` and the README.
- Pin every action to a full commit SHA with its version tag in a comment. Versions were verified on 2026-11-13 against GitHub releases: `actions/checkout` v7.0.1 `3d3c42e5aac5ba805825da76410c181273ba90b1`, `actions/configure-pages` v6.0.0 `45bfe0192ca1faeb007ade9deae92b16b8254a0d`, `actions/upload-pages-artifact` v5.0.0 `fc324d3547104276b827a68afc52ff2a11cc49c9`, `actions/deploy-pages` v5.0.1 `368f82528645a54fb793d4d04e342629a3f51346`. The HTML validator is the Nu Html Checker, npm `vnu-jar@26.10.12`. It needs Java 11 or later, and ubuntu-24.04 ships Java 17 as the default. No secrets are used.
- Use LF endings everywhere and Conventional Commits. Each commit ends with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

**Never:**
- No landing-page copy beyond a minimal placeholder (13.2), no privacy page or facts check (13.4), no support page or contact email on a page (13.5), no daily schedule or external link check (13.4 and later).
- Do not use "Rubik" anywhere in page text (text nodes, `title`, `alt`, `aria-label`, meta `content`). URLs are exempt.
- No personal data in the repo: no tester names, emails or opt-in links. The README may name ExxerPro as publisher.
- Do not push, open PRs, or edit RubikXplorer beyond this spec and the epic-13 context.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Clean site | `site/` as shipped | `scripts/check.sh` exits 0 | — |
| Broken internal link | `<a href="missing/">` or `#nope` | link check fails, naming file and target | exit 1 |
| Third-party resource | `<link href="https://fonts.googleapis.com/...">`, `<img src="//x.com/a.png">`, CSS `url(https://…)`, `@import url(http…)` | no-third-party check fails | exit 1 |
| Script or cookie | `<script>`, `on*=` attribute, `<meta http-equiv="set-cookie">` | check fails | exit 1 |
| Bad root path | `href="/privacy/"` (escapes the project path) | check fails | exit 1 |
| Rubik in text | text or `alt` contains "rubik" (any case) | check fails; an href to `…/RubikXplorer` passes | exit 1 |
| Invalid HTML | unclosed or misnested elements | vnu reports errors | exit non-zero |
| PR event | `pull_request` | `check` runs; `deploy` is skipped; no Pages artifact is uploaded | — |
| Push to main | `push` to `main` | `check`, then `deploy` (`needs: check`) | deploy never runs if check fails |

</intent-contract>

## Code Map

- `cubentia-site/README.md` -- holds two lines (initial commit `e4287b9ff6b3e9f9f458ceea25dda22a7477fdea`). Expand it.
- Pages API state (2026-11-13): `build_type: workflow`, `cname: null`, `https_enforced: true`, `html_url: https://hebelmx.github.io/cubentia-site/`. The `github-pages` environment exists. `hebelmx/hebelmx.github.io` does not exist (404), so no user site and no custom domain. Final URL: `https://hebelmx.github.io/cubentia-site/`.
- DESIGN tokens (epic-13 context, UX section): light surface `#F4F5F7`/`#FFFFFF`, ink `#15181C`/`#5A6370`, accent `#5B3FD1`. Dark surface `#111317`/`#1A1D22`, ink `#E7EAEE`/`#9AA3AE`, accent `#A797FF`. IBM Plex Sans with a system font fallback, and no webfont download.
- `upload-pages-artifact` tars `path` (it excludes dotfiles, `.git` and `.github`) and needs no special token permission. `configure-pages` reads the Pages API and needs `pages` permission, so it runs in `deploy`.

## Tasks & Acceptance

**Execution:**
- `cubentia-site/.gitattributes` -- `* text=auto eol=lf` -- keeps LF endings.
- `cubentia-site/.gitignore` -- ignore `__pycache__/` and the local preview directory -- keeps the tree clean.
- `cubentia-site/site/styles.css` -- tokens on `:root`, a dark override via `prefers-color-scheme`, a system font stack, visible focus, a skip link -- the one stylesheet.
- `cubentia-site/site/index.html` -- minimal page: `lang="en"`, viewport, title "Cubentia", a skip link, header/main/footer landmarks, one `h1`, one calm sentence, footer "© 2026 ExxerPro" -- the 13.2 placeholder.
- `cubentia-site/site/404.html` -- "Page not found" page with links to `/cubentia-site/` and the stylesheet at `/cubentia-site/styles.css` -- the custom 404.
- `cubentia-site/scripts/check_site.py` -- stdlib checker (html.parser + re) for internal links incl. fragments, no third-party resources, no script/handlers/cookie meta, no bad root paths, no "Rubik" in text; takes the site dir as an argument, default `site`; prints each failure as `file: message` -- the AC2 checks other than HTML validation.
- `cubentia-site/scripts/test_check_site.py` -- unittest that builds temp sites for each matrix row and asserts pass/fail -- proves the checker catches each case.
- `cubentia-site/scripts/check.sh` -- `set -euo pipefail`; runs unit tests, then `check_site.py site`, then `npx --yes vnu-jar@26.10.12 --also-check-css --skip-non-html site` (version in one variable) -- the single command CI and local runs share.
- `cubentia-site/scripts/preview.sh` -- serves `site/` under `/cubentia-site/` using a temp dir and symlink with `python3 -m http.server` -- local preview with production paths.
- `cubentia-site/.github/workflows/pages.yml` -- triggers: `push` to `main`, `pull_request`, `workflow_dispatch`. Top-level `permissions: {}`; concurrency group `pages`, `cancel-in-progress: false`. The `check` job (ubuntu-24.04, `contents: read`) runs checkout, then `scripts/check.sh`, then, on non-PR runs on `refs/heads/main`, `upload-pages-artifact` with `path: site`. The `deploy` job (`needs: check`, runs only on non-PR runs on main) has `pages: write` and `id-token: write`, the `github-pages` environment with `url` from the deployment output, and runs `configure-pages` and `deploy-pages` -- AC2 and AC4.
- `cubentia-site/LICENSE` -- the MIT text for the code, plus a content-rights notice -- the PO licence.
- `cubentia-site/README.md` -- purpose; final URL; custom-domain finding; the never-rename rule (and why the repo must not be called `cubentia`); the no-personal-data rule; layout; how to check and preview locally; how deploy works; licence summary -- AC1 and AC3.

**Acceptance Criteria:**
- Given the repo, when the story is done, then it has README (purpose, never-rename, no-personal-data, local preview), LICENSE, `site/index.html`, `site/404.html`, `.github/workflows/pages.yml`.
- Given `scripts/check.sh` on the shipped tree, when run locally, then it exits 0, and each matrix failure row makes the unit tests' corresponding case fail the checker.
- Given `pages.yml`, when reviewed, then `check` has only `contents: read`, only `deploy` has `pages: write` and `id-token: write`, `deploy` uses `environment: github-pages` and `needs: check`, PRs skip `deploy`, and actions are SHA-pinned to the versions above.
- Given the merged PR (orchestrator post-merge check), when `https://hebelmx.github.io/cubentia-site/` is opened in a private window with no sign-in, then it loads over HTTPS; the README already records this URL and "no custom domain".

## Spec Change Log

## Review Triage Log

### 2026-11-13 — Review pass
- verdicts: 39 findings — high 0, medium 10, low 20, false 5, maybe-false 4
- findings:
  - Blind Hunter
    - `[false]` `[reject]` vnu skips CSS because of `--skip-non-html`. The verification-gap layer added deliberate errors to a copy of `styles.css`; vnu reported both and exited 1.
    - `[medium]` `[patch]` `.htm`, `.xhtml` and `.svg` files go unchecked, and a future SVG logo could carry a script or an external ref. Fix: run them through the page checks, with an SVG test.
    - `[medium]` `[patch]` `ping`, `action`/`formaction`, meta refresh, `xlink:href` and CSS `image-set` strings escape the no-third-party check (confirmed in `PageParser._start` and `_check_css`). Fix: treat each as a resource, with tests.
    - `[low]` `[patch]` `data:` URIs are allowed in CSS but flagged as third-party in HTML attributes. Fix: allow them in both.
    - `[low]` `[patch]` an unreadable `.css` file crashes `run()` (its `open` is not guarded). Fix: report it as `cannot be read as UTF-8`.
    - `[low]` `[patch]` "Rubik" in the `value`/`label` attributes is not caught. Fix: widen `TEXT_ATTRS`. CSS `content:` scanning and the `URL_IN_TEXT` loophole are rejected as low and unlikely, since they would need new parsing.
    - `[low]` `[reject]` self-test gaps for `<base>`, unreadable files and the vnu step. The vnu failure path was checked by hand (broken fixture → 3 errors, exit 1); the other gaps are covered by the patched tests.
    - `[maybe-false]` `[reject]` GitHub's licensee may not detect MIT because of the added preamble and content section. Settle it by checking the repo's license badge after merge. If true, the harm is a cosmetic badge (low). The PO asked for the notice in LICENSE.
    - `[low]` `[reject]` licence split for files that hold both markup and text. The README and LICENSE scope the split as markup versus text, which is the PO's wording.
    - `[low]` `[reject]` no favicon, canonical, robots or theme-color. The favicon 404 is harmless, and branding is Story 13.2's.
    - `[low]` `[reject]` no `timeout-minutes`, no PR cancel group, runner Java/Node not pinned, npx not cached. These are hygiene items; ubuntu-24.04 pins Java 17 as the default.
    - `[low]` `[patch]` the README says every check prints `file: message`, but vnu has its own format. Fix: reworded.
    - `[false]` `[reject]` `preview.sh` symlinks fail on NTFS. The preview was run on this `/mnt/e` checkout and returned HTTP 200.
  - Edge Case Hunter
    - `[medium]` `[patch]` `java&#9;script:` bypasses the prefix check (charref → tab, which urlsplit strips). Fix: check the parsed scheme too, with a test.
    - `[low]` `[patch]` `data:` in img src is flagged (same root cause as the Blind Hunter `data:` row).
    - `[low]` `[reject]` `data:` srcset candidates with commas are mis-split. Unlikely; fixing it needs a candidate-aware parser.
    - `[medium]` `[patch]` `ping` and meta refresh escape the check (grouped with the Blind Hunter third-party row).
    - `[medium]` `[patch]` `xlink:href` escapes the check (grouped with the Blind Hunter third-party row).
    - `[low]` `[reject]` `<base href>` misresolves relative links. An external base is already flagged as a resource, a relative base is unlikely, and the fix would add a guard.
    - `[medium]` `[patch]` CSS `image-set` strings escape the check (grouped with the Blind Hunter third-party row).
    - `[low]` `[reject]` "Rubik" in CSS `content:` is not scanned. Unlikely, and it needs new parsing.
    - `[low]` `[patch]` text attributes `value`, `label`, `aria-roledescription` and `abbr` are not scanned. Fix: added to `TEXT_ATTRS`.
    - `[low]` `[reject]` "Rubik" split across tags or with a soft hyphen is missed. Contrived, and the fix needs per-block text accumulation.
    - `[low]` `[reject]` `URL_IN_TEXT` exempts any `word.tld/` token. Contrived.
    - `[medium]` `[patch]` `.svg`/`.htm` are unchecked (grouped with the Blind Hunter SVG row).
    - `[low]` `[patch]` an unreadable `.css` file crashes the run (grouped with the Blind Hunter CSS row).
    - `[low]` `[reject]` case mismatches pass on case-insensitive filesystems. CI runs on a case-sensitive ext4 runner and is the gate.
    - `[low]` `[reject]` `#top` is reported as a broken fragment. It is a false positive only if someone writes `#top`, and the fix would add a special case.
    - `[medium]` `[patch]` an older run can deploy after a newer one, because concurrency covered only `deploy`. Fix: workflow-level `concurrency: pages-${{ github.ref }}`, `cancel-in-progress: false` (this also restores the spec's top-level placement).
    - `[medium]` `[patch]` the claim that runs are not serialized (same root cause as the concurrency row above).
    - `[maybe-false]` `[reject]` the invalid-HTML matrix row has no unit test. vnu was run by hand on a broken fixture (exit 1), which covers the row. A unit test would need Java inside the unit suite.
  - Verification Gap
    - `[medium]` `[patch]` no tests for `poster`, object `data`, `background`, `imagesrcset`, `<embed>` or `<object>`. Pre-verified. Fix: tests added.
    - `[low]` `[reject]` an absolute canonical `<link href>` to the site's own URL fails as third-party. Deliberately strict; no canonical link is planned yet.
  - Intent Alignment
    - `[false]` `[reject]` the upload in `check` diverges from "deploy publishes". `deploy` runs configure-pages and deploy-pages, which satisfies the AC, and uploading in `check` keeps `pages`/`id-token` out of the job that reads the code.
    - `[maybe-false]` `[reject]` the gating, permissions and job order are checked only as YAML text. actionlint 1.7.7 passed. Settle it with the first PR run (`check` only) and the first main run (`check` then `deploy`).
    - `[false]` `[reject]` the live HTTPS / private-window AC is unproven. The intent assigns it to the orchestrator after merge.
    - `[maybe-false]` `[reject]` the LICENSE is not the verbatim MIT text (same claim as the Blind Hunter licensee row).
    - `[false]` `[reject]` RubikXplorer compliance cannot be judged from the diff. The spec and epic context are committed separately in RubikXplorer at finalize.
    - `[low]` `[reject]` scope goes beyond the AC (script/cookie checks, preview script, stylesheet). These are in line with the PO's no-JS/no-tracking clarification, and nothing harmful was named.

### 2026-11-13 — Review pass (follow-up)
- verdicts: 31 findings — high 0, medium 0, low 24, false 4, maybe-false 3
- findings:
  - Verification Gap
    - `[low]` `[reject]` `srcset` splits a `data:` candidate at its comma — carried: same claim as the first-pass Edge Case row ("`data:` srcset candidates with commas are mis-split"); the loop is unchanged. Unlikely, and the fix needs a candidate-aware parser.
  - Blind Hunter
    - `[low]` `[reject]` `srcset` `data:` comma split — carried (same row as above).
    - `[false]` `[reject]` vnu may skip CSS under `--skip-non-html` — carried: the first pass, the verification-gap layer and the intent auditor all planted a CSS error, and vnu reported it and exited 1.
    - `[low]` `[reject]` no CSP or referrer `<meta>` as a runtime guard. This is defence in depth on top of the static checker, not a defect the change caused; it would add new markup on every page for a case nobody has shown.
    - `[low]` `[reject]` no favicon, so the browser requests `/favicon.ico` at the origin root — carried (first-pass favicon/canonical row); branding is 13.2's.
    - `[maybe-false]` `[reject]` the LICENSE preamble may break GitHub's MIT detection — carried; settle by checking the licence badge after merge. The PO asked for the notice in LICENSE.
    - `[low]` `[reject]` "Rubik" in CSS `content:` and SVG `<title>` is not checked — carried for CSS `content:`. The SVG part is false: `.svg` files go through `PageParser`, so `<title>` text reaches `handle_data`.
    - `[low]` `[reject]` a case mismatch passes on the NTFS mount — carried; CI on ext4 is the gate.
    - `[low]` `[patch]` the `pages.yml` comment "Runs on the same ref queue in order" overstates the guarantee, since a newer pending run replaces an older pending one. Fixed: the comment is reworded (`46030d1`).
    - `[low]` `[reject]` missing tests for same-origin and quoted meta refresh, `<base>`, SVG2 `use`/`image href`, a missing same-origin `@import`, `<area>` and `name=` anchors. A probe confirmed the behaviour is correct: quoted `URL='404.html'` passes, external refresh fails, `use`/`image href` to another origin fails, and a missing `@import` is reported. No defect was found, and adding more tests for working behaviour is optional.
    - `[low]` `[reject]` 404 has no `noindex`, index has no `canonical` — carried (first-pass favicon/canonical/robots row).
    - `[low]` `[reject]` `vnu-jar` has no lockfile or integrity pin — carried (first-pass hygiene row: npx is not cached).
  - Intent Alignment
    - `[maybe-false]` `[reject]` workflow gating (`if:`, `needs:`, permissions) is only checked as YAML text — carried; actionlint 1.7.7 passes again. Settle it with the first PR run and the first main run.
    - `[maybe-false]` `[reject]` the invalid-HTML row has no self-test — carried; vnu fails a broken fixture (confirmed again by the intent auditor).
    - `[low]` `[reject]` the Rubik check's reach is narrower than "anything rendered" (URL-shaped text, CSS `content:`) — carried (first-pass `URL_IN_TEXT` and CSS `content:` rows).
    - `[false]` `[reject]` an unreferenced `.js` file in `site/` passes. No page can run it: `<script>`, `on*` and `javascript:` are all rejected, so a stray file is served as text and never executed.
    - `[false]` `[reject]` the "never" rules are not enforced automatically. The intent constrains what the repo holds, and the repo holds none of it; the intent never asks for automation.
  - Edge Case Hunter
    - `[low]` `[reject]` an empty `site/`, or one missing `index.html`/`404.html`, passes. It is real but unlikely (deleting the page would show in review), and the fix adds a new required-files guard.
    - `[low]` `[patch]` files with uppercase extensions (`X.HTML`, `.CSS`, `.SVG`) skip every check (confirmed: a `<script>` in `X.HTML` printed OK). Fixed: extensions are compared lowercased in `run()` and `_check_fragment`, and `test_uppercase_extensions_are_checked` was added (`96d2bb7`).
    - `[low]` `[patch]` `image-set(... type("image/avif"))` reports a false broken resource `image/avif` (confirmed). Fixed: `type(...)` is stripped before the string scan, and `test_css_image_set_type_passes` was added (`96d2bb7`).
    - `[low]` `[reject]` `srcset` `data:` comma split — carried (same row as above).
    - `[low]` `[reject]` a `data:text/css` stylesheet containing `@import url(https://…)` passes. It is real but contrived, and the fix needs data-URI decoding and re-parsing.
    - `[false]` `[reject]` an unclosed `<style>` hides an `@import`. In HTML, vnu reports the unclosed element and fails `check.sh`. In SVG, the file is malformed XML, so the browser renders nothing and fetches nothing.
    - `[low]` `[reject]` "Rubik" split across inline tags — carried.
    - `[low]` `[reject]` "Rubik" in CSS `content:` — carried.
    - `[low]` `[reject]` the `URL_IN_TEXT` exemption is too wide — carried.
    - `[low]` `[reject]` `<base href>` misresolves links — carried.
    - `[low]` `[reject]` case-insensitive local filesystem — carried.
    - `[low]` `[patch]` two `preview.sh` runs share `.preview/`, so one deletes the other's directory. Fixed: `mktemp -d "${ROOT}/.preview.XXXXXX"`, with `.gitignore` and the README updated (`46030d1`).
    - `[low]` `[reject]` no `timeout-minutes` — carried (first-pass hygiene row).
    - `[low]` `[reject]` the concurrency group is `pages-${{ github.ref }}`, not the spec's `pages`. This was changed on purpose in the first pass: one global group would let a PR's pending run replace a pending main deploy. The only fix would be to edit this spec.

## Verification

**Commands (in `/mnt/e/Dynamic/IndFun/cubentia-site`):**
- `bash scripts/check.sh` -- expected: unit tests OK, checker "OK", vnu prints no errors, exit 0.
- `python3 -m unittest discover -s scripts -p 'test_*.py' -v` -- expected: all pass.
- `python3 -c "import yaml,sys; yaml.safe_load(open('.github/workflows/pages.yml'))"` -- expected: parses. Run `actionlint` too if available.
- `git ls-files --eol` -- expected: every file `i/lf`.

**Manual checks:**
- After merge (orchestrator): Actions run shows `check` then `deploy` green; the URL loads over HTTPS in a private window.

## Auto Run Result

Status: done

**Summary:** Story 13.1 is implemented in `/mnt/e/Dynamic/IndFun/cubentia-site` on `feat/13-1-site-repo-and-pages`, 8 commits on top of `e4287b9ff6b3e9f9f458ceea25dda22a7477fdea`. Nothing is pushed. The site has a minimal Cubentia page and a 404 page, a single stylesheet with the design tokens and a dark scheme, a standard-library checker with 58 self-tests, the Nu Html Checker pinned at `vnu-jar@26.10.12`, and a two-job `pages.yml`. Every action in it is pinned to a commit SHA.

**Files changed (cubentia-site):**
- `.gitattributes`, `.gitignore`: LF endings; ignore `__pycache__` and the preview folder.
- `site/index.html`, `site/404.html`, `site/styles.css`: the minimal page, the 404 page (absolute `/cubentia-site/` paths) and the tokens.
- `scripts/check_site.py`: checks internal links and fragments, third-party resources (src/srcset/poster/data/background/link href/ping/action/formaction/xlink:href/meta refresh/CSS url/@import/image-set), script/on*/javascript:/vbscript:/cookie meta, root-path escapes, relative links in 404.html, and "Rubik" in page text.
- `scripts/test_check_site.py`: 58 unittest cases covering every failure row in the matrix.
- `scripts/check.sh`: the one command CI and local runs share (tests, then the checker, then vnu with CSS).
- `scripts/preview.sh`: serves `site/` under `/cubentia-site/`.
- `.github/workflows/pages.yml`: triggers on push to main, PR and dispatch. Top-level permissions are `{}`. Workflow concurrency is `pages-${{ github.ref }}`. `check` has `contents: read` and uploads the Pages artifact only on main. `deploy` needs `check`, runs only on main, has `pages: write` and `id-token: write`, and uses the `github-pages` environment with configure-pages and deploy-pages.
- `LICENSE`: MIT for the code, Copyright (c) 2026 ExxerPro, plus "site content © ExxerPro, all rights reserved".
- `README.md`: purpose; URL `https://hebelmx.github.io/cubentia-site/`; no custom domain (`cname: null`, no user site); the never-rename and no-personal-data rules; how to check and preview locally; how deploy works; the licence.

**Review findings:** 39 in total: high 0, medium 10, low 20, false 5, maybe-false 4.
- Patched: 10 medium rows (3 entries after grouping):
  - the `java&#9;script:` bypass;
  - unchecked resource kinds (ping, action/formaction, xlink:href, meta refresh, image-set);
  - unchecked `.svg`/`.htm`/`.xhtml`;
  - out-of-order deploys, fixed with workflow-level concurrency;
  - missing tests for poster/object/background/imagesrcset/embed.
- Patched: 8 low rows:
  - `data:` handled differently in HTML and CSS;
  - an unreadable `.css` file crashed the run;
  - more Rubik text attributes;
  - the README output-format wording.
- Deferred: none.
- Rejected: every rejected row and its reason is in the Review Triage Log above. In short:
  - false positives: CSS validation does run, preview works on NTFS, the upload placement meets the AC, the live AC belongs to the post-merge check;
  - unlikely low items whose fixes add complexity: srcset data commas, `<base>`, CSS `content:`, split words, the URL-in-text loophole, case-insensitive filesystems, `#top`, canonical links;
  - cosmetic or hygiene items: favicon/canonical/robots, timeouts, runner toolchain pins, licensee detection.

**Follow-up review recommended: true.** Two or more medium entries were patched on a first pass (patched entries by verdict: high 0, medium 10, low 8). The unverified risk: the patched resource and scheme handling in `check_site.py` (meta refresh parsing, image-set bracket matching, SVG link checks) has not had an independent review.

**Verification:**
- `bash scripts/check.sh` exits 0: 58 tests OK, the checker prints OK, vnu reports no errors.
- vnu run by hand on a broken HTML fixture: 3 errors, exit 1.
- `pages.yml` parses as YAML, and actionlint 1.7.7 reports nothing.
- `git ls-files --eol`: every file is LF.
- The pinned SHAs match their tags: checkout v7.0.1, configure-pages v6.0.0, upload-pages-artifact v5.0.0, deploy-pages v5.0.1.

**Residual risks:**
- The workflow has not run on GitHub yet. Watch the first PR run (`check` only) and the first main run (`check`, then `deploy`).
- The post-merge check is still to do: open the URL over HTTPS in a private window with no sign-in.
- CI fetches vnu from npm on every run.
- GitHub's licence detection may show "Other" because of the content notice.

### Follow-up review pass (2026-11-13)

**Summary:** An independent follow-up review of the first pass's checker fixes (script URLs, more resource kinds, SVG, unreadable CSS). It found no high or medium problems. Four low problems were patched in two new commits in `/mnt/e/Dynamic/IndFun/cubentia-site`, `96d2bb7` and `46030d1`, which makes 10 commits on top of the baseline. Nothing is pushed.

**Files changed (this pass):**
- `scripts/check_site.py`: page and CSS file extensions are matched case-insensitively, and `type("…")` inside `image-set()` is no longer read as a URL.
- `scripts/test_check_site.py`: two new tests, `test_uppercase_extensions_are_checked` and `test_css_image_set_type_passes` (60 in total).
- `scripts/preview.sh`, `.gitignore`, `README.md`: each preview run gets its own `mktemp` directory `.preview.XXXXXX`.
- `.github/workflows/pages.yml`: the concurrency comment now describes how GitHub replaces pending runs.

**Review findings:** 31 in total: high 0, medium 0, low 24, false 4, maybe-false 3.
- Patched: 4 low entries:
  - uppercase extensions skipped every check;
  - an `image-set` `type()` false positive;
  - the shared preview directory;
  - the concurrency comment.
- Deferred: none.
- Rejected:
  - 19 rows carried from the first pass with the same reasons, including `srcset` `data:` commas, CSS `content:`, `<base>`, case-insensitive filesystems, favicon/canonical, licence detection, workflow gating seen only as text, and hygiene items;
  - newly rejected:
    - no CSP meta (defence in depth, not a defect);
    - extra tests for working behaviour (probed correct);
    - an empty site passes (unlikely, and needs a new guard);
    - a `data:` stylesheet with `@import` (contrived, needs decoding);
    - the per-ref concurrency group (deliberate; the only fix would edit the spec);
  - false: a stray `.js` file cannot run, the "never" rules need no automation, and an unclosed `<style>` is caught by vnu or breaks the SVG.

**Follow-up review recommended: false.** This was a follow-up pass and it patched no high entry (patched entries by verdict: high 0, medium 0, low 4), so the work has converged.

**Verification:**
- `bash scripts/check.sh`: 60 tests OK, the checker printed OK, vnu reported no errors.
- `pages.yml` parses as YAML, and actionlint 1.7.7 reports nothing.
- `git ls-files --eol`: every file is LF.
- `scripts/preview.sh 8765` returned HTTP 200 at `/cubentia-site/`, and the temporary directory was removed on exit.
- Hand probes of meta refresh, SVG `use`/`image href` and a missing `@import` behaved as expected.

**Residual risks:** unchanged from the first pass.
- The workflow has not run on GitHub yet.
- The post-merge HTTPS check in a private window is still to do.
- vnu is fetched from npm on every run.
- GitHub's licence detection may show "Other".
- Known low gaps remain in the checker: `srcset` `data:` commas, CSS `content:` wording, and `data:` stylesheets.
