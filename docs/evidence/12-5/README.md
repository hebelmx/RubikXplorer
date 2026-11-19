# Story 12.5 progress record

Story 12.5 (store listing and public portfolio repo) is **not done**. Some of its work was done ahead of the story, outside the usual story flow. This page records what exists and what is still open, so the story can start from it.

## Done ahead of the story

| AC part | What was done | Evidence |
|---|---|---|
| OQ-1 trademark search | The PO ran two phonetic searches in the IMPI trademark database (Mexico) on 2026-11-08 at 17:07, before choosing the name Cubentia. They returned 36 and 42 records, and neither has an exact or registered CUBENTIA. The nearest mark is CUENTIA, a pending mixed mark in class 42 (IT services). Other nearby marks are CUBEBID and CUBONIC, also class 42. No mark in class 9 (software) or class 41 (education) sounds like CUBENTIA. | The PO keeps the two result PDFs. They are not in this repo because they list third-party applicants. |
| Final name | Cubentia, chosen on 2026-11-08 after that search, with no "Rubik's" mark in the name. | Story 12.6 rebrand, `sprint-change-proposal-2026-11-08-cubentia-and-pre-pilot.md` |
| Store assets | Store icon, feature graphic, phone and 7- and 10-inch tablet screenshots, and the listing text with an es-419 draft. They were made for the internal-testing upload. | `assets/`, PR #138 (`9db1b38`) |
| Privacy-policy URL and website | <https://hebelmx.github.io/cubentia-site/privacy/> is registered in Play Console (Story 13.6). The website is <https://hebelmx.github.io/cubentia-site/>. | Epic 13, `docs/play-first-publish.md` § 2 |
| Public repo | The GitHub repository is public. | GitHub repository settings |

The trademark search is a quick first-pass search in one registry. It is not a legal opinion.

## Still open

- **App launcher icon.** The app has no launcher icon resources yet (`app/src/main/res` has no `mipmap-*`). The PO has icon drafts outside the repo. The AC needs an icon with no "Rubik's" mark, and the store icon in `assets/AppIcon.png` should match it.
- Short promo video recorded from the app.
- Content-rating questionnaire in Play Console.
- Store screenshots added to the website's landing page.
- README for reviewers: screenshots, architecture overview linking `docs/ARCHITECTURE.md`, CI and coverage badges.
- Rename the GitHub repository and local folder to Cubentia, and update the site repository's `APP_REPO` variable and source links.
- Promotion from closed testing to production, after every gate passes (Story 12.4).
