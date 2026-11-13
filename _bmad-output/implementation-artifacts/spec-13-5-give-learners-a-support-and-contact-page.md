---
title: 'Give Learners a support and contact page'
type: 'feature'
created: '2026-11-13'
status: 'done'
implementation_repo: '/mnt/e/Dynamic/IndFun/cubentia-site'
implementation_branch: 'feat/13-5-support-page'
baseline_revision: 'c54fd2a69700e9958152b1c5af79cb806f3802f2'
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

**Problem:** Story 13.2 left `/support/` as a two-line stub. It does not say what to put in a problem report, does not point developers to the source repository's issues, and the privacy page does not link to it, so a Learner who reads the policy cannot find help.

**Approach:** Expand `site/support/index.html` into the full support page (contact email, what to include, no personal data, privacy link, developer issues link with a plain warning), add a support link to the privacy page by following the site's documented policy-change procedure, and add a self-test that holds the shipped support page to the policy record's contact email.

## Boundaries & Constraints

**Always:**
- Contact email is `exxerpro@exxerpro.com` (PO decision 2026-11-13: the same address as the privacy policy and `privacy-policy.json` `contactEmail`; it supersedes the AC's `abel.briones@exxerpro.com`). It is the only email on the support page.
- What to include in a report, in this wording: device, Android version, app version from Settings → Apps → Cubentia, and what happened.
- Say plainly that no personal data is needed.
- Link `../privacy/` and the developer issues page `https://github.com/hebelmx/RubikXplorer/issues`, and say plainly that Learners should not post personal details there.
- Publisher wording, where any appears: "Exxerpro Solutions, S.A. de C.V." (never "ExxerPro").
- Copy rules: English only, "Learners", no "Rubik", short declarative sentences, no exclamation marks.
- `bash scripts/check.sh` in the site repo passes (self-tests, `check_site.py`, `check_a11y.py`, `check_privacy.py`, vnu), and also with `PRIVACY_BASE=main`.
- Privacy page change follows README "Changing the policy": new later effective date in the marker and its text, `python3 scripts/check_privacy.py --update`, update pinned test literals, commit page + record + tests together.

**Never:**
- Do not edit `site/styles.css` (any change is a policy change); the support page uses existing `h1`/`h2`/`p`/`ul` styles only.
- Do not weaken or relax any checker, allowlist or test assertion to make a page pass.
- No tester or pilot data, no postal address, no phone number, no script, no form, no third-party resource.
- Do not touch the privacy page's fact statements, markers other than the effective date, or `APPROVED_WORDING`.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Shipped support page | `site/support/index.html` | Self-test passes: mailto is the record's `contactEmail`, it is the only email, `../privacy/` and the issues URL are linked, the four report items are present | No error expected |
| Contact drift | support mailto differs from `privacy-policy.json` `contactEmail` | Self-test fails naming both addresses | Test failure |
| Privacy link to support, date unchanged | privacy page gains the link, date still 2026-11-13 | `check_privacy.py` fails (text changed without a new date) | Fixed by the documented procedure, not by the checker |

</intent-contract>

## Code Map

- `cubentia-site/site/support/index.html` -- the 13.2 stub (skip link, header wordmark `../`, `main#main`, one `h1`, footer "© 2026 Exxerpro Solutions, S.A. de C.V."). Expand in place; keep the skeleton.
- `cubentia-site/site/index.html` L23-27 -- already links `support/` (AC2 landing half is met; leave the page alone).
- `cubentia-site/site/privacy/index.html` -- no support link today. The Contact section (last `h2`) ends with the `data-policy="contact"` mailto. Effective-date marker at L17: `<time data-policy="effective-date" datetime="2026-11-13">13 November 2026</time>`.
- `cubentia-site/privacy-policy.json` -- record (`contactEmail`, `effectiveDate` 2026-11-13, `textSha256`). The hash covers all page text and every link target, so any added link needs a new, strictly later date (`--update` and `--base` both enforce it).
- `cubentia-site/scripts/test_check_privacy.py` -- pins `DATE_MARKER` (L30), `effectiveDate` assertion (L117), and literals at L211-296, L714, L745, L789 (`OLD_DATE`). Tests using `2026-12-01` as "later" stay valid; the "earlier than the recorded" message (L296) and the pinned current date change.
- `cubentia-site/scripts/check_site.py` -- allowlists (`ul`, `ol`, `li`, `a`, external `<a href>`, `mailto:` all allowed); "Rubik" check on page text. No change.
- `cubentia-site/scripts/check_a11y.py` -- one `h1`, ordered headings, link text; no change.
- `cubentia-site/scripts/test_check_site.py`, `test_check_a11y.py` -- unittest style to mirror (stdlib, `HERE`/`SHIPPED` paths); discovered by `check.sh` via `-p 'test_*.py'`.
- `cubentia-site/README.md` -- Layout lists `support/index.html` and the scripts; add the new test file.

## Tasks & Acceptance

**Execution:**
- `cubentia-site/site/support/index.html` -- expand: how to get help (mailto `exxerpro@exxerpro.com`); `h2` "What to include" with a list: device, Android version, app version from Settings → Apps → Cubentia, what happened; a sentence that no personal data is needed; link to the privacy policy; `h2` for developers linking `https://github.com/hebelmx/RubikXplorer/issues` and saying Learners should not post personal details there and should email instead; home link -- AC1.
- `cubentia-site/site/privacy/index.html` -- in the Contact section add one sentence linking `../support/` for help with the app or reporting a problem; set the effective date to `2026-11-14` / `14 November 2026` (policy text changed; the checker requires a date strictly later than 2026-11-13) -- AC2.
- `cubentia-site/privacy-policy.json` -- regenerate with `python3 scripts/check_privacy.py --update` -- record follows the page.
- `cubentia-site/scripts/test_check_privacy.py` -- update the pinned current-date literals to the new date per README step 4; no assertion removed or loosened.
- `cubentia-site/scripts/test_support_page.py` -- new stdlib unittest on the shipped `site/support/index.html`: the only mailto/email equals `privacy-policy.json` `contactEmail`; links `../privacy/` and the issues URL; contains the four report items and the no-personal-data and do-not-post statements; plus one negative case (a changed email fails the helper) -- I/O matrix rows 1-2.
- `cubentia-site/README.md` -- Layout: add `test_support_page.py`; one line that the support email must match `privacy-policy.json`.

**Acceptance Criteria:**
- Given `/support/`, when it is read, then it gives `exxerpro@exxerpro.com` (the privacy policy's contact), lists device, Android version, app version from Settings → Apps → Cubentia, and what happened, says no personal data is needed, links the privacy policy, and links the public repo's issues while telling Learners not to post personal details there.
- Given the landing page and the privacy page, when opened, then each links to `support/` (`../support/` from privacy), and `bash scripts/check.sh` (internal link check included) exits 0, also with `PRIVACY_BASE=main`.

## Spec Change Log

## Review Triage Log

### 2026-11-13 — Review pass
- verdicts: 23 findings — high 0, medium 0, low 13, false 10, maybe-false 0
- findings:
  - Blind Hunter
    - `[low]` `[reject]` the new effective date (14 November 2026) is one day in the future. Real, but forced: AC2 needs a privacy-page link to `/support/`, any link changes the hash, and the unchanged checker requires a strictly later date. Avoiding it needs a PO date decision or a weaker checker (forbidden). Design Notes record it, and the PO confirms the date before merge.
    - `[false]` `[reject]` the link could go in the footer or nav to avoid a policy version. The hash covers every page element, footer and nav included, so any placement changes it.
    - `[low]` `[patch]` README step 4 pins the date literal, which goes stale. Fixed: it now says "current effective date (`DATE_MARKER`)" with no literal.
    - `[low]` `[reject]` the date literals in `test_check_privacy.py` are hand-copied rather than built from one constant. This is the structure 13.4 shipped and the README documents. Refactoring it is beyond a direct correction, and developers meet it only when the policy date changes.
    - `[low]` `[patch]` no negative tests for a missing mailto, a missing privacy link or the two statements. Fixed: four tests were added.
    - `[false]` `[reject]` the mailto comparison is too strict for `?subject=`, encoding or case. The page uses a bare address, and the strictness is deliberate. A future variant fails loudly, which is correct behavior, not a defect.
    - `[false]` `[reject]` `test_contact_is_the_policy_contact` adds nothing. It pins the PO-decided address, so the record and the support page cannot drift together to another address unnoticed.
    - `[low]` `[reject]` the support page's "no personal data … other than the email address you write from" reads as contradictory, and the policy does not cover support email handling. The sentence names the one detail that is unavoidable, and is accurate. A policy promise about email handling is a new legal commitment, rejected in 13.4 for the same reason.
    - `[false]` `[reject]` the "Settings → Apps → Cubentia" path is fragile and the arrows are hard for screen readers. The PO fixed this wording (clarification 4).
    - `[false]` `[reject]` the issues link uses the old repo name. The PO supplied this URL (clarification 3), and Story 12.5 updates the site's source links when it renames the repo (epic context).
    - `[low]` `[patch]` the README calls `test_support_page.py` a "self-test". Fixed: it is now described as a content test. `check.sh` discovers it via `-p 'test_*.py'` (327 tests ran).
    - `[false]` `[reject]` the support page's title and meta description are stale. "How to get help with Cubentia or report a problem." still describes the page.
  - Intent Alignment
    - `[false]` `[reject]` the date resolution was settled silently. Spec Design Notes record it, and so do commit 96b71b0's message and this run's result.
    - `[low]` `[patch]` nothing asserts AC2's links on the landing and privacy pages. Fixed: `SupportLinks.test_landing_and_privacy_link_support` was added.
    - `[false]` `[reject]` the email is compared with the record, not the published policy. `check_privacy.py` requires the page's contact marker to equal the record's `contactEmail`, so the two are equal transitively.
    - `[low]` `[reject]` exact-string checks are brittle to rewording. This is deliberate and fails loudly: rewording the page means editing one constant, and loosening the match would need fuzzy logic.
    - `[false]` `[reject]` the app-repo spec file is not in the diff. It is committed on `chore/13-5-site-support-tracking` at finalize. The diff covers the site repo only.
  - Edge Case Hunter
    - `[low]` `[reject]` mailto with a query or encoding gives a false failure. Grouped with the Blind Hunter strict-mailto row: the shipped page uses a bare address, and the failure is loud.
    - `[low]` `[reject]` a different letter case fails. Same reason: it is contrived, and the fix adds normalization branches.
    - `[low]` `[reject]` a nested `li` loses the outer item's text. The page has no nested lists, and the fix adds a stack.
    - `[low]` `[reject]` inline markup before punctuation breaks the statement match. The statements are plain text, so this is contrived and the failure is loud.
    - `[low]` `[patch]` untested failure branches. Grouped with the Blind Hunter negative-tests row (same fix).
    - `[low]` `[reject]` the future effective date. Grouped with the Blind Hunter date row. The proposed clock guard would change the checker, which is out of scope and would make it time-dependent.
  - Verification Gap
    - (no findings)

## Design Notes

- **Effective date:** the PO asked to keep the date unless the policy text changes. Adding a support link adds visible text to the policy page and changes the hashed link set, and the checker (unchanged by rule) requires a strictly later date for any hash change. So the policy text changes and the date moves to the earliest accepted date, 14 November 2026. The PO confirms the date before merge (13.4 human gate precedent).
- Support page sketch:
  ```html
  <h2>What to include</h2>
  <ul>
    <li>Your device, for example the phone model</li>
    <li>Your Android version</li>
    <li>The app version, from Settings → Apps → Cubentia</li>
    <li>What happened, and what you expected</li>
  </ul>
  ```

## Verification

**Commands (in `/mnt/e/Dynamic/IndFun/cubentia-site`):**
- `bash scripts/check.sh` -- expected: all self-tests OK, site, a11y, privacy (fixture) and vnu pass, exit 0.
- `PRIVACY_BASE=main bash scripts/check.sh` -- expected: exit 0 (new date is later than main's 2026-11-13).
- `git ls-files --eol` -- expected: all `i/lf`.

**Manual checks:**
- Post-merge: keyboard flow, 200% zoom, 320 CSS px and light/dark on `/support/` recorded in the PR.

## Auto Run Result

Status: done

**Summary:** expanded `/support/` into the full support and contact page. It gives the policy contact `exxerpro@exxerpro.com`, what to include in a report (device, Android version, app version from Settings → Apps → Cubentia, what happened), says no personal data is needed, links the privacy policy, and links `https://github.com/hebelmx/RubikXplorer/issues` with a plain "Do not post personal details there". The privacy page's Contact section links the support page. Following README "Changing the policy", its effective date moved to 14 November 2026, the record was regenerated and the pinned test dates were updated. A new content test holds the support page to the record's contact and checks AC2's links.

**Files changed (cubentia-site, commits 96b71b0, d68b337, bfe189b on feat/13-5-support-page):**
- `site/support/index.html` -- the full support page.
- `site/privacy/index.html` -- support link in Contact, effective date 2026-11-14.
- `privacy-policy.json` -- regenerated with `--update` (new date and hash).
- `scripts/test_check_privacy.py` -- pinned date literals moved to 2026-11-14. No assertion was loosened.
- `scripts/test_support_page.py` -- new content test, 11 cases.
- `README.md` -- Layout entry and the policy-change step 4 wording.

**Review:** 23 findings (high 0, medium 0, low 13, false 10). Patched 4 low entries: the negative tests, the AC2 link test, the README date literal and the README naming. Nothing deferred. Every rejection and its reason is in the Review Triage Log.

**Follow-up review recommended:** false. Patched entries were all low (0 high, 0 medium).

**Verification:** both `bash scripts/check.sh` and `PRIVACY_BASE=main bash scripts/check.sh` exited 0: 327 self-tests, site, a11y, privacy and vnu all passed. `git ls-files --eol` shows all files `i/lf`. Matrix row 3 was confirmed by hand: with the link but the old date, `check_privacy.py --base main` fails.

**Residual risks:**
- The PO must confirm the 14 November 2026 effective date before merge. If it merges on 13 November, the live policy shows a date that has not arrived yet for one day.
- Post-merge manual checks on `/support/` are still to do and record in the PR: keyboard flow, 200% zoom, 320 CSS px, and light and dark themes.
