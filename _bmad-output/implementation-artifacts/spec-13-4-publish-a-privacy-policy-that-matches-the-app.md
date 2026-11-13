---
title: 'Publish a privacy policy that matches the app'
type: 'feature'
created: '2026-11-13'
status: 'done'
implementation_repo: '/mnt/e/Dynamic/IndFun/cubentia-site'
implementation_branch: 'feat/13-4-privacy-policy'
baseline_revision: 'b1d44043427897ee492acb8ff9de0cf7b6b8e234'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/AGENTS.md'
  - '{project-root}/_bmad-output/implementation-artifacts/epic-13-context.md'
warnings: ['oversized']
deferred:
  - summary: >-
      The landing page, 404 page, README and LICENSE still name the publisher "ExxerPro" instead of the PO's updated legal name.
    evidence: |-
      site/index.html and site/404.html footers read "© 2026 ExxerPro"; README and LICENSE use ExxerPro. Only /privacy/ must name the publisher exactly (AC1). Aligning the other pages belongs to 13.2/13.5; the LICENSE copyright holder is a PO decision.
    location: >-
      cubentia-site/site/index.html, site/404.html, README.md, LICENSE
    severity: low
  - summary: >-
      The pages.yml trigger, upload, deploy and concurrency conditions have no automated test; they run only on GitHub.
    evidence: |-
      Verification-gap layer (pre-verified): no test reads the workflow file, so reverting the deploy condition to != pull_request (a daily redeploy) would pass every test. The repo has no workflow test harness. Settle it with the first PR run (check only), the first scheduled run (check only) and the first main push (check, then deploy).
    location: >-
      cubentia-site/.github/workflows/pages.yml
    severity: medium
---

<intent-contract>

## Intent

**Problem:** Play cannot review the pilot until Cubentia has a public privacy-policy URL. The site (`hebelmx/cubentia-site`, Story 13.1) has no privacy page yet, and nothing holds a future page to the facts the app's build exports in `docs/privacy-facts.json` (Story 13.3).

**Approach:** In the site repo (`/mnt/e/Dynamic/IndFun/cubentia-site`, branch `feat/13-4-privacy-policy`), add `site/privacy/index.html` in plain language, with machine-readable `data-fact`/`data-value` statements and `data-policy` markers for publisher, contact and effective date. Add a stdlib checker `scripts/check_privacy.py` that compares the page with the facts file and with a committed policy record, wire it into `scripts/check.sh`, and make `pages.yml` read the facts file from `vars.APP_REPO` and run daily.

## Boundaries & Constraints

**Always:**
- PO values (updated clarification, verbatim): the page reads "Exxerpro Solutions, S.A. de C.V. (published on Google Play as Abel Briones)". The legal organization name is `Exxerpro Solutions, S.A. de C.V.` and the public Play developer name is `Abel Briones`, and both appear. Contact `exxerpro@exxerpro.com` is the only email on the page. Effective date `2026-11-13`. These replace the story's placeholder "ExxerPro" and the earlier `abel.briones@exxerpro.com`. No postal address or phone number on the site.
- The page says, in plain language: Cubentia collects no personal data and shares none. It has no account, ads, analytics or crash reporting, no internet permission, no backup and no runtime permission. Progress is stored only on the device and is removed on uninstall. The policy applies to Learners of any age. The website sets no cookies and is hosted on GitHub Pages, which may log visitor IP addresses for security, with a link to `https://docs.github.com/en/site-policy/privacy-policies/github-general-privacy-statement`.
- Facts encoding: every key in the facts file except `schemaVersion` is a fact. A statement is an element with `data-fact="<key>"`, `data-value="<value>"` and non-empty text. `<value>` is the raw string for JSON strings, or compact JSON (`json.dumps(v, separators=(",", ":"))`) otherwise, e.g. `false`, `[]`. `schemaVersion` must be `1`. Any other value fails with "unsupported schemaVersion".
- Policy record `privacy-policy.json` (repo root, not published) holds `publisher`, `playDeveloper`, `contactEmail`, `effectiveDate` and `textSha256`. The hash covers the whitespace-normalized text of `<main>` plus the `(data-fact, data-value)` pairs in document order. `check_privacy.py --update` rewrites `effectiveDate` and `textSha256` from the page, and refuses unless the page's date is later than the recorded one. When the record is missing, `--update` creates it with the publisher, Play developer and contact read from the page.
- Markers: `data-policy="publisher"` (its text equals the record's `publisher`); `data-policy="play-developer"` (its text equals the record's `playDeveloper`); the page text contains `<publisher> (published on Google Play as <playDeveloper>)`; `data-policy="contact"` on an `<a>` whose text is the email and whose `href` is `mailto:<email>`; `data-policy="effective-date"` on a `<time datetime="YYYY-MM-DD">`.
- CI reads the facts with a sparse `actions/checkout` (same pinned SHA as now) of `repository: ${{ vars.APP_REPO }}`, `ref: main`, file `docs/privacy-facts.json`, `persist-credentials: false`, and no token beyond the default. The step before it fails when `vars.APP_REPO` is empty. `check.sh` reads `PRIVACY_FACTS`. On CI (`GITHUB_ACTIONS=true`) it must be set and must exist. Locally it falls back to `scripts/fixtures/privacy-facts.json`, a copy of the app's v1.0 file, and prints that it did. Local runs and tests never need the network.
- `pages.yml` adds a daily `schedule`. `check` runs on PR, on push, daily and on dispatch. The artifact upload and `deploy` run only for `push` or `workflow_dispatch` on `refs/heads/main`, after `check` (`needs: check`). Permissions stay unchanged.
- The page passes the existing `check_site.py` (deny-by-default) and vnu. It has `lang="en"`, a skip link, header/main/footer landmarks, one `h1` and `h2`s in order, and only existing design tokens. Its CSS additions are only for headings and lists.
- LF endings, Conventional Commits, each commit ends with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

