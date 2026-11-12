# Cubentia beginner pilot session

Recruit approximately eight beginners. Arrange sessions early enough to fix usability issues before production. The facilitator records observations outside the app; Cubentia remains offline with no telemetry, analytics, accounts or added permissions (NFR-8).

## Consent and setup

Explain the purpose, tasks, approximate session duration, and that participation is voluntary and can stop at any time. Obtain consent before observing or recording. Obtain separate consent for any optional recording or A/B comparison of plain instructions and contextual explanation; otherwise run the standard experience. Avoid collecting names or contact details in observation sheets. Assign a participant code and keep recruitment contact information separately with restricted access. Agree a retention/deletion date and who may read the notes; delete recordings and identifiable notes by that date.

Record participant code, date, facilitator, app version/versionCode, device/Android version, prior cube experience, consent and retention date. Confirm the closed-testing opt-in install succeeds and the expected version is present. Prepare a charged device, a physical 3×3 cube in a valid state and a quiet space. Explain that the product is being tested, not the participant. Ask them to think aloud; avoid teaching moves unless they request help, and record facilitator help separately from app hints.

## Tasks

1. Start from the welcome flow and find the Course. Ask the beginner to explain the first instruction in their own words without teaching Notation. Separately, once the first Lesson's Objective is shown, ask them to explain that Objective in their own words (SM-4). Record the two answers separately.
2. Work through all seven Course Stages to a first solve, recording each Stage separately. Allow breaks or multiple sessions and record unfinished tasks honestly.
3. During a Stage, invite one deliberate wrong Move. Observe whether the Learner notices, understands the recovery instruction and can recover. Record the mistake, recovery path and help required.
4. After the Course, ask for one solve of a fresh Scramble without step-by-step guidance (SM-2), and record it as independent, with hints, or with facilitator help. Then run at least three successive fresh-Scramble practice attempts at the same Stage with reduced assistance. Number each attempt and record the assistance setting and hints used (SM-5). Observe understanding of completion and Mastery. Inspect progress on You; use reset only with explicit participant agreement.
5. Follow the instructions on a physical cube. Observe orientation, interpretation of Move instructions without Notation knowledge, ability to keep the cube and app in sync, and recovery from a deliberate mistake. Do not imply camera recognition is available.
6. For participants who finished the Course: the 2-look last-layer mini-Course, then one last-layer solve with 2-look Algorithms and no hints (SM-9). Record completion and hint use separately from the beginner Course.
7. Ask what was confusing, what helped, and whether they could continue alone. Record installation/access problems too.

## Observation sheet (one row per task or Stage)

| Participant code / build | Session # and start–end time | Task, Stage and Lesson, attempt # | Completed independently / with help / incomplete | App hints used (count/type) | Incorrect Moves (count) | Facilitator help | Points of confusion / observed behavior | Mistake (deliberate/accidental) and recovery | Severity 1–4 / issue reference |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| | | | | | | | | | |

Number every session and every attempt at the same Stage, and always record start and end times: SM-6 needs elapsed time and session count to a first solve, SM-5 needs hints per attempt over successive attempts, and SM-7 needs incorrect Moves per Lesson and Stage, so name the Lesson on every Course row. SM-8 (scan success) does not apply to the v1.0 pilot, which has no camera. Record observed behavior separately from interpretation. Note interruptions, and the participant's words with consent. Never infer success from Move count. Note missing observations as missing. Store the sheet in the team's access-controlled research storage outside the app and repository; link only de-identified issue summaries into the issue tracker.

Severity: **1** blocks installation, progress or recovery with no practical workaround; **2** major confusion or failure requiring facilitator intervention; **3** recoverable friction that slows progress; **4** minor copy or presentation issue. Record impact and reproduction steps, not just the number. Escalate severity 1–2 findings for fixes before production and retest with beginners. Summarize task completion, hint use, confusion and recovery as directional evidence for SM-1–SM-7 and SM-9; about eight people do not establish statistical significance. Epic 12 owns release-gate triage and vitals review.
