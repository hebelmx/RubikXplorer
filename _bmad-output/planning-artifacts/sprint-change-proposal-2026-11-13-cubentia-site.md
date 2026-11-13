---
title: 'Sprint Change Proposal: Epic 13, a public Cubentia website with a privacy policy, in its own repository'
date: 2026-11-13
status: approved
approved_by: hebelmx (PO), 2026-11-13 (D1 option A; D2 publisher "ExxerPro", contact abel.briones@exxerpro.com; D3 hebelmx; D4 site-repo stories by hand; D5 daily check)
trigger: 'PO request 2026-11-13: a public GitHub Pages website for Cubentia with a privacy policy, as one new BMAD epic. Google Play closed testing (issue #127, docs/play-first-publish.md § 2) needs a public privacy-policy URL. PO input 2026-11-13: two repositories, one for the app source and one for the website.'
scope: Moderate
mode: batch (non-interactive; drafted 2026-11-13, approved by the PO the same day, and applied to epics.md, the PRD, the spine and sprint-status in this SCP's branch)
---

# Sprint Change Proposal: Epic 13, a public Cubentia website with a privacy policy

## 1. Issue Summary

**Trigger.** Version 0.9.0 (`versionCode` 1) is a draft on the internal track (`docs/play-first-publish.md`, "Where things stand"). Closed testing for the pilot goes through Google's review. That review needs every App content declaration, and the Data safety form can't be saved without a privacy-policy URL (`docs/play-first-publish.md` § 2). So the pilot (Story 7.8 AC2, issue #127, sprint action item `epic-7-retro-item-66`) is blocked until a privacy policy is public.

**What Play requires of the URL:**
- it is public, loads without a login, and is not geofenced;
- it is not a PDF;
- it names the developer exactly as on the Play developer account (the verified ExxerPro organization account, `docs/release.md:8`);
- it gives a contact email and an effective date.

**Who reads it.** The pilot testers are colleagues outside exxerpro.com, mostly Gmail users. The page must be readable by anyone, with no Workspace sign-in. Their opt-in path is already covered in `docs/play-first-publish.md` § 4.

**Who owns it today.** Story 12.5 owns "a privacy-policy page" (AC2). That story runs last in Epic 12, after the pilot, so the page would arrive too late. `docs/play-first-publish.md:33` recommends a `docs/privacy.md` page in this repo. That clashes with the developer docs in `docs/`, and it would put the privacy URL under `/RubikXplorer/`.

**The URL-stability problem.** Story 12.5 AC3 also renames the GitHub repository `hebelmx/RubikXplorer` (public) and the local folder to Cubentia. GitHub redirects git and web URLs after a rename, but **GitHub Pages project URLs (`https://<owner>.github.io/<repo>/`) are not redirected**. A privacy URL registered in Play under `/RubikXplorer/` would break when 12.5 runs.

**PO input (2026-11-13).** The PO prefers two repositories: this one for the app source (renamed to Cubentia in 12.5, as planned), and a separate repository for the website. This SCP makes that the recommended option (§3, decision D1). With a separate site repo, the app repo's rename no longer touches the privacy URL.

**Privacy facts to state** (all enforced in this repo today):
- The release manifest has no `INTERNET` permission, no backup (`allowBackup="false"`, exclude-all `dataExtractionRules`) and no runtime permissions. `ManifestPolicy` (`build-logic/.../policy/ManifestPolicy.kt`) and `:app:checkReleaseMergedManifest` enforce this (AD-13).
- There is no account and no analytics, ads or crash SDK. `:app:checkReleaseSdkDenylist` enforces the SDK part.
- Progress is stored only in app-private storage on the device, and is removed when the app is uninstalled (NFR-8).

## 2. Impact Analysis

| Area | Impact |
|---|---|
| Epics | **New Epic 13** with six stories (§4.1). **Story 12.5:** AC2 points at Epic 13's privacy URL, and AC3 also updates the site repo's links (§4.2). **Story 12.3:** gains a dependency on 13.6, because its data-safety AC needs the registered URL. **Story 7.8** (done): an annotation, since AC2's closed-testing review waits for 13.6. Epic List, Epic 12 header and NFR coverage line are updated. No story is removed. |
| PRD | §7 *Store readiness* gets a note on where the privacy page lives and that the site has no tracking. §10 gets a decision line. No FR or NFR changes. Routed to `bmad-prd` update (§4.4), because the PRD is final. |
| Architecture spine | AD-13 gets one rule bullet: the public privacy policy states the same facts the build enforces, through a facts file checked in CI. The deployment diagram gets the site. Routed to `bmad-architecture` update (§4.5). No new module, port or AD. |
| UX | **No change.** DESIGN.md and EXPERIENCE.md specify the app. The site reuses the DESIGN.md color tokens and the EXPERIENCE.md *Voice and Tone*. The story ACs cite them, so no UX text needs to change. |
| Code (this repo) | Story 13.3 adds a `build-logic` task, `:app:checkPrivacyFacts`, and a committed `docs/privacy-facts.json`. CI's "Verify build wires the gates" list gains the task. No app code changes. |
| New repository | `hebelmx/cubentia-site` (name per D1): static HTML/CSS in `site/`, a Pages workflow, and its own checks (§3.3). The repo is public, so the existing rule applies: no tester names, emails, group addresses or opt-in links in it. |
| Docs (this repo) | `docs/play-first-publish.md` § 2 step 1 points at the site instead of `docs/privacy.md`. README links the website and the privacy policy. These are done in 13.6. |
| AGENTS.md | After 13.3, a `bmad-project-context` refresh adds a "Where things are" pointer: the website lives in the site repo, and the privacy facts are `docs/privacy-facts.json`, checked by `:app:checkPrivacyFacts`. |
| Orchestrator | The gates in `_bmad/custom/bmad-orchestrate.toml` gain entries for Epic 13 (§4.6), added with the `bmad-customize` skill, not by hand. Stories 13.1, 13.2, 13.4 and 13.5 land in the site repo, which the orchestrator does not yet drive (decision D4). |
| sprint-status | Described in §4.7. It is applied later with `sprint_plan.py`. |
| Not changed | `_bmad/`, `.claude/skills/`, historical records, planning folder names, the app manifest, `ManifestPolicy`, the SDK denylist. |

### Sequencing

The critical path to the pilot review is **13.1 → 13.3 → 13.4 → 13.6**. Then the PO finishes `docs/play-first-publish.md` §§ 2–4 and #127.

- 13.2 (landing page) and 13.5 (support page) are off the critical path. 13.1 publishes a minimal index, so the site root never returns 404. 13.5 should land before 12.5, because the store listing's website field points at it.
- 13.3 is in this repo and has no dependency on 13.1, so it can run in parallel with it.
- **New dependencies:** 12.3 depends on 13.6 (its data-safety AC needs the URL). 12.5 depends on 13.4 and 13.5. #127 § 2 waits for 13.6.
- **Order relative to Epic 12:** Epic 13 runs now, before the remaining Epic 12 stories (12.3, 12.4, 12.5). 12.1 stays in `review` and is not affected. Story IDs stay stable; Epic 13 simply executes before the end of Epic 12, as other approved follow-ups have (`epics.md` "How to read it").

### Epic numbering: why 13

- Epics 10 and 11 were moved to `post-launch/v1.1-camera-recognition.md`, but their numbers are still taken there (Stories 10.1–10.8, 11.x), and the gates file still keys on `10-2-…`, `10-8-…` and `11-1-…`.
- Epic 12 is the highest number in `epics.md`.
- So the next free number is **13**. Reusing 10 or 11 would collide with the v1.1 plan when it returns.
- The Epic List heading `### Epic 13:` matches `sprint_plan.py`'s `EPIC_RE`, which is intended here: it should generate `epic-13`.

### Checklist summary

- [x] 1 Trigger and context: a new requirement from an external constraint (Play review), plus the PO's two-repository decision. The evidence is `docs/play-first-publish.md` § 2 and #127.
- [x] 2 Epic impact: new Epic 13. Story 12.5 is edited, 12.3 gains a dependency, and 7.8 is annotated. No epic is invalidated.
- [x] 3 Artifact conflicts: epics.md (§4.1–4.3), PRD (§4.4, routed), spine (§4.5, routed), UX [N/A], docs (in 13.6), gates (§4.6, via `bmad-customize`).
- [x] 4 Path: Direct Adjustment, adding an epic. Rollback [N/A]. MVP review [N/A]: the privacy page was always in scope (PRD §7). It only moves earlier and gets its own home.
- [x] 5 Proposal components: §§3–4.
- [x] 6 Approval: the PO approved the proposal on 2026-11-13 and took D1–D5 as recommended (§5).

## 3. Recommended Approach

**Direct Adjustment: add Epic 13.** A separate public site repository is served by GitHub Pages under a name that never changes. The privacy policy is checked against facts that this repo's build derives from the release manifest.

### 3.1 Hosting options (decision D1)

| # | Option | Privacy URL | Stable across the 12.5 rename? | Trade-offs |
|---|---|---|---|---|
| **A (recommended)** | Separate project repo **`hebelmx/cubentia-site`**, Pages from GitHub Actions | `https://hebelmx.github.io/cubentia-site/privacy/` | **Yes.** Only this repo is renamed in 12.5. | It is the PO's preference and needs no DNS. Repo creation and Pages settings are a 15-minute human step. The URL shows the personal GitHub account rather than ExxerPro, but Play checks that the page *names* the publisher, not the domain. **The rule from then on: never rename `cubentia-site`.** |
| B | Option A now, plus a custom domain later (for example `cubentia.exxerpro.com`) | Starts as in A, then the custom domain | Yes | When a custom domain is set on a Pages repo, GitHub redirects the `github.io` URL to it, so the URL registered in Play keeps working. It needs exxerpro.com DNS (CNAME) and GitHub domain verification, to prevent takeover. It can be a v1.1 or post-launch story. Not needed for the pilot. |
| C | Custom domain from day one | `https://cubentia.exxerpro.com/privacy/` | Yes, and it survives any move off GitHub | Most durable, and it matches the ExxerPro publisher. But it needs DNS access and propagation now, on the pilot's critical path. Recommended only if exxerpro.com DNS can be changed today. |
| D | Same repo, `site/` folder, with the 12.5 GitHub rename pulled forward into Epic 13 | `https://hebelmx.github.io/Cubentia/privacy/` | Yes, if the rename lands first | One repo. But the PO prefers two. Pages config, workflow permissions and site history would mix with the app's release workflow. The rename would also move ahead of 12.5's README/portfolio work. Not recommended. |
| E | User site repo `hebelmx/hebelmx.github.io` | `https://hebelmx.github.io/cubentia/privacy/` | Yes | It uses up the developer's personal root site, which may be wanted for the job-search portfolio. It mixes app policy with a personal site. Not recommended. |

**Name constraint.** The site repo **must not be named `cubentia`**. GitHub repository names are case-insensitive, so `hebelmx/cubentia` would collide with the app repo once 12.5 renames it to `hebelmx/Cubentia`. Use an all-lowercase name, and register the URL in Play exactly as served.

**Check in 13.1.** If the `hebelmx` account's user site (`hebelmx.github.io` repo) has a custom domain, every project site is served under that domain instead. 13.1 records whether it does, and the final URL.

### 3.2 Site build and content

- **Static, with no toolchain.** Hand-written HTML and one CSS file in `site/`. There is no generator and no JavaScript. Pages deploys the `site/` folder as an artifact through the official Pages actions (`actions/configure-pages`, `actions/upload-pages-artifact`, `actions/deploy-pages`). Their current major versions are verified against GitHub's docs when the story is built (AGENTS.md pitfall: verify versions).
- **Pages:**
  - `/`: the landing page (13.2);
  - `/privacy/`: the privacy policy (13.4);
  - `/support/`: support and contact (13.5);
  - a `404.html`.
  Screenshots come later, from the 12.5 store assets.
- **No tracking on the site itself**, to match the claim it makes:
  - no cookies, no analytics, no third-party embeds;
  - no externally hosted scripts, styles or fonts (no Google Fonts: IBM Plex is self-hosted under its OFL licence, or a system font stack is used);
  - the policy says plainly that the site is hosted on GitHub Pages, and that GitHub may log visitor IP addresses for security, linking GitHub's privacy statement.
- **Copy:** EXPERIENCE.md *Voice and Tone* (precise, calm, short declarative sentences). Glossary terms, with "Learner" used for the app's users. English only (NFR-10). Never "Rubik" or "Rubik's" (OQ-1; the same rule as `checkLearnerText`).
- **Accessibility (WCAG 2.2 AA, matching NFR-7):**
  - `lang="en"`, landmarks, one `h1` per page, ordered headings, a skip link;
  - visible focus, keyboard-only navigation;
  - text readable at 200% zoom and at 320 CSS px with no horizontal scroll;
  - AA contrast using the DESIGN.md light and dark tokens via `prefers-color-scheme`;
  - `alt` text on every image.

### 3.3 Keeping the privacy page true across two repositories

The facts live in **this** repo, where the build enforces them. The page lives in the **site** repo. The proposal joins them with one machine-readable file and a check at each end.

1. **This repo (Story 13.3): `docs/privacy-facts.json`, committed.** A `build-logic` task, `:app:checkPrivacyFacts`, is wired into `build`.
   - It derives `internetPermission`, `backupAllowed` and `runtimePermissions` from the **release** merged manifest, and `denylistedSdks` from the release SDK-denylist result.
   - It fails if the committed file disagrees. The failure message tells the developer to regenerate it with `:app:writePrivacyFacts` and update the privacy policy in the site repo.
   - Facts that the manifest cannot show (`account`, `ads`, `analytics`, `crashReporting`, `dataLeavesDevice`, `storage`) are fixed values in the file, sourced from NFR-8. They change only through a reviewed PR.
   - Any change to the manifest facts already needs a deliberate `ManifestPolicy` change (AD-13), for example v1.1's `CAMERA`. So the facts file can't drift silently.
2. **Site repo (Stories 13.1, 13.4): a `check` job that runs before every deploy**, and also on a daily schedule and on manual dispatch.
   - It checks out `docs/privacy-facts.json` from the app repo's `main` with `actions/checkout`, using `repository: ${{ vars.APP_REPO }}` (the app repo is public, so no token or secret is needed) and a sparse checkout.
   - The `APP_REPO` variable starts as `hebelmx/RubikXplorer`. Story 12.5 changes it to `hebelmx/Cubentia`. GitHub's rename redirect also covers the gap.
   - The privacy page marks each statement with `data-fact="<key>"` and `data-value="<value>"`. A small Python check (standard library only) fails if:
     - any fact in the file has no statement with an equal value on the page, or the page states a fact that the file lacks;
     - the publisher, contact email or effective date is missing;
     - the policy text changed in a PR but the effective date did not.
   - **The deploy job `needs: check`**, so a page that disagrees with the app is never published.
3. **When the app's facts change first** (an app PR edits `docs/privacy-facts.json`), the site's daily run fails within 24 hours. GitHub emails the repo owner on scheduled-workflow failure. The 13.3 failure message and the app repo's PR template remind the author to update the site in the same change window. A `repository_dispatch` from the app repo would be immediate, but it needs a cross-repo token secret. Not recommended for now (D5).

Other checks in the site repo's `check` job:
- **internal link check**, blocking (lychee in offline mode, or the same Python script);
- **external link check**, weekly and advisory;
- **HTML validation**;
- **no-third-party check:** every `src`, `<link href>`, CSS `url()` and `@import` is same-origin, there is no `<script>`, and there is no `Set-Cookie`-style meta;
- **no "Rubik"** in any page text.

### 3.4 Effort, risk, timeline

- **Effort:** about 1.5–2 working days of agent work (13.2 to 13.5, about half a day each, with 13.5 shorter), plus about an hour of PO time (13.1 repo creation and Pages settings, 13.4 statement confirmation, 13.6 Play Console entry).
- **Risk:** low.
  - Nothing in the app changes.
  - The main risk is the URL changing after Play registration. D1 and the "never rename `cubentia-site`" rule remove it.
  - The second risk is the page drifting from the app. §3.3 makes that a CI failure.
- **Timeline:** the pilot review waits for 13.6, about 1–2 days, then Google's first review of up to 7 days (`docs/play-first-publish.md` § 4). The rest of Epic 12 is unchanged in content. 12.3 and 12.5 simply wait for Epic 13.

## 4. Detailed Change Proposals

### 4.1 epics.md: new Epic 13 (to append after the last Epic 12 story, Story 12.13)

Exact text:

```markdown
## Epic 13: Cubentia on the web, with a privacy policy

Anyone can open Cubentia's public website, read what the app is, read a privacy policy that states exactly what the app does with data, and find how to get support. Play Console has a privacy-policy URL that never changes. The site lives in its own public repository (`hebelmx/cubentia-site`), so the app repository's rename in Story 12.5 does not affect it (`sprint-change-proposal-2026-11-13-cubentia-site.md`).

**Runs before** Stories 12.3, 12.4 and 12.5, and before the closed-testing review in #127 (Story 7.8 AC2). Critical path: 13.1 → 13.3 → 13.4 → 13.6. Story 13.3 lands in this repository; Stories 13.1, 13.2, 13.4 and 13.5 land in the site repository.

### Story 13.1: Create the site repository and publish it with GitHub Pages

As the developer,
I want a separate public repository that GitHub Pages publishes on every merge,
So that Cubentia's web pages have a home and a URL that never changes.

**Refs:** PRD §7 store readiness · NFR-8 · AD-13 · `docs/play-first-publish.md` § 2

**Human gate (before start):** the PO creates the public repository `hebelmx/cubentia-site`, sets **Settings → Pages → Source: GitHub Actions**, protects `main` (PRs only), and sets the repository variable `APP_REPO=hebelmx/RubikXplorer`.

**Acceptance Criteria:**

**Given** the new repository
**When** the story is done
**Then** it has a README (purpose, the never-rename rule, the no-personal-data rule for a public repo, how to preview locally), a licence chosen by the PO, `site/index.html` with a minimal Cubentia page, `site/404.html`, and `.github/workflows/pages.yml`

**Given** a push to `main`
**When** `pages.yml` runs
**Then** a `check` job (HTML validation, internal link check, no-third-party-resource check, no "Rubik" in page text) passes before a `deploy` job publishes `site/` through the GitHub Pages actions, and a pull request runs `check` only

**Given** the deployed site
**When** `https://hebelmx.github.io/cubentia-site/` is opened in a private browser window with no Google or GitHub sign-in
**Then** it loads over HTTPS, and the repository README records the final URL and whether the account's user site sets a custom domain

**Given** the workflow
**When** it is reviewed
**Then** it uses least-privilege permissions (`contents: read` for check; `pages: write`, `id-token: write` only for deploy), the `github-pages` environment, and action versions verified against GitHub's current documentation

### Story 13.2: Introduce Cubentia on a landing page

As a Learner or a hiring manager,
I want a short page that says what Cubentia is,
So that I know what I am installing or reviewing before I open the app.

**Refs:** PRD §1, §7, UJ-6 · NFR-7, NFR-8, NFR-10 · DESIGN.md color tokens · EXPERIENCE.md Voice and Tone

**Depends on** Story 13.1.

**Acceptance Criteria:**

**Given** the landing page
**When** it is read
**Then** it says in plain, calm sentences that Cubentia is an offline Android tutor that teaches a beginner to solve a 3×3 cube on a virtual or real cube, works without an account or internet connection, and links to the privacy policy and support pages; it never uses "Rubik" or "Rubik's", and it has no screenshot placeholders (screenshots are added from the Story 12.5 store assets)

**Given** any page on the site
**When** it is loaded
**Then** it sets no cookies, runs no script, and requests no resource from another origin (fonts self-hosted or a system stack), and the `check` job enforces this

**Given** the page in light and dark system themes, at 200% zoom and at 320 CSS px wide
**When** it is checked with the keyboard only and with an automated accessibility checker
**Then** text meets WCAG 2.2 AA contrast with the DESIGN.md tokens, nothing is cut off or scrolls sideways, focus is visible and in reading order, there is a skip link, `lang="en"`, landmarks and one `h1`, and the result is recorded in the PR

### Story 13.3: Export the app's privacy facts from the build

As the developer,
I want the build to state the app's privacy facts in one checked file,
So that the public privacy policy can be held to what the app really does.

**Refs:** NFR-5, NFR-8 · AD-13, AD-14 · AR-4 · `sprint-change-proposal-2026-11-13-cubentia-site.md` §3.3

**Acceptance Criteria:**

**Given** the release merged manifest and the release SDK-denylist result
**When** `:app:writePrivacyFacts` runs
**Then** it writes `docs/privacy-facts.json` with `schemaVersion`, `applicationId`, `internetPermission`, `backupAllowed`, `runtimePermissions` and `denylistedSdks` derived from the build, and the fixed NFR-8 facts `account`, `ads`, `analytics`, `crashReporting`, `dataLeavesDevice` and `storage`; for v1.0 the derived values are `false`, `false`, `[]` and `[]`

**Given** the committed `docs/privacy-facts.json`
**When** `./gradlew build` runs
**Then** `:app:checkPrivacyFacts` passes when the file matches the build, fails with a message naming `:app:writePrivacyFacts` and the site repository's privacy page when it does not, and CI's "Verify build wires the gates" step lists `:app:checkPrivacyFacts`

**Given** Gradle TestKit fixtures
**When** `./gradlew -p build-logic test` runs
**Then** a manifest that adds `INTERNET`, `allowBackup="true"` or a runtime permission produces facts that differ from the v1.0 file, and the check fails on them

### Story 13.4: Publish a privacy policy that matches the app

As a Learner,
I want a plain privacy policy I can read before I install,
So that I know Cubentia collects nothing and keeps my progress on my device.

**Refs:** NFR-5, NFR-8 · AD-13 · PRD §7 · `docs/play-first-publish.md` § 2

**Depends on** Stories 13.1 and 13.3.

**Human gate (before merge):** the PO confirms the publisher name ("ExxerPro") exactly as on the Play developer account, and the effective date.

**Acceptance Criteria:**

**Given** `/privacy/`
**When** it is read
**Then** it states, in plain language: Cubentia collects no personal data and shares none; it has no account, ads, analytics or crash reporting; it has no internet permission and does not back up data; it asks for no runtime permission; progress is stored only on the device and is removed when the app is uninstalled; it names the publisher ("ExxerPro") exactly as on the Play developer account, the contact email `abel.briones@exxerpro.com` and an effective date; it applies to Learners of any age; and it says the website sets no cookies and is hosted on GitHub Pages, which may log visitor IP addresses for security, with a link to GitHub's privacy statement

**Given** `docs/privacy-facts.json` on the app repository's `main` (`vars.APP_REPO`)
**When** the site's `check` job runs on a pull request, on push, daily and on manual dispatch
**Then** it fails if any fact lacks a `data-fact` statement with an equal `data-value` on the page, if the page states a fact the file lacks, if the publisher, contact email or effective date is missing, or if the policy text changed without a new effective date; and `deploy` runs only after `check` passes

**Given** the deployed page
**When** it is opened in a private window from a network outside exxerpro.com, with no sign-in
**Then** it loads as HTML (not a PDF) over HTTPS, and it meets the Story 13.2 accessibility and no-tracking criteria

### Story 13.5: Give Learners a support and contact page

As a Learner,
I want to know how to ask for help or report a problem,
So that I am not stuck when something goes wrong.

**Refs:** PRD §7 store readiness · NFR-8 · `docs/play-first-publish.md` § 3 (Store settings contact)

**Depends on** Story 13.1; lands before Story 12.5.

**Acceptance Criteria:**

**Given** `/support/`
**When** it is read
**Then** it gives the same contact email as the privacy policy (`abel.briones@exxerpro.com`), says what to include in a report (device, Android version, app version from Settings → Apps → Cubentia, what happened), says that no personal data is needed, links to the privacy policy, and points developers to the public source repository's issues without asking Learners to post personal details there

**Given** the landing page and the privacy page
**When** they are opened
**Then** each links to `/support/`, and the site's link check passes

### Story 13.6: Register the privacy policy in Play Console

As the developer,
I want the privacy-policy URL registered in Play Console and the runbook updated,
So that the closed-testing review can go ahead and nobody has to work out the URL again.

**Refs:** PRD §7 · NFR-8 · AD-15 · #127 · `docs/play-first-publish.md` § 2

**Depends on** Story 13.4.

**Human gate (before start):** the PO enters the URL in Play Console.

**Acceptance Criteria:**

**Given** the deployed privacy page
**When** the PO pastes `https://hebelmx.github.io/cubentia-site/privacy/` under **Policy and programs → App content → Privacy policy**
**Then** Play Console saves it, and the Data safety form can then be saved with "no data collected" (the answers in `docs/play-first-publish.md` § 2)

**Given** this repository
**When** the story's PR merges
**Then** `docs/play-first-publish.md` § 2 step 1 names the site repository and the registered URL instead of `docs/privacy.md`, the README links the website and the privacy policy, and #127 records the date the URL was registered (no tester data)
```

### 4.2 epics.md: Story 12.5 (Store listing and public portfolio repo)

**Section: Refs.** Append ` · Epic 13`.

**Section: before Acceptance Criteria.** Insert:

```
**Depends on** Stories 13.4 and 13.5 (the privacy-policy and support pages, `sprint-change-proposal-2026-11-13-cubentia-site.md`).
```

**AC 2.**
- OLD: "**Then** it has screenshots, a short promo video recorded from the app, a privacy-policy page, and a completed content-rating questionnaire"
- NEW: "**Then** it has screenshots, a short promo video recorded from the app, the privacy-policy URL registered in Story 13.6, the website from Epic 13 as the listing's website, and a completed content-rating questionnaire; the store screenshots are also added to the website's landing page"
- Rationale: the privacy page moves to Epic 13 so that it exists before the pilot review.

**AC 3.**
- OLD: "… and the GitHub repository and local working folder are renamed to Cubentia (deferred here from Story 12.6)"
- NEW: "… and the GitHub repository and local working folder are renamed to Cubentia (deferred here from Story 12.6); the site repository's `APP_REPO` variable and its source-repository links are updated to the new name, and the site's `check` job passes afterwards. The site repository itself is never renamed, so the registered privacy URL does not change."
- Rationale: the app rename stays in 12.5. It no longer affects the privacy URL, but the site reads facts from the app repo by name.

### 4.3 epics.md: other edits

| Location | Change |
|---|---|
| Epic List, after the *Epic 12* entry (`:237`) | Insert the Epic List entry below. |
| Epic 12 header, after the *Also carries* paragraph (`:235`) | Append: "**Epic 13** (public website and privacy policy, `sprint-change-proposal-2026-11-13-cubentia-site.md`) runs before Stories 12.3, 12.4 and 12.5. Story 12.3 depends on 13.6, and Story 12.5 on 13.4 and 13.5." |
| Story 12.3, before *Acceptance Criteria* (`:1837`) | Insert: "**Depends on** Story 13.6 (privacy-policy URL registered; the data-safety form needs it)." |
| Story 7.8 (done), after the last AC (`:1471`) | Annotation: "*(SCP 2026-11-13) AC2's closed-testing review needs a privacy-policy URL in Play Console. That is Story 13.6; #127 § 2 waits for it.*" |
| NFR coverage line (`:178`) | "NFR-5/8 Epic 1 (build checks), Epic 12" → "NFR-5/8 Epic 1 (build checks), Epic 12, Epic 13 (privacy facts and public policy)" |
| Frontmatter `updated:` | `2026-11-13` |

Epic List entry (exact text):

```markdown
### Epic 13: Cubentia on the web, with a privacy policy (pre-pilot, 2026-11-13)
Anyone can open Cubentia's public website, read a privacy policy that states exactly what the app does with data, and find support. Play Console has a privacy-policy URL that never changes. The site lives in its own repository, `hebelmx/cubentia-site`, published by GitHub Pages. Its privacy page is checked in CI against `docs/privacy-facts.json`, which this repository's build derives from the release manifest.
**Absorbs:** the privacy-policy page of Story 12.5 AC2 (PRD §7 store readiness).
**FRs covered:** none. **Also:** NFR-5, NFR-7, NFR-8, NFR-10, AD-13, AD-14, AR-4.
**Runs before** Stories 12.3, 12.4 and 12.5 and the #127 closed-testing review (`sprint-change-proposal-2026-11-13-cubentia-site.md`).
```

### 4.4 PRD: route to `bmad-prd` update (do not hand-edit)

**§7 Store readiness, the "a privacy-policy page" bullet (`:651`).**
- OLD: "a privacy-policy page (required even when no data is collected);"
- NEW: "a privacy-policy page (required even when no data is collected). It is published on the public Cubentia website, which lives in its own repository and sets no cookies and no tracking. Its statements are checked against the app's build (Epic 13, SCP 2026-11-13);"

**§10 Decisions, append:** "Public website and privacy policy in a separate repository, `hebelmx/cubentia-site`, on GitHub Pages; the app repository keeps the source (PO, 2026-11-13, SCP 2026-11-13)."

PRD memlog: a `(change)` entry, appended with `uv run _bmad/scripts/memlog.py append`.

### 4.5 Architecture spine: route to `bmad-architecture` update (do not hand-edit)

**AD-13 Rule (`:311-315`), append a bullet after the CI-check bullet:**

```
  - The public privacy policy (website repository, Epic 13) states the same facts. `docs/privacy-facts.json` is derived from the release merged manifest and the SDK denylist, `:app:checkPrivacyFacts` fails the build when it is stale, and the website's CI fails, and does not deploy, when the page disagrees with it.
```

**Deployment diagram (`:494-501`), add two lines:**

```
  dev -->|PR to site repo| site[GitHub Pages: website + privacy policy]
  site -. privacy URL .-> play
```

**Capability map, NFR-5/NFR-8 row (`:519`).**
- OLD: `| NFR-5, NFR-8 Offline and privacy | manifest, CI | AD-13 |`
- NEW: `| NFR-5, NFR-8 Offline and privacy | manifest, CI, `docs/privacy-facts.json`, website repo | AD-13 |`

Architecture memlog: a `(decision by user)` entry, appended with `memlog.py append`: "Website and privacy policy in a separate repo (PO 2026-11-13); privacy facts file derived from the release manifest and checked in both repos; site repo never renamed."

No AD is added, and no module or port changes.

### 4.6 Orchestrator gates: via the `bmad-customize` skill (do not hand-edit `_bmad/`)

Proposed entries for `_bmad/custom/bmad-orchestrate.toml`:

```toml
[[gates]]
key = "13-1-create-the-site-repository-and-publish-it-with-github-pages"
when = "before-start"
reason = "PO creates public repo hebelmx/cubentia-site, sets Pages source to GitHub Actions, protects main, sets APP_REPO; work lands in the site repo"

[[gates]]
key = "13-2-introduce-cubentia-on-a-landing-page"
when = "before-start"
reason = "work lands in the site repo (hebelmx/cubentia-site), not this repo"

[[gates]]
key = "13-4-publish-a-privacy-policy-that-matches-the-app"
when = "before-merge"
reason = "PO confirms publisher name as on the Play developer account and effective date; work lands in the site repo"

[[gates]]
key = "13-5-give-learners-a-support-and-contact-page"
when = "before-start"
reason = "work lands in the site repo (hebelmx/cubentia-site), not this repo"

[[gates]]
key = "13-6-register-the-privacy-policy-in-play-console"
when = "before-start"
reason = "PO enters the privacy URL in Play Console App content and saves the Data safety form"
```

13.3 needs no gate. It is ordinary work in this repo.

### 4.7 sprint-status impact (applied later with `sprint_plan.py`, after the epics.md edits)

- Add `epic-13: backlog` and these keys, all `backlog`:
  - `13-1-create-the-site-repository-and-publish-it-with-github-pages`
  - `13-2-introduce-cubentia-on-a-landing-page`
  - `13-3-export-the-app-s-privacy-facts-from-the-build`
  - `13-4-publish-a-privacy-policy-that-matches-the-app`
  - `13-5-give-learners-a-support-and-contact-page`
  - `13-6-register-the-privacy-policy-in-play-console`
  - `epic-13-retrospective: optional`
- `epic-12` stays `in-progress`. `12-3` and `12-5` stay `backlog` with their new dependencies, and `7-8` stays `done`.
- Action item `epic-7-retro-item-66` (#127) stays open, with a note that § 2 waits for `13-6`.

## 5. Implementation Handoff

**Scope: Moderate.** A new epic, a backlog reorder ahead of Epic 12's tail, a second repository, and gate changes. Routed to the PO for approval, then to SM and Dev.

### PO decisions (approved 2026-11-13)

**Decided:**
- **D1:** option A. A separate project repo, `hebelmx/cubentia-site`, on GitHub Pages. The permanent privacy URL is `https://hebelmx.github.io/cubentia-site/privacy/`. A custom domain may be added later (option B).
- **D2:** the publisher is "ExxerPro", exactly as on the Play developer account. The contact email for the privacy policy, the support page and the Play listing is `abel.briones@exxerpro.com`, given by the PO on 2026-11-13. It replaces the recommended role address. At the Story 13.4 before-merge gate, the PO still confirms the publisher name and the effective date.
- **D3:** the owner is `hebelmx`.
- **D4:** Stories 13.1, 13.2, 13.4 and 13.5 are built by hand with `bmad-build` in a checkout of the site repo. This repo tracks their status and gates them. Stories 13.3 and 13.6 go through the normal orchestrator.
- **D5:** a daily scheduled check plus a check before every deploy. There is no cross-repo token.

The options as presented:


| # | Question | Options (recommended first) | Recommendation |
|---|---|---|---|
| D1 | Where is the site hosted, and what is the permanent privacy URL? | **A** `hebelmx/cubentia-site` → `https://hebelmx.github.io/cubentia-site/privacy/`; B: A plus a custom domain later; C: custom domain (`cubentia.exxerpro.com`) now; D: same repo with the rename pulled forward; E: user site `hebelmx.github.io` | **A.** It is the PO's two-repo preference, needs no DNS on the critical path, and is unaffected by 12.5. B stays open, because GitHub redirects `github.io` to a custom domain added later. The repo must not be called `cubentia`, which would collide with the renamed app repo. |
| D2 | What publisher name and contact email go on the policy? | **The publisher name copied exactly from the Play developer account (ExxerPro organization), with a role address at exxerpro.com** (for example a `cubentia@` or `support@` alias); or a personal mailbox | **Role address.** It is shown publicly on the site and the listing (`docs/play-first-publish.md` § 3), and it survives staffing changes. Play rejects a name mismatch, so copy the name from Play Console rather than typing it. |
| D3 | Who owns the site repository? | **`hebelmx` (personal account)**; or an ExxerPro GitHub organization, if one exists | **`hebelmx`.** It matches the app repo, and a transfer later would change the Pages URL unless a custom domain (D1-B) is in place first. |
| D4 | How do stories that land in the site repository run? | **13.1, 13.2, 13.4 and 13.5 run with `bmad-build` in a checkout of the site repo; this repo keeps their status in sprint-status, gated so the orchestrator stops at them**; or extend the orchestrator to drive a second repo | **Manual `bmad-build` in the site repo.** Four small stories don't justify orchestrator changes, and the gates in §4.6 stop it cleanly. |
| D5 | How fast must the site notice an app-side facts change? | **A daily scheduled check plus a pre-deploy check** (no secrets); or a `repository_dispatch` from the app repo (needs a cross-repo token secret) | **Daily.** Facts change only with a planned `ManifestPolicy` change (v1.1 `CAMERA`). A day's lag is acceptable, and no new secret is added. |

### Routing after approval

| Item | Route | What |
|---|---|---|
| epics.md | PM/SM, this SCP's PR | §§4.1–4.3 applied 2026-11-13. |
| PRD | `bmad-prd` update | §4.4 plus the memlog entry, applied 2026-11-13. |
| Spine | `bmad-architecture` update | §4.5 plus the memlog entry, applied 2026-11-13. |
| Gates | Orchestrator, through `bmad-customize` | §4.6. Not applied here: the orchestrator adds the gates itself. |
| sprint-status | SM, `sprint_plan.py` | §4.7. The Epic 13 keys were generated on 2026-11-13. |
| Stories | Dev / orchestrator | 13.1 (PO first) → 13.3 (parallel) → 13.4 (PO confirms) → 13.6 (PO) on the critical path; 13.2 and 13.5 after 13.1. |
| AGENTS.md | `bmad-project-context` refresh, after 13.3 merges | Add the website and privacy-facts pointer. |
| #127 | PO | Continue `docs/play-first-publish.md` § 2 after 13.6. |

### Success criteria

- `https://hebelmx.github.io/cubentia-site/privacy/` (or the D1 URL) loads publicly, as HTML, with no sign-in. It names the publisher, a contact email and an effective date, and it is registered in Play Console. The Data safety form is saved.
- `:app:checkPrivacyFacts` is part of `build`, and CI is green. The site's `check` job gates every deploy and runs daily.
- The site sets no cookies, runs no script and loads no third-party resource. It meets WCAG 2.2 AA.
- After Story 12.5 renames the app repo, the privacy URL still loads and the site's `check` passes.
- No planning text still assigns the privacy page to Story 12.5 alone.