**Never:**
- No JavaScript, cookies, or resources from other origins, and no "Rubik" in page text.
- No support page or link to one (13.5), and no landing-page copy or nav changes (13.2). No external link check job.
- No personal data beyond the published contact address. Do not edit RubikXplorer app code or planning artifacts. Do not push or open PRs.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Matching page | v1.0 facts, shipped page and record | `check_privacy.py` prints OK, exit 0 | — |
| Fact missing | facts gain a key, or the page drops a statement | fails naming the key | exit 1 |
| Value differs | file `internetPermission: true`, page `false` | fails naming the key, the expected value and the page value | exit 1 |
| Extra fact | page `data-fact="location"` absent from the file | fails: page states a fact the file lacks | exit 1 |
| Conflicting statements | two statements, one key, different values | fails | exit 1 |
| Empty statement | `data-fact` element with no text | fails | exit 1 |
| Marker missing or wrong | no publisher/Play developer/contact/effective date, a name not the recorded one, bad mailto, bad date | fails naming the marker | exit 1 |
| Second email | any other email address or `mailto:` in the page text or attributes | fails: the contact must be the only email | exit 1 |
| Text changed, same date | `<main>` text edited, date unchanged | fails: "policy text changed without a new effective date" | exit 1 |
| New date, not recorded | date later than the record | fails: run `--update` | exit 1 |
| Date earlier than the record | — | fails | exit 1 |
| Bad facts file | missing, invalid JSON, `schemaVersion` ≠ 1 | fails with the reason | exit 1 |
| `--update` | later date / same date | rewrites the record / refuses | exit 0 / exit 1 |
| CI without facts | `GITHUB_ACTIONS=true`, `PRIVACY_FACTS` unset or missing | `check.sh` fails | exit non-zero |

</intent-contract>

## Code Map

