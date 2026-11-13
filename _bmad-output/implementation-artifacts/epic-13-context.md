# Epic 13 Context: Cubentia on the web, with a privacy policy

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal

Give Cubentia a public website (a landing page, a privacy policy and a support page) with a privacy-policy URL that never changes, and register that URL in Play Console. Google Play's closed-testing review cannot go ahead without it: the Data safety form can't be saved until a public privacy-policy URL is registered, so the pilot (#127, Story 7.8 AC2) is blocked until this epic is done. The site lives in its own public repository, `hebelmx/cubentia-site`, published by GitHub Pages. When Story 12.5 renames the app repository, the privacy URL is not affected, because GitHub Pages project URLs are not redirected after a rename. A CI check holds the privacy page to the facts that the app's build enforces.

## Stories

- Story 13.1: Create the site repository and publish it with GitHub Pages
- Story 13.2: Introduce Cubentia on a landing page
- Story 13.3: Export the app's privacy facts from the build
- Story 13.4: Publish a privacy policy that matches the app
- Story 13.5: Give Learners a support and contact page
- Story 13.6: Register the privacy policy in Play Console

## Requirements & Constraints

- **Privacy URL requirements from Play:** the page is public, loads without any sign-in (testers are mostly Gmail users outside exxerpro.com), is not geofenced and is HTML, not a PDF. It names the publisher exactly as on the Play developer account ("ExxerPro"), and gives the contact email `abel.briones@exxerpro.com` and an effective date.
- **Permanent URL:** `https://hebelmx.github.io/cubentia-site/privacy/`. Never rename `cubentia-site`. The site repo must not be named `cubentia`, because repository names are case-insensitive and it would collide with the renamed app repo. Story 13.1 checks whether the account's user site sets a custom domain, which would change every project URL, and records the final URL. A custom domain may come later; GitHub redirects `github.io` to it.
- **Privacy facts the policy states:** no personal data collected or shared; no account, ads, analytics or crash reporting; no INTERNET permission; no backup; no runtime permission; progress stored only in app-private storage and removed on uninstall; applies to Learners of any age.
- **The site doesn't track either:** no cookies, no script, no third-party embeds, and no resources from other origins (no Google Fonts; fonts are self-hosted IBM Plex under its OFL licence, or a system font stack). The policy says the site is hosted on GitHub Pages, which may log visitor IP addresses for security, and links GitHub's privacy statement.
- **Accessibility, WCAG 2.2 AA:** `lang="en"`, landmarks, one `h1` and ordered headings, a skip link, visible focus in reading order, keyboard-only navigation, no horizontal scroll at 200% zoom and at 320 CSS px, AA contrast in light and dark themes, and `alt` on every image. Results are recorded in the PR.
- **Copy:** English only. Never "Rubik" or "Rubik's". The app's users are called "Learners". No screenshot placeholders; screenshots come later from the Story 12.5 store assets.
- **Public repo rule:** no tester names, emails, group addresses or opt-in links in the site repo, and none in #127 either.

## Technical Decisions

- **Two repos.** The app repo derives the facts and the site repo checks the page against them. Stories 13.3 and 13.6 land in this repo. Stories 13.1, 13.2, 13.4 and 13.5 land in the site repo and are built by hand with `bmad-build` in a site-repo checkout; the orchestrator gates them and does not drive them.
- **Facts file (app repo):** `docs/privacy-facts.json` is committed. `:app:writePrivacyFacts` generates it. `:app:checkPrivacyFacts` is wired into `build`, fails on a stale file, and its message names `writePrivacyFacts` and the site's privacy page. It is listed in CI's "Verify build wires the gates" step.
  - Derived from the **release** merged manifest and the release SDK-denylist result: `internetPermission`, `backupAllowed`, `runtimePermissions`, `denylistedSdks`. For v1.0 these are `false`, `false`, `[]`, `[]`.
  - Also in the file: `schemaVersion`, `applicationId`, and the fixed facts `account`, `ads`, `analytics`, `crashReporting`, `dataLeavesDevice` and `storage`. The fixed facts change only through a reviewed PR.
  - Gradle TestKit fixtures prove that adding INTERNET, `allowBackup="true"` or a runtime permission changes the facts and fails the check.
  - Changing a manifest fact already needs a deliberate `ManifestPolicy` change (the existing offline and privacy enforcement). No app code changes in this epic.
- **Site build:** hand-written static HTML and one CSS file under `site/` (`index.html`, `privacy/`, `support/`, `404.html`). No generator and no JavaScript. `.github/workflows/pages.yml` runs two jobs:
  - a `check` job with `contents: read`, which runs on PR, on push, daily and on manual dispatch;
  - a `deploy` job with `needs: check`, `pages: write`, `id-token: write`, the `github-pages` environment, and `configure-pages`, `upload-pages-artifact` and `deploy-pages`. A PR runs `check` only. Verify the action versions against GitHub's current docs.
- **`check` job contents:**
  - HTML validation;
  - an internal link check, blocking;
  - a no-third-party check covering every `src`, `<link href>`, CSS `url()` and `@import` (all same-origin), no `<script>` and no cookie meta;
  - no "Rubik" in page text;
  - the facts check, below.
  - An external link check runs weekly and is advisory.
- **Facts check (site repo):** a sparse `actions/checkout` reads `docs/privacy-facts.json` from `repository: ${{ vars.APP_REPO }}`. The app repo is public, so it needs no token. A standard-library Python script then checks the page. Each statement on the page carries `data-fact="<key>"` and `data-value="<value>"`. The check fails when:
  - a fact in the file has no statement with an equal value on the page;
  - the page states a fact the file lacks;
  - the publisher, contact email or effective date is missing;
  - the policy text changed without a new effective date.
- **Drift detection:** the daily scheduled run plus the pre-deploy check. There is no `repository_dispatch` and no cross-repo secret.

## UX & Interaction Patterns

- Reuse the DESIGN.md color tokens with `prefers-color-scheme`. Light: surface `#F4F5F7`/`#FFFFFF`, ink `#15181C`/`#5A6370`, accent `#5B3FD1`. Dark: surface `#111317`/`#1A1D22`, ink `#E7EAEE`/`#9AA3AE`, accent `#A797FF`. Typeface: IBM Plex Sans.
- Voice: precise, calm and on the Learner's side. Short declarative sentences, Glossary terms, no exclamation marks.
- Landing page: an offline Android tutor that teaches a beginner to solve a 3×3 cube on a virtual or real cube, with no account and no internet. It links to the privacy and support pages, and the privacy page also links to support.
- Support page: the same contact email as the policy, and what to include in a report: device, Android version, app version (from Settings → Apps → Cubentia) and what happened. It says no personal data is needed, links the privacy policy, and points developers to the public source repo's issues without asking Learners to post personal details.

## Cross-Story Dependencies

- Critical path: 13.1 → 13.3 → 13.4 → 13.6. Story 13.3 can run in parallel with 13.1. Stories 13.2 and 13.5 depend on 13.1, and 13.5 must land before 12.5.
- Human gates:
  - 13.1, before start: the PO creates the repo, sets Pages source to GitHub Actions, protects `main` and sets `APP_REPO=hebelmx/RubikXplorer`.
  - 13.4, before merge: the PO confirms the publisher name and the effective date.
  - 13.6, before start: the PO enters the URL under App content → Privacy policy and saves the Data safety form with "no data collected".
- Story 13.6 also updates this repo:
  - `docs/play-first-publish.md` § 2 step 1 names the site repo and the URL instead of `docs/privacy.md`;
  - the README links the website and the privacy policy;
  - #127 records the date the URL was registered.
- Epic 13 runs before Stories 12.3, 12.4 and 12.5. Story 12.3 depends on 13.6, and 12.5 on 13.4 and 13.5.
- When Story 12.5 renames the app repo, it updates the site's `APP_REPO` and source links, and the site's `check` must still pass.
- After 13.3 merges, an AGENTS.md refresh adds a pointer to the website and to the privacy-facts file.
