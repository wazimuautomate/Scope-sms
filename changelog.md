# changelog.md

Dated, terse session outcomes. Not a copy of git log.

---

## 2026-07-28 — v1.5.0: promotional-tone checker + gateway signup/balance hints

Same session as the Play Store abandonment below. Branch
`feature/tone-checker-and-settings-hints` → PR #26, squash-merged, tagged
`v1.5.0` (versionCode 12), release green, `update.json` on `main` confirmed
updated — the client's in-app updater will offer this.

### Promotional vs transactional tone check (client's field request)
The registered sender ID is transactional with Safaricom/BlazeTech; a message
that reads as promotional risks being silently blocked by the carrier's own
filter, with no error the gateway client can see or log. New
`domain/templates/PromotionalToneChecker` flags urgency language, sales
calls-to-action, discount framing, prize/incentive language, ALL-CAPS
shouting, repeated `!`/`?`, and multi-emoji runs — checked against the
**rendered** template preview (catches promotional bundle descriptions
substituted via `{bundle_list}`/`{package}`, not just the surrounding
template text). Deliberately does not flag this app's own vocabulary
("offer", "free", bare prices) since that's this app's single most common
legitimate message. Shown as a live, advisory-only warning card in the
Templates screen (never blocks saving — a heuristic will have false
positives). Full design rationale and the false-positive test cases are in
`memory.md`.

### Settings: where to get an API key, and to keep enough balance
A signup link to `https://sms.blazetechscope.com/apikeys` plus a reminder to
keep enough SMS balance/tokens, next to the gateway credentials fields —
client noted the API key field gave no indication of where a key comes from.

---

## 2026-07-28 — Play Store push started; two blockers found, CI pipeline for Play added

Client asked to take the app to Play Store production, with a signed AAB and
seamless future updates, while keeping the existing GitHub direct-install
channel untouched. Branch `feature/play-store-aab-bundle` → PR #24 (open, not
yet merged — see below).

### Built: on-demand signed AAB workflow
`.github/workflows/playstore-bundle.yml`, `workflow_dispatch` only. Runs
`./gradlew bundleRelease`, signed with the existing permanent `scope-sms`
keystore (same `ANDROID_*` secrets `release.yml` already uses) as the Play
Console "upload key" — no new key generated. Deliberately does not touch
`release.yml`, `update.json`, or the tag-triggered GitHub Release flow at all;
it is a second, independent output. Full rationale in `memory.md`.