- `cubentia-site/scripts/check_site.py` -- deny-by-default site checker (allowlists L51-113). `data-*` and `aria-*` are already allowed. `<time datetime>`, `<code>`, `<address>`, `mailto:` links and external `<a href>` pass, and `<main>`/`h1` have no checker rules. Reuse its `PageParser` only if convenient. The privacy checker needs per-element text, so a small separate `HTMLParser` with an open-element stack (skipping void elements) is expected. Do not weaken any rule.
- `cubentia-site/scripts/test_check_site.py` -- unittest style to mirror (temp dirs, `build()` helper). Discovered by `check.sh` via `-p 'test_*.py'`, so a new `test_check_privacy.py` runs automatically.
- `cubentia-site/scripts/check.sh` -- runs the self-tests, then `check_site.py site`, then `vnu-jar@26.10.12`. Add the privacy step after the site checks.
- `cubentia-site/.github/workflows/pages.yml` -- the 13.1 workflow. Checkout is pinned at `actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1`, which supports `repository`, `ref`, `path`, `sparse-checkout` and `sparse-checkout-cone-mode`. The current deploy/upload condition is `github.event_name != 'pull_request' && github.ref == 'refs/heads/main'`. It must change, or a daily schedule would redeploy.
- `cubentia-site/site/styles.css` -- tokens and base layout. No `h2` or list styling yet.
- `cubentia-site/site/index.html`, `site/404.html` -- page skeleton to mirror (skip link, `.site-header` wordmark, `main#main`, `.site-footer`). Their footer still says "© 2026 ExxerPro". Leave them alone (see Design Notes).
- `cubentia-site/README.md` -- layout, check and deploy sections to extend. It says "The privacy policy will live at …".
- `RubikXplorer/docs/privacy-facts.json` -- v1.0 facts: `schemaVersion` 1, `applicationId` `com.exxerpro.cubentia`, `internetPermission` false, `backupAllowed` false, `runtimePermissions` [], `denylistedSdks` [], `account`/`ads`/`analytics`/`crashReporting`/`dataLeavesDevice` false, `storage` `app-private`. It is on `hebelmx/RubikXplorer` main. Copy it verbatim into the fixture.
- `RubikXplorer/build-logic/.../SdkDenylistPolicy.kt` -- the denylist covers ads, analytics, crash-reporting and attribution SDKs. Wording for `denylistedSdks`: no advertising, analytics or crash-reporting libraries.
- App string `settings_reset_progress` = "Reset progress". The page may mention it as an in-app way to erase progress.

## Tasks & Acceptance

**Execution:**
- `cubentia-site/scripts/fixtures/privacy-facts.json` -- verbatim copy of the app's v1.0 file -- local default and test baseline.
- `cubentia-site/scripts/check_privacy.py` -- the stdlib checker, with CLI `--facts`, `--page` (default `site/privacy/index.html`), `--record` (default `privacy-policy.json`) and `--update`. It prints `file: message` lines, or OK -- AC2.
- `cubentia-site/scripts/test_check_privacy.py` -- one unittest per matrix row, plus a test that the shipped page, the record and the fixture pass -- proves the checker.
- `cubentia-site/site/privacy/index.html` -- the policy page with the statements and markers above, and a link home -- AC1.
- `cubentia-site/site/styles.css` -- `h2` and list spacing, and a muted style for the effective-date line -- readability.
- `cubentia-site/privacy-policy.json` -- generated with `--update` -- the policy record.
- `cubentia-site/scripts/check.sh` -- resolve `PRIVACY_FACTS` (CI must set it; locally use the fixture) and run `check_privacy.py` -- one shared command.
- `cubentia-site/.github/workflows/pages.yml` -- add the daily schedule, the `APP_REPO` guard step, the sparse facts checkout into `app-facts/`, `PRIVACY_FACTS` env for the check step, and the push/dispatch-only upload and deploy conditions -- AC2.
- `cubentia-site/README.md` -- the privacy URL is live, the facts check and how to change the policy (edit, bump the date, `--update`), `PRIVACY_FACTS`, the schedule, and the layout entries.

**Acceptance Criteria:**
- Given `/privacy/`, when it is read, then it states every fact listed in Always, reads "Exxerpro Solutions, S.A. de C.V. (published on Google Play as Abel Briones)", gives `exxerpro@exxerpro.com` as its only email, has no postal address or phone number, and shows effective date 13 November 2026, and links GitHub's privacy statement.
- Given `bash scripts/check.sh` on the shipped tree with no network for the facts, when it runs locally, then every self-test passes, `check_site.py`, `check_privacy.py` (fixture) and vnu all pass, and it exits 0.
- Given `pages.yml`, when reviewed, then `check` runs on `pull_request`, `push`, `schedule` and `workflow_dispatch` and reads `docs/privacy-facts.json` from `vars.APP_REPO` at `main`, and `deploy` runs only after `check` and only for push or dispatch on main.
- Given the merged PR (post-merge, orchestrator/PO), when `https://hebelmx.github.io/cubentia-site/privacy/` is opened in a private window with no sign-in, then it loads as HTML over HTTPS. The keyboard, 200%/320 px and light/dark checks are recorded in the PR.

## Spec Change Log

## Review Triage Log

