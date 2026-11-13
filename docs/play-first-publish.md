# First publish to Google Play

This is the checklist for taking Cubentia from its first uploaded build to installs by the pilot testers in closed testing. Progress is tracked in issue #127. Production is out of scope (Story 12.5).

**Where things stand (2026-11-13):** tag `v0.9.0` ran the release workflow ([run 43358827489](https://github.com/hebelmx/RubikXplorer/actions/runs/43358827489)). It passed and left version 0.9.0 (`versionCode` 1) as a **draft** on the **internal** track. Nothing has been rolled out.

Do the sections in order. Each one ends with a **Check:**; don't start the next section until it passes. Play Console menu names are as of November 2026; if one has moved, use the Play Console search box.

**The repository is public.** Never post tester names, email addresses, the Google Group address or an opt-in link in GitHub. Record only counts, versions and dates.

## 1. Roll out internal testing

Internal testing doesn't go through Google's full review, so it's the quickest way to get the build onto a real device.

1. **Confirm the signing setup.** Go to **Test and release → App integrity → Play app signing**. The page should say releases are signed by Google Play. Play turned this on with a Google-generated app signing key when the workflow made the first upload.
   - Compare the *upload key* certificate SHA-256 fingerprint with your keystore: `keytool -list -v -keystore <upload.p12> -storetype PKCS12 -alias <alias>`. Every pair of characters must match. The stored value starts with `C8:13:81:DF` and ends with `0B:1F`.
   - If it doesn't match, stop. Don't roll out, and report it in #127. After the first rollout, the app signing key can never be changed.
2. **Add yourself as a tester.** Go to **Test and release → Testing → Internal testing → Testers**. Create an email list containing your own Google account, select it, and save.
3. **Roll out the draft.** On the **Releases** tab, open the 0.9.0 draft and select **Next**. The review page lists any errors. Then select the rollout button, labelled **Start rollout to Internal testing** or **Save and publish**.
   - If rollout is blocked, Play Console lists the missing setup tasks. Complete them using sections 2 and 3, then come back to this step.
4. **Install it.** On the **Testers** tab, copy the opt-in link and open it on the Lenovo or Galaxy reference tablet. In the Play Store app, tap the profile picture and switch to the tester account first. Accept the invitation, then install Cubentia from Play. Allow a few minutes after opting in. If Play says the app isn't available, clear the Play Store cache and try again.

**Check:** on the tablet, **Settings → Apps → Cubentia** shows version 0.9.0, and the app opens to the Learn tab.

## 2. Complete app content

Closed testing goes through Google's review, and the review requires every App content declaration. Go to **Policy and programs → App content**.

The facts behind the answers: the release manifest has no `INTERNET` permission, no backup and no runtime permissions, and CI enforces this (`ManifestPolicy`). The app has no account, no ads, no analytics and no crash SDK. Progress stays on the device.

**Do the privacy policy first.** The Data safety form can't be saved without its URL.

1. **Create the privacy policy page.** Recommended: GitHub Pages from this repo, for example a `docs/privacy.md` page. A page on exxerpro.com works too. The page must be public, load without a login, and not be a PDF. It needs these statements:
   - Cubentia collects no personal data and shares none.
   - It has no account.
   - Progress is stored only on the device and is removed when the app is uninstalled.
   - The publisher's name, exactly as on the Play developer account.
   - A contact email address.
   - An effective date.
2. **Check the page.** Open the URL in a private browser window. Then paste it under **App content → Privacy policy**.
3. **Answer each declaration:**

| Declaration | Answer |
| --- | --- |
| App access | All functionality is available without special access. |
| Ads | No, the app has no ads. |
| Content rating | Category: **Reference, News, or Educational**. Email: the developer contact address. Answer **No** to every content question (violence, sexuality, language, controlled substances, gambling, user interaction, shared location, digital purchases, unrestricted internet). |
| Target audience | **Your decision:** 13 and over (recommended for the pilot), or include under 13, which adds Families policy compliance and a longer review. If you choose 13 and over, answer the "appeals to children" question honestly, and keep the store listing aimed at teens and adults. |
| Data safety | "Does your app collect or share any of the required user data types?" **No**. Progress kept only on the device isn't collection. |
| Advertising ID | The app doesn't use an advertising ID. |
| Government apps | No. |
| Financial features | The app doesn't provide any financial features. |
| Health apps | The app doesn't have any health features. |
| News apps, and any other listed item | No, or not applicable. |

**Check:** every item on the App content page shows as completed.

## 3. Fill in the main store listing

Go to **Grow users → Store presence → Main store listing**. Google's review checks this listing, and testers see it on the opt-in page. The assets don't exist yet; whoever prepares them saves them outside the app source, for example in `docs/store/`.

| Field | Requirement |
| --- | --- |
| App name | Cubentia (up to 30 characters) |
| Short description | Up to 80 characters |
| Full description | Up to 4,000 characters |
| App icon | 512 × 512 px, 32-bit PNG, up to 1 MB |
| Feature graphic | 1024 × 500 px, JPEG or 24-bit PNG |
| Phone screenshots | 2–8; each side 320–3,840 px, longest side at most twice the shortest |
| Tablet screenshots | Recommended, 7-inch and 10-inch; add them, because the reference devices are tablets |

Then go to **Grow users → Store presence → Store settings**:
- App or game: **App**.
- Category: **Education**.
- Contact email: a role address, not a personal mailbox. It's shown publicly on the listing.

The "Cubentia" trademark search (Story 12.5) isn't done yet. That's acceptable for a closed pilot; finish it before production.

**Check:** the Play Console dashboard shows no open setup task.

## 4. Open closed testing for the pilot

1. **Create the tester group.** Create a Google Group at groups.google.com for the pilot testers, about 8 consenting beginners. Set it so only invited people can join, then invite them.
   - If you create the group under the exxerpro.com Workspace, turn on **Allow members outside your organization**, or Gmail testers can't join.
   - For 8 testers, an email list on the track works too. Use one or the other, not both.
2. **Set up the track.** Go to **Test and release → Testing → Closed testing** and use the default closed track; select **Manage track**.
   - **Countries / regions** tab: add every tester's country. Testers outside the selected countries can't install.
   - **Testers** tab: add the group's email address, or the email list, and save.
3. **Promote the build.** Go to **Testing → Internal testing → Releases**. On the 0.9.0 release, choose **Promote release → Closed testing**, then review and save.
4. **Send it for review.** Go to **Publishing overview** and select **Send changes for review**. The first review of a new app can take up to 7 days.
   - If **Managed publishing** is on, an approved release doesn't go live until you select **Publish**. Leave it off for the pilot.
   - Google sends the result to the Play Console inbox and by email. If the review is rejected, follow the policy email, fix the problem, send the changes for review again, and note the rejection in #127.
5. **Invite the testers.** After Google approves the release, copy the opt-in link from the track's **Testers** tab. Send it to testers by email or direct message only. Each tester:
   1. joins the group;
   2. opens the link with that same Google account and taps **Become a tester**;
   3. installs Cubentia from Play.

**Check:** at least one pilot tester has 0.9.0 installed. Record the number of testers who installed it, the version, the `versionCode` and the date in #127 (Story 7.8, AC2).

## 5. Run the pilot and ship fix builds

1. **Run the sessions** with the [pilot session protocol](pilot-session-protocol.md). Keep observations outside the app and outside GitHub. Severity 1–2 findings go to Story 12.4.
2. **Ship fix builds.** Each fix build needs a new signed tag on `main`, such as `v0.9.1` (see the [release runbook](release.md)). Each tag needs a new workflow run, because Play rejects a `versionCode` that was already uploaded. For each fix:
   1. the tag uploads to internal; roll it out there (as in section 1) and check it on a tablet;
   2. promote it to closed testing and send it for review (as in section 4);
   3. ask testers to update Cubentia from Play. Testers on the closed track don't get a build until it's promoted there.
3. **Optional: switch internal uploads to automatic.** Do this only after the closed-testing review is approved (section 4, step 4) and the app no longer shows as a draft under **All apps**:
   ```
   gh variable set PLAY_RELEASE_STATUS --repo hebelmx/RubikXplorer --body completed
   gh variable list --repo hebelmx/RubikXplorer
   ```
   The next tag's upload step must then commit. If it fails with "Changes cannot be sent for review automatically", either send the changes for review by hand in **Publishing overview**, or add `changesNotSentForReview: true` to the upload step in `.github/workflows/release.yml` (a code change through a PR). Promotion to closed testing always stays manual.

**Done when:** #127 records the pilot installs and the completed sessions, and it's closed.