### 🔴 Blocker 1 — GitHub Actions is billing-locked account-wide, since 2026-07-19
Every job on every run (this PR, and `main`'s last push 8 days ago) fails in
2-3 seconds with "recent account payments have failed or your spending limit
needs to be increased." Not caused by this session's change — `main`'s own
last CI run already shows it. No local JDK in this session's environment
either, so **nothing could be verified**, CI or local. PR #24 is held
un-merged per CLAUDE.md's own "merge only after CI is green" rule — this
isn't a judgment call to override, since unlike the 2026-07-18 artifact-quota
incident (tests/lint/instrumented suites still ran and passed that time),
here literally no job started at all. **Needs the client to fix GitHub
Billing & plans before any further CI-dependent work — including cutting the
next real GitHub release — can be verified.**

### 🔴 Blocker 2 — the in-app self-updater likely conflicts with Play's own update-mechanism policy
Separate from the SMS-permission question (client says already cleared with
Google). Play policy (Device and Network Abuse / unauthorized code execution)
restricts apps distributed via Play from updating themselves by any method
other than Play's own mechanism, or downloading executable code from outside
Play. This app's existing `update/AppUpdater` does exactly that: downloads an
APK and installs it via `REQUEST_INSTALL_PACKAGES` + `ACTION_VIEW`, entirely
independent of Play. Submitting this exact build to Play risks rejection or
removal for that reason **alone**, regardless of the SMS-permission outcome.
Not yet resolved — needs a decision (see `memory.md`) before a production
submission, most likely a Play-specific build variant that drops the
in-app updater and `REQUEST_INSTALL_PACKAGES`, keeping both intact only for
the GitHub-direct build.

### Resolved this session: PR #24 merged, first AAB built
Client flipped the repo public to route around the billing lock (checked repo
history for anything secret-shaped first — clean, see `memory.md`). CI went
green, client explicitly approved the merge, `playstore-bundle.yml` ran
against `main` and produced `scope-sms-1.4.0-vc11.aab` (signature verified).
Blocker 2 (self-updater vs Play policy) is still unresolved — this AAB proves
the build mechanics, not that the app is Play-policy-clean yet. **Reminder:
confirm the repo has actually been flipped back to private.**

### DECIDED: Play Store abandoned, not paused
After hearing about the SMS-permission risk and the self-updater-vs-Play-policy
risk, client concluded Play would definitely reject the app: "we don't go that
way, we just go the normal way he used to do it... since it was even his
personal app." Confirms CLAUDE.md's original call was right. `playstore-bundle.yml`
deleted (this project deletes abandoned code rather than keeping it dormant —
see `memory.md`). GitHub direct-install remains the only distribution channel,
unaffected.

### Still needs the client (Play Console — none of this is CI-doable)
Developer account + $25 fee + identity verification (can take hours–days),
app listing, privacy policy URL, Data Safety form, content rating
questionnaire, the Permissions Declaration Form for `RECEIVE_SMS`/`READ_SMS`,
and the Play App Signing enrollment choice (own key vs Google-generated —
see `memory.md`, this choice is essentially one-time). None of these are
things a session can do on the client's behalf.

## 2026-07-19 — v1.3.2 send-once (stop token-bleed) + v1.4.0 log controls & reset

Same day as the stuck-Sending fix below, two more releases the client asked for.

### v1.3.2 (versionCode 10) — send exactly once + send-path logging
Client: the 5-attempt retry was **re-sending and re-billing** the same SMS.
Fix (`fix/send-once-error-logging`, PR #21): `DEFAULT_MAX_ATTEMPTS` 5 → 1 with
the guard moved **before** claiming, so a job whose one attempt is already spent
— including a crash that left it `SENDING` and `releaseStuckJobs` returned to
`PENDING` — is **failed, never re-sent**. That closes the crash-recovery
double-send too. Every failure is now terminal, so it always shows in the
activity log with the gateway's reason (retryable failures used to stay silent
on "Sending…"). Added an injected `OutboundLog` port (logcat in production, no-op
in tests so the queue stays JVM-testable): logs each send, its reply id, and any
failure reason; phone masked, never the body or key. Offline-at-arrival is
unchanged (the `CONNECTED` constraint still holds an unclaimed job until data
returns). Rewrote the retry-era queue tests to the send-once contract. Shipped
(tag `v1.3.2`, release green, `update.json` versionCode 10).

### v1.4.0 (versionCode 11) — clear, force-send, reset
Branch `feature/log-controls-and-reset`, PR #22. Three controls for "when things
get messy":
- **Bulk clear** (activity-log ⋮ menu): Clear sent / pending / all. Clearing
  *pending* also cancels the unsent jobs (`OutboundQueue.cancelPending` deletes
  `PENDING`/`SENDING`) so a cleared reply can't still go out.
- **Force send** (selection bar + per-row): `OutboundQueue.forceSend(txnCode)`
  sends now, bypassing the queue AND send-once; marks job + log terminal; reuses
  the job's captured `senderId`. No-job rows (logged before enqueue) reconstruct
  from the record + current sender ID. Sequential; bulk is behind a confirm
  (each is billed); result shown as a toast.
- **Reset app** (Settings, red card + scary confirm): `AppReset` deletes the
  Keystore key, cancels all work, then `clearApplicationUserData()` and arms an
  `AlarmManager` relaunch → next launch re-runs onboarding.
- Tests for `forceSend` + `cancelPending`. No Room schema change (new methods are
  queries). Shipped (tag `v1.4.0`, release green).

---

## 2026-07-19 — Field bug: replies stuck on "Sending…" on the client's Samsungs

Branch `fix/queue-stuck-sending-samsung`. Client report: real auto-replies
stay on **"Sending…"** forever and never go out, while a **manual test send
from Settings works**. Only under load (~5+/min), toggling WiFi↔mobile data
does nothing, and it reproduces on the client's **Samsung A16 / A07 / A06**
but not on the dev's older **A05**.

### Diagnosis (no-network path exonerated)
"Sending…" is the activity-log `QUEUED` state; it only clears on a *terminal*
send outcome. The test send bypasses WorkManager + Room entirely
(`SettingsViewModel.sendTest` → gateway directly), so it proves the HTTP
client, credentials and parsing are fine and isolates the fault to the
**WorkManager drain path not running to completion** on the newer One UI
(stricter App-Standby / expedited-quota / process-reaping; the A05 is the
dev's daily driver so it sits in the Active bucket). Made *permanent* by two
code gaps: a job is marked `SENDING` before the HTTP call and only reclaimed
at the *start of the next drain that runs*, and `drain()` read one page once
while `ExistingWorkPolicy.KEEP` dropped every burst re-trigger — so a row
queued mid-drain, or stranded by a mid-send kill, had nothing to drain it.

### Fix (self-healing backstop — WorkManager only, honors CLAUDE.md #6)
- **Periodic safety-net drain** (`SendJobWorker.enqueuePeriodicDrain`, wired in
  `AppContainer.start`): a ~15-min `PeriodicWorkRequest` reusing the same
  `drain()`, so a stranded job is reclaimed within that window even if no new
  SMS arrives. Turns "stuck forever" into "sends within ~15 min at worst."
- **`drain()` now finishes the whole burst in one run**: loops over freshly
  queued rows, tracking handled ids so retryables can't spin it, so a payment
  that lands mid-drain is still sent by that drain instead of stranded.
- New JVM test `a job queued while a drain is running is sent by that same
  drain` locks the burst-tail behavior in.
- Not a substitute for the device-side battery-optimization exemption the
  reliability screen already prompts for — both matter; documented in code.

### Released v1.3.1 (versionCode 9) — same session
PR #19 squash-merged to `main` (CI green: unit + lint + both instrumented
smoke tests on API 30/36), then tagged `v1.3.1`. The tag-triggered
`release.yml` run went fully green this time (the v1.3.0 tag run had failed on
the artifact-upload step; PR #17's `continue-on-error` fix held) — signed APK
built + signature-verified, GitHub Release **Scope SMS v1.3.1** published, and
`update.json` on `main` now reads `versionCode: 9`. Client's in-app updater
will offer 1.3.1.

**Why we shipped without the planned on-device pre-check.** The chosen path was
validate-on-device first, but the debug artifact upload was blocked by the
recurring **account-wide GitHub Actions storage quota** (recalculates every
6–12h), so no debug APK could be produced to sideload. Rather than leave the
client stalled 6–12h, we shipped the signed **release** APK — a Release asset,
which uses different storage and bypasses that quota (proven by v1.3.0). The
risk is bounded: the change is additive (a periodic safety-net worker + a
drain-loop guarded by a new regression test), CI including instrumented tests
is green, and the receiver/gateway/parsing paths are untouched. **Still to do:
confirm on a live A16/A07/A06 post-update, and set battery-optimization /
"Never sleeping apps" on those phones — the code fix and that setting work
together.**

---

## 2026-07-18 — v1.3.0 released; a release-pipeline bug the quota exposed, fixed

Direct follow-on from the trusted-senders session below. Bumped to
**v1.3.0 / versionCode 8** for that feature (PR #16), then hit a second,
more serious problem cutting the actual release.

### The tag push alone did not ship the release
`release.yml`'s run for the `v1.3.0` tag failed — and this time it wasn't
cosmetic like the earlier `build.yml` runs. The same storage-quota-driven
`Upload test + lint reports` failure caused every step after it to be
**skipped**, including signing the APK, verifying it, publishing the GitHub
Release, and writing `update.json`. No release went out, even though the
tag existed and the workflow had "run."

### Fixed: artifact uploads can no longer block the pipeline
PR #17 added `continue-on-error: true` to every `upload-artifact` step in
both `build.yml` and `release.yml` — five steps. They're convenience/audit
uploads, never gates; test/lint/build/sign/verify/publish steps are
untouched. Confirmed working because PR #17's own CI run went fully green
with the storage quota still exhausted.

### Then actually published v1.3.0
`gh workflow run release.yml --ref main -f tag=v1.3.0` — re-ran the release
workflow's now-fixed `main` definition against the already-existing tag, no
retagging needed. `gh release view v1.3.0` confirms the APK + `update.json`
are attached, and `update.json` on `main` reads `versionCode: 8` /
`versionName: "1.3.0"`. The agent's app will pick this up as an in-place
update.

Full incident detail and the "what to check first if a release run fails"
note are in `memory.md`.

---

## 2026-07-18 — Trusted M-Pesa senders whitelist + a CI storage-quota fire

Branch `feature/trusted-sms-senders` → PR #14, squash-merged to `main`. Not
tagged as its own release this session — merged, but the version bump/tag
was left for next time (or on request).

The client runs a second service under his own registered sender ID
(`SKYSCOPE_`) that texts the same till-confirmation format M-Pesa uses;
those were being silently dropped since the app only trusted the official
`MPESA`/`M-PESA` shortcode. Fixed with a new **Settings → "Trusted M-Pesa
senders"** section (empty by default — every existing install keeps
today's behavior until the agent explicitly adds a sender there) and an
`extraTrustedSenders` parameter on `MpesaParser.isMpesaSender`. Full
rationale, including why `SKYSCOPE_` isn't hardcoded, is in `memory.md`.

### 🔴 Mid-session: PR #14's CI went red on a GitHub Actions storage quota
Not a code problem — every real step (unit tests, lint, both emulator
smoke tests) passed; only the artifact-upload steps failed with `Artifact
storage quota has been hit`. The repo had accumulated 239 CI artifacts
(~709MB, mostly 14MB debug APKs) in three days, all still within the
14-day retention window. Deleted the 229 oldest (kept the newest 10),
freeing ~696MB — but GitHub's quota check runs on a periodic recalculation
("every 6-12 hours" per their own error), not a live count, so a rerun
still failed the same way immediately after cleanup. With the client's
explicit go-ahead, merged PR #14 on the strength of the actual passing
test/lint/instrumented-test steps rather than waiting out the recalculation
window. See `memory.md` for the full incident note and what to check first
if this recurs.

---

## 2026-07-18 — Name variables, bundle purchase-limit, softer failure styling, log copy menu

Branch `feature/name-vars-purchase-limit-ui-polish` → PR #12, squash-merged
to `main`. Ships as **v1.2.0** (versionCode 7), tagged and released this
session with the client's explicit go-ahead. Six items from the client's
live use of v1.1.1.

- **`{first_name}` / `{last_name}` template variables** — some M-Pesa names
  run up to 50 characters and were pushing replies into a second billed SMS
  segment. Split off `{name}` in `TemplateEngine`; allowed in both flows.
- **Bundle purchase-limit** — Safaricom restricts some offers to one
  purchase per number per day. New `PurchaseLimit` enum, a picker in the
  Rules editor next to Category, and a `{purchase_limit}` template variable
  (matched flow) rendering "once a day" / "as many times as you like" so the
  agent can mention it. Room `v2→v3` migration (nullable `ADD COLUMN`, same
  shape as the category migration) — fully backward compatible with the
  client's existing price list, which reads as unrestricted (today's
  behavior) until a bundle is edited to say otherwise.
- **Softer failure styling** — the solid red "failed to send" card was "too
  harsh" per the client. Both the Activity log row and Home's recent-replies
  card now use a red border + red status text instead of a red fill.
- **Bundle name on Home's recent-replies card** — was already on the
  Activity log row, now also shown on Home alongside price/name/time/status.
- **Per-row copy menu on the Activity log** — a 3-dot menu per row to copy
  the M-Pesa code, phone number, or full outbound message.
- **Version bump** — 1.1.1 (6) → 1.2.0 (7).

Full technical detail (schema shape, why `MULTIPLE_PER_DAY` is the default,
what was deliberately left untouched) is in `memory.md`'s dated section for
this session.

### Process note
No local JDK was available in this session's environment — CI was the only
compiler (CLAUDE.md constraint 8's baseline; the 2026-07-16 "local toolchain"
note doesn't hold everywhere this project runs). The `app/schemas/.../3.json`
Room schema was pulled from the CI `room-schemas` artifact and committed
before merge, same workflow as the `2.json` category migration.

---

## 2026-07-16 — Gateway false-failure (5x duplicate sends) + activity select/copy/delete

Branch `feature/gateway-fix-and-log-actions` → PR #10. Ships in the unreleased
**v1.1.0** (versionCode 5, on `main` after PR #9). Two items from the agent's live
testing.

### 🔴🔴 The gateway's REAL success shape — the app was misreading it as failure
The agent sent an unmatched-amount reply; it was **delivered** but logged FAILED
("Unexpected gateway response: no messageid or success code — gave up after 5
attempts"), and the SCOPE dashboard showed the message sent **5 times**.

**Root-caused by calling the live endpoint** (with the agent's key, to their own
number, authorised). The gateway's success response is **not** the documented
`response-code`/`messageid`:
```
{"status":"success","statusCode":"200","reason":"success","mobile":"254…",
 "invalidMobile":"","transactionId":"…","msgId":"","requestTime":"…"}
```
`statusCode` is a **string**, the id is **transactionId** (`msgId` is empty on the
immediate response), and the HTTP `Content-Type` is `text/html` (body is JSON —
Moshi parses it regardless). The app read only the documented fields → all null →
`SendFailure.Unexpected`, which is **retryable** → the queue resent 5× (per-job
retries don't dedupe) then logged FAILED. Every retry was a real, charged SMS.

Fix: `SendSmsResponse` now models the live shape; `interpret()` treats
`status=success` / `statusCode="200"` / any non-blank id (msgId|transactionId|
messageid) as **delivered → Sent** (no retry, no duplicates), recording the id;
`invalidMobile` non-blank → terminal `InvalidPhone`. The documented shape still
works (kept for safety). +3 tests, incl. the exact captured body. **This affects
the released v1.0.3** — it ships in the next release.

### Activity log: select all / copy / delete
Long-press a row to select; a contextual bar offers Select all, Copy (selected
rows → clipboard as plain text, for pasting when a customer disputes a payment),
and Delete (confirmed). DAO `deleteByIds` + repo `delete(ids)`; ViewModel selection
state + `buildCopyText`; selectable rows (combinedClickable + highlight).

### Gotcha — gh multi-account broke `git push`
Two GitHub accounts are logged in (`wazimuautomate` = repo owner, `Wazimu90`). The
active one had flipped to `Wazimu90` → `git push` failed "Repository not found"
(the Windows `manager` credential helper served the wrong token). Fixed:
`gh auth switch --user wazimuautomate` + a **repo-local** `credential.helper=
!gh auth git-credential` so this repo always uses gh's active-account token.

---

## 2026-07-16 — v1.0.3 crash root-caused (ICU regex) + bundle categories (1.1.0)

Two phases in one session. The v1.0.x releases went out mid-session; the client
confirmed the Messages tab works. Then a feature add.

### The Messages-tab crash — actually root-caused this time
The on-device CrashReporter added in v1.0.2 paid off immediately: the agent's
Samsung/Android 14 handset produced the real stack trace —
`PatternSyntaxException: Syntax error … near index 25` on `\{[a-zA-Z_][a-zA-Z0-9_]*}`.
The token regex escaped the opening `{` but **left the closing `}` bare**.
Android's ICU-backed `java.util.regex` rejects a lone `}`; the desktop JVM
(Robolectric/CI) tolerates it as a literal — so 300 green tests never saw it, and
two layout rewrites never could. Fixed by escaping the brace (`\}`) in both
`TemplateEngine` and `TemplateVariable`. Shipped as **v1.0.3**. **It was never a
layout bug.**

### 🔴 PROCESS GOTCHA — main regressed behind a release
`v1.0.3` was tagged on a branch commit (`9a464df`) whose fix **was never merged to
main** (PR #8 squashed an earlier branch state, then the regex fix landed only on
the tag). Result: the *released* v1.0.3 APK is correct, but `main` sat at the
buggy v1.0.2 regex with `update.json` claiming v1.0.3. **Lesson: cut releases from
main after merge, or verify the tag's fix is on main.** The 1.1.0 feature branch
re-applies the regex escape so this baseline isn't the crashing one — that restores
it to main via the feature PR.

### Bundle categories (feature) → 1.1.0 / versionCode 5
Client asked to categorise bundles (Data/Minutes/SMS) and quote one category at a
time. Chosen design (asked): **per-category template variables**.
- `BundleCategory` (DATA/MINUTES/SMS, DEFAULT=DATA, safe `fromName`);
  `PricingRule.category`. `BundleListRenderer` gains an optional category filter;
  `unmatchedValues` adds `{data_offers}`/`{minutes_offers}`/`{sms_offers}` (UNMATCHED
  flow only). `{bundle_list}` still lists everything.
- **Room v1→v2 migration** — first real migration. Nullable `category` column via a
  plain `ADD COLUMN category TEXT` (no default), which is exactly what a nullable,
  no-`@ColumnInfo`-default field produces, so runtime schema validation matches
  without the NOT-NULL-default quoting subtleties. Old rows → NULL → read as DEFAULT.
  Data-preserving; `fallbackToDestructiveMigration` stays absent.
- Export/import: `category` optional, codec **stays version 1** (older apps can
  still import); missing/unknown → DEFAULT.
- Rules editor: Data/Minutes/SMS segmented picker; each rule row shows its category.
- Templates action row: Save/Cancel/Reset are now real centered **Buttons** that
  grey when disabled (was TextButtons), per the client.
- Tests: per-category rendering + variable scoping; codec category round-trip,
  old-file default, unknown-name fallback.

### Still to do this session
- Merge the feature PR to main + cut **v1.1.0** (both reserved for a human by the
  safety gate; the token/permission file was never actually added).
- Commit the CI-generated **`app/schemas/…/2.json`** (from the room-schemas
  artifact) for the migration record; add an instrumented MigrationTestHelper test
  once it's in.

---

## 2026-07-16 — Messages-tab crash (round 3) + private-repo updater fix (feature branch)

Branch `fix/crash-and-private-updater` (integrates the delegated crash-fix branch
`fix/messages-tab-crash-regression-test`). Two device-reported issues.
**v1.0.0 was already published earlier today** (release run 29501071679), so this
is versioned **1.0.1 / versionCode 2** — the guard requires strictly-greater than
the published 1.

### The Messages-tab crash — did NOT reproduce on current code
Investigated by a dedicated sub-agent that built the project (local JDK 21 **and**
CI) and wrote the regression test three prior rounds lacked: a **Robolectric
Compose test** (`TemplatesScreenTest`, testDebug source set) that runs Compose's
real measure/layout pass off-device over the data the crash was suspected to need
— 5-rule `{bundle_list}`, non-default bodies, invalid tokens, multi-segment,
tab-switch, live typing, and the faithful nested-Scaffold arrangement. **6 tests,
all green; the current code does not crash.** Static analysis agreed (ViewModel
flow, `TemplateEngine`/`SmsSegments`, `tpl_segments` format, render path all
total; the nested Scaffold gets finite constraints → no infinity-height).

Deliberately **not** a third speculative layout rewrite. What shipped instead:
- `TemplatesScreen` split into a thin ViewModel wrapper + stateless
  `TemplatesContent` (identical behaviour) so the screen is testable off-device.
- **Defensive hardening:** `selectedTab.coerceIn(0, entries.lastIndex)` before
  indexing `TemplateType.entries[...]`, so a stale/corrupt `rememberSaveable`
  index can't throw `IndexOutOfBoundsException` and force-close on open.
- Corrected memory.md's inaccurate claim that Templates matches Home/Settings'
  shape — it does not (they are plain `Column`+`verticalScroll`; Templates is the
  lone nested-Scaffold screen). That misinformation likely misdirected rounds 1–2.

Most likely the user is on an **older APK** (the required one-time uninstall for
the `com.scopesms.autoreply → com.tricreta.scopesms` switch may not have been
done); the only alternative consistent with the evidence is an OEM-runtime crash
Robolectric can't see (Transsion/Tecno, Android 11). **Still needs a device to
close.** If 1.0.1 still crashes, next step is an on-device crash catcher / logcat
— and 1.0.1's working updater makes that iteration one-tap.

### The "Check for updates" error — root-caused and fixed
*"Update information is not available right now"* was a **real error**, not "no
updates": the repo is **private**, and the app fetched `update.json` from
`raw.githubusercontent.com` unauthenticated → **404** (confirmed live), so every
check failed. Fixed per the client's choice ("embed a read-only token"):
- Manifest now fetched via the **GitHub contents API** with a `Bearer` token
  (`Accept: application/vnd.github.raw` → body is the file). Token attached by a
  **host-scoped OkHttp network interceptor** (only on `api.github.com`), so it
  rides the manifest + asset-API hops but is dropped on the 302 to storage
  (avoids both a token leak and GitHub's "only one auth mechanism" 400).
- APK download uses the asset's **api.github.com asset URL** + `Accept:
  application/octet-stream` (the browser `releases/download` URL is a web endpoint
  a PAT cannot authenticate → 404 on a private repo). `release.yml` now resolves
  the asset id post-upload and writes that URL into update.json.
- Token injected from the **`UPDATE_READ_TOKEN`** secret into BuildConfig, never
  committed. Absent → empty → new `UpdateError.NotConfigured` ("automatic updates
  aren't set up for this build… install manually"), not a scary error.
- `UpdateResolver` reordered: **"nothing newer → UpToDate" is decided before**
  validating the install fields, so the seeded placeholder (versionCode 0, blank
  apkUrl) reads as "you have the latest version". +regression test.

### Still needs the agent
- Add the **`UPDATE_READ_TOKEN`** secret (done 14:39) — fine-grained PAT, single
  repo, **Contents: Read-only**.
- Install **1.0.1** (one-time uninstall of the current app the first time), then
  confirm the Messages tab opens and Check-for-updates behaves.
- Everything after 1.0.1 updates in place, one tap.

---

## 2026-07-16 — Permanent identity, signed releases & in-app updater (feature branch)

Branch `feature/tricreta-release-and-updates`. A client-driven pivot to a
permanent, self-updating private distribution. CI-verified (no full local JDK
this session; CI is the source of truth).

### Package migration → `com.tricreta.scopesms`
`applicationId` + `namespace` moved from `com.scopesms.autoreply` (406 refs / 110
files) via `git mv` of the source/test/androidTest trees + the Room schema dir
(`app/schemas/com.tricreta.scopesms.data.AppDatabase`), then a scoped
string-replace. `ArchitectureGuardTest` now asserts the base id after stripping
the debug suffix. **Consequence:** a one-time uninstall of the old
`com.scopesms.autoreply` app on the agent's phone (different package can't update
in place); after that, seamless forever.

### Versioning reset
`versionCode 3 / 0.9.0` → **`versionCode 1 / versionName 1.0.0`**. Safe: the new
package has zero installs, so there is nothing to be monotonic against yet.

### Debug/release split ("real apps, no debug apks")
Debug now `applicationIdSuffix ".debug"` + label **Scope SMS Debug**, default
debug key, never distributed. Removed the old "sign debug with the release key"
hack and the rolling **testing pre-release** from `build.yml` — `build.yml` is
verification-only (tests + lint + debug artifact), `contents: read`. Real APKs
come only from tags.

### Signing / CI secrets renamed to `ANDROID_*`
`build.gradle.kts` + `release.yml` now read `ANDROID_KEYSTORE_BASE64 /
ANDROID_KEYSTORE_PASSWORD / ANDROID_KEY_ALIAS / ANDROID_KEY_PASSWORD`. **Keystore
reused, not regenerated** (client's choice): alias stays `scope-sms`. README
documents backup + a from-scratch fallback (safe, since no signed release has
shipped under the new package).

### `release.yml` (tag `v*`)
tests + lint → tag↔versionName guard → **versionCode-strictly-increasing guard**
(reads `main:update.json`) → sign → `apksigner verify` → SHA-256 → publish Release
with `scope-sms-release-vX.Y.Z.apk` → generate `update.json` and commit it to
`main` (also attached to the Release as a fallback).

### In-app updater — rebuilt from browser-open to full download/verify/install
- Reads `update.json` (`BuildConfig.UPDATE_MANIFEST_URL`, raw main URL), compares
  by **versionCode**.
- OkHttp streaming download to `cacheDir/updates/` with incremental SHA-256 and %
  progress; cancellable; separate long-timeout client.
- Verifies SHA-256 + package name (`com.tricreta.scopesms`) + signing cert (with
  the `getPackageArchiveInfo` sourceDir gotcha; unreadable cert = soft proceed,
  readable mismatch = hard block); rejects+deletes invalid.
- Installs via `ACTION_VIEW` + `FileProvider` `content://` URI +
  `REQUEST_INSTALL_PACKAGES`; handles the "install unknown apps" grant
  (`canRequestPackageInstalls` → `ACTION_MANAGE_UNKNOWN_APP_SOURCES`, re-check on
  return). Never silent.
- New: `domain/update/{UpdateResolver,Sha256,SignatureMatch}` (pure, JVM-tested),
  `network/UpdateManifestClient`, `update/{AppUpdater,UpdateFlowState}`,
  `ui/update/{UpdateViewModel,UpdateSection}`, `res/xml/file_paths.xml`.
  Retired `network/UpdateChecker` + `domain/update/AppVersion`
  (`UpdateStatus`/`UpdateCheck`) + `UpdateCheckTest`.

### Still needs the agent
- Add the four `ANDROID_*` secrets, then tag **`v1.0.0`** for the first signed
  release + first real `update.json`.
- Uninstall the old `com.scopesms.autoreply` app once; install `1.0.0`.
- Device pass: the install/permission/cert path can only be proven on a handset.

---

## 2026-07-16 — Round-2 device-testing fixes + real versioning 🟡 (feature branch)

Eight items back from the agent's second real-device test. Fixed on a feature
branch; all unit tests green locally (JDK 21 + the installed SDK — the local
toolchain the previous session documented). Version set to **0.9.0** for this
round; **1.0.0 is held until the agent confirms** everything works, exactly as
the client asked.

### The Messages-tab crash (#1) — restructured, not re-patched
Round 1 applied the textbook `weight(1f)` fix for the "measured with an infinity
maximum height" crash, which is correct on paper — yet the tab still force-closed
on the agent's build (the build that also carries round-1's sample-send buttons,
so it definitely had the fix). Rather than re-patch the same shape, the Messages
(Templates) screen was rebuilt to the *exact* structure Home and Settings use —
the only scrolling screens proven not to crash on the agent's handset: the TabRow
is now a nested-`Scaffold` `topBar` and the body is a root-level `verticalScroll`
Scaffold body, with **no `weight` in the middle** for a nested `Column` measure to
get wrong. The data path was independently proven exception-free (render, segment
count, cache lookup are all pure/total), so this was the layout or nothing.

### The rest
- **#2 test-send** — removed the "send a real price-list / purchase-confirmation
  sample" buttons (a round-1 addition). They rendered from the live templates and
  reliably tripped the gateway; the agent already previews those exact messages on
  the Messages screen. The plain "Send test" is back to what it was.
- **#3 keep-running instructions** — the OEM "Keep Scope SMS running" section is
  now collapsible and **collapsed by default**, a tappable header expands it.
- **#4 toggles → Settings, latest replies → Home** — the two auto-reply toggles
  moved off Home into a new Settings "Automatic replies" section. Home now shows
  the **latest 3 replies** (amount, who, status, time), tap-through to the full
  log.
- **#5 theme** — new Settings "Appearance" section: System / Light / Dark,
  **System is the default**. Applied app-wide via `MainActivity` observing a new
  `SettingsRepository.themePreference`.
- **#6 activity filters** — the log's filter chips now sit on one horizontally
  **scrolling** row instead of wrapping onto several lines.
- **#7/#8 versioning, signing, updatable installs** — see below.

### Versioning & signing (the big one)
- **A permanent release keystore now exists.** Generated this session (RSA-2048,
  10 000-day validity, alias `scope-sms`). It is the app's permanent identity;
  whatever signs a build must sign every future update or the agent has to
  uninstall (losing prices/templates/history). Handed to the agent as a base64
  blob + password to load into four GitHub secrets — **never committed**.
- **CI now signs the testing (debug) APK with that same release key** when the
  secrets are present (`app/build.gradle.kts` debug `signingConfig`). Same
  certificate as the eventual release build ⇒ testing builds and v1.0.0 update
  over each other with no uninstall. One-time cost: the first signed build over
  the old *unsigned* test build needs a single uninstall (the memory predicted
  this).
- **`build.yml` now publishes a rolling `testing` GitHub pre-release** with the
  APK named `Scope-SMS-version-0.9.0.apk` — a direct, un-zipped, login-free
  download, which is what the agent asked for (workflow artifacts are always
  zipped). Gated on the signing secret being present; skips with a warning
  otherwise. `release.yml` naming aligned to the same `Scope-SMS-version-X.Y.Z`
  convention.

### Still needs the agent
- Install the new **0.9.0** testing build (one uninstall of the current app the
  first time — data on the current debug build is not preserved across the key
  switch), confirm the Messages tab opens and every fix behaves, **then** we tag
  **v1.0.0**.
- The four signing secrets must be added to GitHub before CI can publish the
  signed testing release (steps handed over this session).

---

## 2026-07-16 — Round-1 device-testing fixes ✅ (merged to main)

Six issues came back from the agent's real-device test; all fixed, plus a batch
of bugs a review of the integration work surfaced. All unit tests green locally
and pushed to `main`.

### The crash (#4) — reproduced and root-caused, not guessed
The Messages tab force-closed on open. Reproduced on an API 30 emulator in dark
mode. Cause: a scrollable (`LazyColumn` / `verticalScroll` Column) nested inside
another `Column` is measured with an **infinite** max height and throws
"measured with an infinity maximum height". The working screens escape it by
putting their scroll directly in the `Scaffold` body (bounded); Templates put it
under a `TabRow` inside a Column. **The same latent bug was in Activity and
Prices** — they only survived because an empty list/log never composes the
scrollable, so they'd have crashed the first time the agent had data. All three
now give the scroll area `weight(1f)`.

### The rest
- **#1 onboarding** — black-on-black in dark mode was a missing `Surface`
  (`LocalContentColor` defaults to black without one). Rebuilt: Surface-wrapped,
  animated between steps, centred, pulsing hero icon. **Gateway step removed** —
  API key/sender ID belong in Settings, not first-run.
- **#1b** — sender ID defaults to `SKYSCOPE_`; gateway setup only in Settings.
- **#6** — a delivered SMS was reported failed: a 200 whose message text
  mentioned the recipient number tripped the phone/number error classifier. A
  delivery signal (messageid, or response-code 200) is now authoritative and
  checked *before* text classification — the exact case the client warned about.
  Regression tests added.
- **#6b** — Settings can send a real matched/unmatched **sample** reply, rendered
  from the live templates + prices.
- **#3** — OEM "Open settings" now always lands somewhere, falling back to the
  app's own system settings page; always shown.
- **#5** — quiet ongoing "watching" notification (low importance, NOT a
  foreground service — constraint 6), re-posted on start and boot.
- **#2** — share/import price list as JSON (SAF; merges, skips duplicates).

### Review-found bugs also landed
sender ID was stored per job but the gateway ignored it (offline-queued replies
went out under the wrong ID); a send cancelled mid-flight never burned an attempt
(flaky-2G re-send-forever); `drain()` stranded jobs past 100; undecryptable
credentials never cleared (app insisted it was set up while every send failed);
the dashboard's "today" froze for the ViewModel's life; a template edit typed
during a save was dropped; `1,000` was rejected as having cents; `KshAmount.parse`
lacked the overflow guard its sibling had.

---

## 2026-07-16 — Integration + Phases 7, 10, 11: the app exists ✅

**Branch:** `integration/all-phases` → merged to `main` (8d86134).
**CI:** ✅ green — **276 unit tests + 6 instrumented tests on API 30 and API 36**,
0 failures, 0 skipped. Counts read out of the downloaded reports, not off the tick.

### The state this session found
`main` held **Phase 0 only**. All six phase branches were unmerged, and they did
not compile together. Phase 7 had never been built — the session that owned it
correctly declined, because it integrates layers that were still moving.

### Integration — the collisions no session could fix from inside its own worktree
- **Two money types.** Phase 2's `Money`/`amountCents: Long` vs Phase 3/4's
  `KshAmount`. Both chose integer cents, so the *data* agreed; they just would not
  compile. **KshAmount wins** — a value class (free at runtime on the hot path)
  whose `format()` already drops a trailing `.00`. `Money` deleted.
- **Three "the app's database".** Phases 5b, 3/4 and 8 each wrote one. Consolidated
  into `AppDatabase`, whose doc had already reserved the slots. Two databases
  would mean no transaction could span a queue job and its log row.
- `QueueGraph` absorbed into `AppContainer`, as its own doc invited.

### The receive path now exists
Every session left it as a comment because it needs Phases 2, 3, 4, 6 and 5b at
once. `PaymentPlanner` (pure, JVM-testable) does classify → toggles → render;
`PaymentPipeline` logs, then enqueues. **Log first**: die between the two and the
agent sees a `QUEUED` reply that never sends — visible and diagnosable — rather
than a customer texted with no trace of why. The log insert carries the duplicate
guard, so an OEM redelivery stops before it can text anyone twice.

### Whole-shilling amounts, per the client
`parseWholeShillings()` rejects decimals at **entry**, so no rule can hold cents
and `format()` can be trusted to render no decimal point. Deliberately asymmetric
with `parse()`: what the agent types is constrained, what a customer *sends* is
not — Ksh 20.50 keeps its cents and correctly matches nothing, rather than being
rounded into the Ksh 20 bundle and confirming a purchase that never happened.

### Built
- **Phase 7 — the whole UI.** Onboarding (permissions → SIM → gateway + test send
  → battery), Home with both toggles visible at a glance and the four stat tiles,
  Rules, Templates (two tabs, live preview through the *real* engine, segment
  count), Activity Log (search + filters), Settings (SIM, gateway, battery, OEM
  guidance, version, update check). Navigation hand-rolled — five flat screens,
  no arguments; a nav library would add a route DSL to express `current = RULES`.
- **Phase 10 — emulator matrix** on API 30 (minSdk, what the target handsets run)
  and 36 (targetSdk). ON despite being optional in the plan, for one reason: the
  Keystore holds the agent's API key, has no JVM equivalent, and breaks on exactly
  this market's OEMs. `SmokeTest` round-trips a real secret through the real
  Keystore. `continue-on-error` — emulator jobs flake for reasons unrelated to the
  app, and a red tick nobody trusts is worse than none.
- **Phase 11 — tag-triggered signed release.** `release.yml`: test → sign → verify
  with apksigner → attach to a GitHub Release. Fails if the tag and `versionName`
  disagree. In-app update check (pure comparator + a thin GitHub client), on
  demand only — the agent pays for that data.
- **Gateway credentials encrypted** — Android Keystore AES/GCM over DataStore.
  Closes open decision 1. Verified on real emulators, not just unit-tested.
- **The queue reports outcomes to the activity log.** Phase 5 had no way to; a
  failed reply updated a job row the agent never sees.

### Found and fixed
- 🔴 **`WorkManager.enqueue` from `Application.onCreate`** — my own bug, two at
  once: a **disk write on the main thread of every process start** (including the
  headless SMS wakeups constraint 5 exists to protect), and it **throws** if
  WorkManager's initializer hasn't run, which is a dead app at launch *and a dead
  receiver*. Caught by the Robolectric suite.
- 🔴 **`Icons.Default.*` needs `material-icons-core` explicitly** — Material3
  doesn't bring it transitively.
- 🔴 **There is a working local compiler.** No Android Studio, but a JDK 21 + the
  already-installed SDK builds and tests this project fine. Every prior session
  believed CI was the only compiler and paid a 5–10 min round trip per typo. This
  is now the top gotcha in `memory.md`.

### 🔴 Still open — needs you or the agent, not a session
- **Still exactly one real M-Pesa sample.** Unchanged since Phase 2 and still the
  highest-value ask on the project. The parser is green against ~30 *constructed*
  variants; that means "no known case is broken", not "the parser works".
- **No signing key exists.** `release.yml` is written but unexercised — key custody
  is the client's decision, deliberately handed back rather than assumed. Testing
  APK is debug-signed. See README → "Cutting a release".
- **Toggle defaults unconfirmed** (unmatched=ON, matched=OFF). Safe to ship: a
  fresh install has no rules, so nothing can send regardless.
- **targetSdk stays 36** — now a decision, not a deferral. No Android 17 device to
  test against, and targetSdk opts into exactly the runtime behaviour (background
  execution, broadcast delivery) that sits between "customer pays" and "customer
  gets a reply".
- **Real-device checks**: dual-SIM, the `^M-?PESA$` sender rule, the Phase 7
  click-through, Phase 9's soak/reboot/airplane-mode, and the Transsion autostart
  deep links. Steps in `README.md`.

---

## 2026-07-16 — Phase 9: Reliability hardening 🟡 code done, exit criteria unmet

**Branch:** `feature/phase-9-reliability-hardening` (cut from
`feature/phase-1-permissions-sim`, **not** `main` — Phase 9 uses Phase 1's
classes and `main` has only Phase 0). **Merge Phase 1 first.**
**CI:** ✅ green — run 29452472972, 64 tests / 0 failures, debug APK built.

### Built
- **Boot health check** — `BootCompletedReceiver` → `ReliabilityInspector` →
  pure `domain/reliability/ReliabilityCheck`. Detects a revoked permission, no
  SIM, a watched slot with no SIM in it, and a missing battery exemption; warns
  the agent via a `health` notification channel. Silent when healthy.
- **OEM autostart guidance** — per-OEM instructions (Transsion / Xiaomi /
  ColorOS / Vivo / Huawei / Samsung / generic) plus best-effort deep links,
  probed at runtime and discarded when absent.
- **UI** — stateless composables only (`OemGuidanceSection`,
  `ReliabilityIssueCard`) for Phase 7 to place. No screen, no ViewModel.
- **29 JVM tests**, no Robolectric (CI stays on JDK 17).

### 🔴 Not done — Phase 9's exit criteria are all real-device work
The 24h Transsion soak test, the reboot pass, and the airplane-mode queue test
have **not been run**. Green CI here proves the code compiles and the pure logic
is right — the half that was never in doubt. The airplane-mode criterion is also
blocked on Phase 5b (no outbound queue exists yet). **Phase 9 is not done.**

### Found
- **The plan's boot-check tasks were already solved by Phase 1.** BUILD-PLAN asks
  the receiver to re-validate saved subscription IDs and saved exemption status;
  Phase 1 persists neither, on purpose, citing this same Phase 9 line. Checks the
  equivalent live conditions instead — chiefly "the watched slot has no SIM in
  it", where `SimFilter` correctly drops every message and the app looks healthy
  while replying to nobody.
- **🔴 Transsion component names are unverified** and it's the primary market. The
  ~12 repos carrying them largely copy each other, and the most-used library has
  no Transsion entry at all. Needs the agent's real Tecno/Infinix to settle. The
  written instructions are the contract precisely because of this.
- **A bug caught by its own test:** `none {}` is vacuously true on an empty set,
  so an empty SIM selection rendered *"You told Scope SMS to watch , but those
  slots are empty."* Red on the first CI run, then fixed.
- **🔴 Parallel sessions share one working directory and one git HEAD.** Another
  agent's `git checkout -b` moved this session's branch mid-work; Phase 9's
  uncommitted files ended up on Phase 2's branch. Nothing lost — Phase 2's tree
  was handed back untouched and Phase 9 moved to a `git worktree`. **Every
  session should use its own worktree**; see memory.md.

### Raised for the client / next sessions
- Someone must install a CI APK on the agent's **real Tecno/Infinix** and report
  which autostart screen opens (or that none does).
- **Phase 8:** use a *different* notification channel for send failures — muting
  "a reply failed" must not mute "the app has stopped working".
- **Phase 2** owns "malformed SMS: log and skip, never crash" (a Phase 9 bullet)
  — left alone to avoid colliding with that live session.

---

## 2026-07-15 — Phase 0: Repository, scaffolding & CI pipeline ✅

**Branch:** `feature/phase-0-scaffold-ci` → merged to `main`
**State:** Phase 0 complete. All exit criteria met.

First session on the project. Repo was empty; `main` seeded with the existing
governance docs, all Phase 0 work done on a feature branch per the no-direct-
commits rule.

### Built
- **Android project** — Kotlin + Compose, `minSdk 30`, `compileSdk 37`,
  `targetSdk 36`, JDK 17, applicationId `com.scopesms.autoreply`.
- **Package structure** per BUILD-PLAN: `data/ domain/ telephony/ network/
  queue/ ui/ di/`. Each has a README naming its owning phase and constraints,
  since phases are being built by parallel sessions.
- **GitHub Actions CI** (`.github/workflows/build.yml`) — the project's only
  compiler. On push to any branch + PRs to main: validate wrapper → unit tests
  → debug APK → upload artifact. Test report uploaded on failure.
- **Brand theme + launcher icons** carried over from the local reference
  material, with the reference's inverted dark-mode bug fixed rather than
  copied.
- **Governance files** — `memory.md`, `changelog.md`, `README.md`.
- **`ArchitectureGuardTest`** — fails the build if `SEND_SMS` is declared or
  `SmsManager` referenced. Replaces the trivially-passing test the plan allows.

### Exit criteria — met
| Criterion | Result |
| --- | --- |
| Push triggers CI | ✅ |
| Unit tests run and pass | ✅ 4 tests, 0 failures, 0 skipped |
| Debug APK downloadable from artifacts | ✅ `scope-sms-debug-2-7c34f10…` (10.5 MB) |

### Verified, not assumed
- **The guard test actually fails when violated.** A scratch branch added
  `SEND_SMS`, CI went red on exactly that assertion, branch deleted. A guard
  that has never failed is not known to guard.
- **Wrapper jar is authentic** — sha256 matched Gradle's published checksum;
  CI re-validates it every run.
- **Tests ran non-vacuously** — a fixture assertion proves the manifest and
  sources are actually being read.

### Fixed during the session
- **CI red on first run:** current AndroidX requires `compileSdk 37` since
  Android 17 shipped. Bumped compileSdk; deliberately left `targetSdk` at 36
  (see memory.md — flagged deviation, Phase 10 owns it).

### Decisions (full rationale in `memory.md`)
- **AGP 9 has built-in Kotlin** — `org.jetbrains.kotlin.android` must NOT be
  applied; it hard-fails. Kotlin pinned to **2.2.10** (AGP 9.2.1's own bundled
  compiler) rather than the newer 2.4.10, because that's the combination Google
  actually tests and nothing here needs newer language features.
- **AGP 9.2.1**, not 9.2.0 (known R8 bug) and not 9.3.0 (days old).
- `allowBackup=false`; `queue/` kept top-level; no permissions in the manifest
  yet (Phase 1 owns them); DI deliberately undecided.

### Raised for the client / next sessions
- 🔴 **`security-crypto` is deprecated** and has OEM keyset-corruption crashes
  on this app's exact target devices. Blocks the Phase 5/7 API-key storage
  decision.
- 🔴 **Only one real M-Pesa sample message exists.** BUILD-PLAN Phase 2 needs
  5–10 more redacted samples from the agent — a hard blocker for the parser.
- 🔴 **`01-UI-DESIGN-PROMPT.md` does not exist in the repo**, though both
  governance docs reference it. Phase 7 has no UI spec beyond the out-of-date
  AI Studio reference.
- ⚠️ **Robolectric needs JDK 21** against SDK 36+; CI is on 17. Will bite
  whichever phase adds it.
- ⚠️ Launcher icons are legacy flat PNGs — no adaptive icon layers.