### 2026-11-13 — Review pass
- verdicts: 34 findings — high 0, medium 6, low 22, false 6, maybe-false 0
- findings:
  - Blind Hunter
    - `[low]` `[reject]` the landing page does not link to /privacy/. Real, but Story 13.2's AC owns the landing-page links, and the intent is "exactly this story's acceptance criteria". Play links the URL directly.
    - `[false]` `[reject]` "not copied … to another device" may be false on Android 12+. The app manifest sets `android:dataExtractionRules="@xml/data_extraction_rules"`, which excludes every domain under both `<cloud-backup>` and `<device-transfer>`, so the statement holds.
    - `[low]` `[reject]` untagged claims (camera/microphone/files, uninstall removal, Reset progress) have no fact behind them. They are true today (no runtime permission, app-private storage, `SettingsScreen.kt` shows Reset progress). Tying each to a fact would need new app-side facts, which is outside this story.
    - `[low]` `[patch]` the hash misses the `<title>`, the meta description (which makes a policy claim) and link targets. Fixed: `text_sha256` now also covers the title, the meta description and every `<a href>`. The record was regenerated, and three tests were added.
    - `[low]` `[reject]` a future or typo effective date is accepted. It is unlikely, review sees the date in the PR, and the fix adds a clock-dependent guard.
    - `[false]` `[reject]` `ref: main` is not pinned. AC2 names "`docs/privacy-facts.json` on the app repository's `main`" explicitly.
    - `[medium]` `[patch]` GitHub disables scheduled workflows after 60 days of inactivity, so the daily drift check can stop without notice. Fixed: the README documents the shutdown, how to re-enable the workflow, and that PRs and pushes run the same facts check.
    - `[low]` `[reject]` the local fixture is not compared with the app's file. CI always uses the live file and is the gate. The fixture is only a local default.
    - `[low]` `[patch]` `test_value_change_counts_as_text_change` only zeroed the hash. Fixed: it now changes only a `data-value`, with a matching facts file, and expects "policy text changed".
    - `[low]` `[reject]` the string "false" and the boolean false encode the same way, and nested key order matters. The facts file is generated deterministically by build-logic (sorted, fixed order), so this is contrived.
    - `[low]` `[reject]` `--update` creating a record trusts the contact marker. The next check fails loudly on any bad marker, and creation happens once.
    - `[false]` `[reject]` content gaps:
      - "Learners" is the project's Glossary term, capitalized by design (epic copy rules).
      - The PO forbade a postal address on the site.
      - The site is English only by epic rule.
  - Intent Alignment
    - `[low]` `[defer]` "ExxerPro" remains on the landing page, the 404 page, the README and LICENSE (reading R1b). Deferred: aligning those pages is 13.2/13.5 work, and the LICENSE holder is the PO's decision.
    - `[low]` `[reject]` untagged privacy claims are not checked (reading R2b). Same claim as the Blind Hunter untagged-claims row, same reason.
    - `[low]` `[reject]` the record's hash can be pasted by hand without a new date (reading R3b). A reviewer sees every record change in the PR. A git-history baseline behaves differently on PR, push and schedule (Design Notes).
    - `[low]` `[reject]` nothing checks for a postal address or phone number. The page has neither, and a pattern check for addresses would be guesswork.
    - `[medium]` `[defer]` the workflow checkout, schedule and deploy condition have no tests. Grouped with the Verification Gap row below (same root cause).
    - `[false]` `[reject]` AC3 (the live HTTPS page from outside exxerpro.com) is not covered. The spec assigns it to the post-merge check.
    - `[false]` `[reject]` the 13.2 accessibility criteria are not tested. AC3 and 13.2 require the keyboard, zoom, width and theme results to be recorded in the PR after deploy. Structure (lang, skip link, landmarks, one h1, ordered h2s) is in place and passes vnu.
  - Edge Case Hunter
    - `[low]` `[patch]` the `datetime` can change while the visible date stays old. Fixed: the effective-date text must equal `D Month YYYY` of its `datetime`, with a test. Existing date tests were updated to change both, and the README step says so.
    - `[low]` `[reject]` a future date is accepted. Same as the Blind Hunter row.
    - `[low]` `[reject]` `--update` writes a record from a bad contact marker. Same as the Blind Hunter row.
    - `[low]` `[reject]` the record's names with stray spaces never match. The record is generated from normalized page text, so a hand edit would be needed to break it, and the failure is loud.
    - `[low]` `[patch]` non-ASCII values in list facts were `\u`-escaped. Fixed: `ensure_ascii=False` in `fact_value`, with a test.
    - `[low]` `[reject]` duplicate keys in the facts file pass with the last value winning. The file is machine-written by `:app:writePrivacyFacts` and checked by `:app:checkPrivacyFacts`.
    - `[low]` `[patch]` wording outside `<main>` is not hashed. Grouped with the Blind Hunter hash row (same fix).
    - `[low]` `[reject]` `relpath` raises across Windows drives. The checker runs on Linux CI and WSL, and the fix adds a guard.
    - `[low]` `[reject]` `--facts` with `--update` is silently ignored. This is harmless: `check.sh` always runs the facts check.
    - `[low]` `[patch]` a relative `PRIVACY_FACTS` resolved against the repo root, not the caller's directory. Fixed: `check.sh` makes it absolute before `cd`, with a test that runs from another directory.
    - `[medium]` `[patch]` the schedule is disabled after 60 days. Grouped with the Blind Hunter 60-day row.
    - `[false]` `[reject]` app `main` may hold facts not yet shipped. AC2 makes `main` the source, and a stricter policy than the shipped build is still true.
  - Verification Gap
    - `[medium]` `[defer]` (pre-verified) the deploy, upload and schedule conditions in `pages.yml` are not verified by anything that runs. Deferred: the repo has no workflow test harness. Settle it with the first PR run, scheduled run and main push.
    - `[medium]` `[patch]` a scheduled run in the same concurrency group can replace a pending push run, so that push never deploys. Fixed: the group key adds `schedule` or `run` by event, so check-only scheduled runs never share a group with deploy runs.
    - `[medium]` `[patch]` the 60-day schedule shutdown. Grouped with the Blind Hunter 60-day row.

### 2026-11-13 — Review pass (follow-up)
- verdicts: 37 findings — high 0, medium 1, low 27, false 8, maybe-false 1
- findings:
  - Blind Hunter
    - `[false]` `[reject]` carried: the facts come from app `main`, not the released build. AC2 names `main` explicitly.
    - `[low]` `[reject]` only `data-value` is compared, not the sentence. "There are ads." on an `ads=false` element would pass. By design, the contract requires only non-empty text. Matching the wording would need a phrase list per fact, and the reviewer reads the text in the PR.
    - `[low]` `[reject]` carried: untagged claims (Reset progress, uninstall, camera and others) have no fact behind them. They are true today, and new app facts are out of scope.
    - `[low]` `[reject]` the page does not say what happens to an email sent to the contact, or what Google Play collects. A sentence promising how email is handled is a new legal commitment for the PO, the intent fixes the page content, and Play's own collection is covered by Google's policy.
    - `[false]` `[reject]` a Mexican S.A. de C.V. needs an address, ARCO rights and Spanish. The PO decided there is no postal address on the site, and the site is English only by epic rule. Both are scope decisions.
    - `[low]` `[patch]` there is no documented way to change the publisher, Play developer or contact, because `--update` rewrites only the date and hash. Fixed: the README says to edit the marker and the matching record field by hand, then follow the change steps.
    - `[low]` `[patch]` the module docstring describes the old hash scope. Fixed: it now lists the title, meta description and link targets.
    - `[low]` `[reject]` the hash covers every href but only the text inside `<main>`, so footer wording is unhashed. The footer and header hold only "Cubentia home" and the wordmark, with no policy wording. Changing the scope means regenerating the record for no user benefit.
    - `[low]` `[reject]` carried: nothing keeps the local fixture in sync. CI uses the live file.
    - `[low]` `[reject]` no test covers the local fixture fallback in `check.sh`. When it breaks, every local run fails loudly. Testing it needs a dry-run mode in `check.sh`.
    - `[low]` `[reject]` a failed daily run is easy to miss. The README documents this (carried 60-day row). Opening an issue would need `issues: write`, and the intent keeps permissions unchanged.
    - `[low]` `[reject]` carried: no landing-page link to /privacy/. That is 13.2's AC.
    - `[false]` `[reject]` carried: "Learners" is the Glossary term. The Play target-audience declaration is a Play Console form, not site content.
    - `[false]` `[reject]` the global `ul` and `li + li` rules change other pages. `site/index.html` and `site/404.html` have no lists, so nothing changes.
    - `[low]` `[reject]` the email regex would flag a future `logo@2x.png`, and `--update` does not validate the contact (carried). No such asset exists, the failure is loud, and the fix adds URL-context guards.
  - Verification Gap
    - `[low]` `[reject]` `PolicyParser` ignores implied end tags, so an omitted `</li>` on a statement swallows the following text and hides the "no text" check. Every element on the shipped page closes explicitly, and implementing HTML's implied-end-tag rules adds parser branches.
  - Intent Alignment
    - `[low]` `[patch]` the `.effective-date` CSS rule breaks "CSS additions are only for headings and lists". Fixed: the rule and its class are removed. The hash does not cover classes, so the record is unchanged.
    - `[false]` `[reject]` the concurrency group change is not in the intent. Nothing forbids it, permissions are unchanged, and it prevents a scheduled run from replacing a pending deploy run (earlier patch).
    - `[low]` `[reject]` carried: the Reset progress sentence has no fact behind it. It is true today (`SettingsScreen`).
    - `[false]` `[reject]` the hash covers more than `<main>` plus the facts. Strictly more changes need a new date, and every matrix row still behaves as stated, so the result is no worse.
    - `[false]` `[reject]` the date-text match, the raw-source email scan and the missing `data-value` failure are stricter than the contract. Every matrix row still holds, and nothing that should pass fails.
    - `[medium]` `[defer]` carried: the `pages.yml` conditions and the cross-repo checkout are untested. Already in `deferred`; settle it on the first runs.
    - `[false]` `[reject]` carried: the deployed URL is not covered. AC4 is a post-merge check.
    - `[low]` `[reject]` nothing checks the "existing tokens only" rule. The stylesheet diff uses only `var(--ink-muted)`-style existing tokens (now none), and a token linter is new tooling.
  - Edge Case Hunter
    - `[low]` `[patch]` the self-tests hard-code `2026-11-13` and some page sentences, so following the README's change steps breaks `check.sh`. Fixed together with the two rows below: the README change steps now say to update `DATE_MARKER` and the pinned literals, and to commit the tests with the page and record.
    - `[low]` `[patch]` when the app's facts change, the self-tests still run against the old fixture, and `test_fixture_is_the_app_v1_file` pins it. Fixed: the README's drift paragraph says to refresh the fixture and that test in the same change.
    - `[low]` `[patch]` following the README procedure as written cannot reach a green `check.sh`. Same root cause and fix as the two rows above.
    - `[low]` `[patch]` the "add data-fact … data-value" hint is not HTML-escaped, so a list value with quotes gives broken HTML. Fixed: `html.escape(expected)`, checked by hand (`["x"]` → `[&quot;x&quot;]`).
    - `[low]` `[reject]` carried: objects or floats spelled differently. The build-logic writes the file deterministically.
    - `[low]` `[reject]` a statement hidden with `hidden`, `aria-hidden` or `<template>` still counts. Contrived, the reviewer sees it in the PR, and the fix adds ancestor checks.
    - `[low]` `[reject]` the email regex flags `icon@2x.png`. Same as the Blind Hunter row: no such asset, and the failure is loud.
    - `[low]` `[reject]` carried: a future effective date is accepted.
    - `[low]` `[reject]` carried: `--update` trusts the contact marker when it creates the record.
    - `[low]` `[reject]` carried: a record with stray whitespace never matches.
    - `[low]` `[reject]` carried: `--facts` with `--update` is silently ignored.
    - `[low]` `[reject]` an interrupted `--update` write truncates the record. The record is tracked in git, the next check fails loudly as invalid JSON, and the fix adds temp-file handling.
    - `[maybe-false]` `[reject]` `vars.APP_REPO` may be unavailable on fork PRs, which would then fail the guard. To settle it, check GitHub's rule for configuration variables on fork-triggered `pull_request` runs. Even if true it is low: this is a single-maintainer repo, and the guard fails loudly rather than passing wrongly.

## Design Notes

- **Why a record file and not git history:** "text changed without a new effective date" needs a baseline. Diffing against `origin/main` behaves differently on PR, push and schedule, and needs full history. A committed hash paired with its date is deterministic everywhere. A reviewer sees any change to the record in the PR.
- **Publisher name elsewhere:** `index.html`, `404.html`, `README.md` and `LICENSE` say "ExxerPro" (13.1). Only the privacy page must name the publisher exactly. Aligning the other pages is left to 13.2/13.5, and the LICENSE holder to the PO. Record this as a deferred item.
- Statement example:
  ```html
  <li data-fact="internetPermission" data-value="false">The app has no internet permission, so it cannot send anything over the network.</li>
  <p>Questions: <a data-policy="contact" href="mailto:exxerpro@exxerpro.com">exxerpro@exxerpro.com</a></p>
  ```

## Verification

**Commands (in `/mnt/e/Dynamic/IndFun/cubentia-site`):**
- `bash scripts/check.sh` -- expected: tests OK, site checker OK, privacy check OK (fixture noted), vnu no errors, exit 0.
- `GITHUB_ACTIONS=true bash scripts/check.sh` -- expected: fails because `PRIVACY_FACTS` is unset.
- `PRIVACY_FACTS=/mnt/e/Dynamic/IndFun/RubikXplorer/docs/privacy-facts.json python3 scripts/check_privacy.py --facts "$PRIVACY_FACTS"` -- expected: OK against the real app file.
- `python3 -c "import yaml; yaml.safe_load(open('.github/workflows/pages.yml'))"` -- expected: parses (actionlint if available).
- `git ls-files --eol` -- expected: all `i/lf`.

**Manual checks:**
- Post-merge: the page loads over HTTPS in a private window, the Actions run shows `check` then `deploy`, and a scheduled run shows `check` only.

## Auto Run Result

Status: done

**Summary:** Story 13.4 is implemented in `/mnt/e/Dynamic/IndFun/cubentia-site` on `feat/13-4-privacy-policy`, in 4 commits on top of `b1d44043427897ee492acb8ff9de0cf7b6b8e234`. Nothing is pushed. The site now has `/privacy/`, a plain-language policy. Each of the 11 app facts is on a `data-fact`/`data-value` statement. The publisher line reads "Exxerpro Solutions, S.A. de C.V. (published on Google Play as Abel Briones)". `exxerpro@exxerpro.com` is the only email, there is no address or phone number, and the effective date is 13 November 2026. The page also says the site is hosted on GitHub Pages and links GitHub's privacy statement. The stdlib checker `scripts/check_privacy.py` holds the page to the app's `docs/privacy-facts.json` and to the committed record `privacy-policy.json`. CI reads the facts from `vars.APP_REPO` at `main` and runs on PR, on push, daily and on dispatch, and it deploys only for a push or dispatch on main.

The PO's later clarification replaced the first publisher and email values (`Exxerpro Solutions`, `abel.briones@exxerpro.com`). The spec, page, checker, tests and record use only the updated values.

**Files changed (cubentia-site):**
- `site/privacy/index.html`: the policy page.
- `site/styles.css`: `h2`, list and effective-date styles, using existing tokens only.
- `scripts/check_privacy.py`: the facts, markers, single-email and effective-date/record check, plus `--update`.
- `scripts/test_check_privacy.py`: 58 tests, covering every matrix row plus the review patches.
- `scripts/fixtures/privacy-facts.json`: a copy of the app's v1.0 facts, used as the local default.
- `privacy-policy.json`: the policy record (publisher, Play developer, contact, date, text hash).
- `scripts/check.sh`: resolves `PRIVACY_FACTS` (required on CI, the fixture locally, relative to the caller) and runs the privacy check.
- `.github/workflows/pages.yml`: the daily schedule, an `APP_REPO` guard, the sparse facts checkout, push/dispatch-only upload and deploy, and a separate concurrency group for scheduled runs.
- `README.md`: the live privacy URL, the facts check, how to change the policy, `PRIVACY_FACTS`, and the schedule caveat.
- `.gitignore`: ignores `app-facts/`.

**Review findings:** 34 in total: high 0, medium 6, low 22, false 6.
- Patched (7 entries):
  - hashing the title, meta description and link targets (low);
  - the 60-day schedule note (medium);
  - the misnamed test (low);
  - the date text matching its datetime (low);
  - `ensure_ascii` (low);
  - the relative `PRIVACY_FACTS` (low);
  - the separate concurrency group for scheduled runs (medium).
- Deferred (2):
  - "ExxerPro" on the other pages, README and LICENSE;
  - the workflow conditions have no tests.
- Rejected: every row and its reason is in the Review Triage Log. In short:
  - false: the backup statement (data_extraction_rules exclude device transfer), `main` is mandated, the content gaps follow the epic and PO rules, AC3 and accessibility are post-merge checks, unshipped facts on `main`;
  - low and unlikely: untagged claims, future dates, fixture sync, type encoding, record creation, whitespace, duplicate keys, Windows paths, `--facts` with `--update`, an address pattern check, hand-pasted hashes, the landing-page link (13.2's AC).

**Follow-up review recommended: true.** Two medium entries were patched on a first pass (patched entries by verdict: high 0, medium 2, low 5). The unverified risk: the new concurrency expression (`pages-${{ github.ref }}-${{ github.event_name == 'schedule' && 'schedule' || 'run' }}`) and the sparse checkout of one file with `sparse-checkout-cone-mode: false` have only been read, not run on GitHub.

**Verification:**
- `bash scripts/check.sh` exits 0: 204 tests OK, `check_site.py` OK, `check_privacy.py` OK against the fixture, vnu reports no errors.
- `PRIVACY_FACTS=../RubikXplorer/docs/privacy-facts.json bash scripts/check.sh`: the privacy check is OK against the real app file.
- `GITHUB_ACTIONS=true bash scripts/check.sh`: exits 1, because `PRIVACY_FACTS` must be set on CI.
- `pages.yml` parses as YAML. actionlint is not installed.
- `git ls-files --eol`: every file is LF.

**Residual risks:**
- The workflow has not run on GitHub yet. Watch the first PR run (check only), a scheduled run (check only) and the first main push (check, then deploy).
- Still to do after merge: the live HTTPS/private-window check, plus the keyboard, 200%/320 px and light/dark checks, recorded in the PR.
- PO human gate before merge: confirm the publisher wording and the effective date.
- "ExxerPro" remains on the other pages (deferred).

### Follow-up review pass (2026-11-13)

**Summary:** A follow-up review of `b1d44043427897ee492acb8ff9de0cf7b6b8e234..HEAD` on `feat/13-4-privacy-policy`. The diff file is `/tmp/claude-1000/-mnt-e-Dynamic-IndFun-RubikXplorer/ceb048e1-ff63-4cfc-9477-fa35ef7d187a/scratchpad/review-13-4-f.diff`. All four layers ran as Claude subagents, because the Codex marker was absent. Five low entries were patched in one site-repo commit.

**Files changed (cubentia-site, this pass):**
- `scripts/check_privacy.py`: the docstring now gives the full hash scope, and the missing-fact hint HTML-escapes the value.
- `site/styles.css`: the `.effective-date` rule is removed (the intent allows CSS for headings and lists only).
- `site/privacy/index.html`: the now-unused `class="effective-date"` is removed. The hash does not cover classes, so `privacy-policy.json` is unchanged.
- `README.md`: the change steps cover the date-pinned self-tests, the fixture refresh when the facts change, and how to change the publisher, Play developer or contact by hand.

**Review findings:** 37 in total: high 0, medium 1, low 27, false 8, maybe-false 1.
- Patched (5 entries, all low):
  - README steps for the date-pinned tests and the fixture (3 findings, one root cause);
  - README steps for changing the publisher, Play developer or contact;
  - the docstring hash scope;
  - escaping in the missing-fact hint;
  - the `.effective-date` CSS removed.
- Deferred: none new. The untested workflow conditions are carried and already in `deferred`.
- Rejected: every row and its reason is in the Review Triage Log under "Review pass (follow-up)". In short:
  - false: `main` is mandated, the address and Spanish are PO and epic scope, Learners, the global list CSS affects no other page, concurrency is allowed, the wider hash and stricter checks are a superset, AC4 is post-merge;
  - low and unlikely, or the fix adds complexity: wording not matched, email handling wording (a PO legal decision), footer hash scope, no test for the fallback, issue alerts (need new permissions), the implied-end-tag parser, the token linter, hidden statements, `@2x` assets, truncated writes, plus the carried rows;
  - maybe-false: fork PRs and `vars` (low even if true).

**Follow-up review recommended: false.** This was a follow-up pass and patched no high (patched by verdict: high 0, medium 0, low 5), so the work has converged.

**Verification (after the patches):**
- `bash scripts/check.sh` passes: 204 tests OK, `check_site.py` OK, `check_privacy.py` OK against the fixture, vnu reports no errors.
- `GITHUB_ACTIONS=true bash scripts/check.sh` exits 1.
- `check_privacy.py --facts` against the app's real `docs/privacy-facts.json` prints OK.
- `pages.yml` parses as YAML.
- `git ls-files --eol` shows every file as LF.

**Residual risks:**
- Unchanged from the first pass: the workflow has not run on GitHub yet (watch the first PR, scheduled and main-push runs), the post-merge HTTPS and accessibility checks are still to do, and the PO gate remains on the wording and date.
- The fork-PR `vars` behaviour is unverified.
