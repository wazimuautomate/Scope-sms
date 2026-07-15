# Build Plan — M-Pesa Bundle Auto-Reply App (v2)

> **This supersedes the earlier plan.** A client-driven architecture pivot
> changed how sending works, added a second notification flow, and
> confirmed the build/CI setup. If a session already made progress on
> Phase 0 under the old plan, check `memory.md` for exactly what exists
> before continuing — some of it (anything assuming `SmsManager` sending or
> pure offline operation) needs to change, not just extend.

This plan assumes: (1) Stitch/AI Studio UI designs exist (Settings/Templates
sections need a refresh — see note at the end of `CLAUDE.md`), (2)
`CLAUDE.md` is in the repo root, (3) the GitHub repo exists and is cloned,
(4) **all builds run via GitHub Actions CI** — there is no local Android
Studio in this workflow.

**How to use this doc:** open a new Claude session per phase (or continue in
one session across phases if context allows). At the start of every session,
Claude must read `CLAUDE.md`, `memory.md`, and `changelog.md` first. At the
end of every session — win, partial, or blocked — `memory.md` and
`changelog.md` must be updated before the session ends.

**Git flow:** every phase = one `feature/phase-N-<slug>` branch off `main`.
Merge to `main` only when CI is green and that phase's exit criteria are
met. No direct commits to `main`.

---

## Phase 0 — Repository, Scaffolding & CI Pipeline
**Goal:** an empty, CI-buildable Android project with governance files and a
working GitHub Actions pipeline — this last part is not optional given
there's no local Android Studio.
- Kotlin + Jetpack Compose project, `minSdk 30`, `targetSdk` = latest stable.
- Package structure: `data/` (Room, DataStore/EncryptedPrefs), `domain/`
  (parsing, in-memory rule cache, matching, decision logic), `telephony/`
  (SMS receiver + SIM identification — **ingestion only**), `network/`
  (SCOPE SMS API client, response/error models), `queue/` (outbound send job
  table + WorkManager worker — may live under `data/`+`domain/` if you
  prefer fewer top-level packages, just be consistent), `ui/` (Compose
  screens), `di/`.
- Create `CLAUDE.md`, `memory.md`, `changelog.md`, `README.md` at repo root
  (if not already present from earlier work).
- `.gitignore` (must exclude any local `local.properties`, keystores,
  `*.jks`, and anything containing the SMS gateway API key).
- **GitHub Actions workflow** (`.github/workflows/build.yml` or similar):
  - Trigger on push to any branch + PRs to `main`: run `./gradlew test` +
    `./gradlew assembleDebug`, upload the debug APK as a workflow artifact.
  - Trigger on tag push (e.g. `v*`): run a release build, sign it using a
    keystore stored as a base64-encoded GitHub Secret + passwords as
    secrets, and either upload as an artifact or attach to a GitHub Release
    (decide which in Phase 11 — a debug-signed artifact per push is enough
    for this phase).
- **Exit criteria:** pushing to a branch triggers CI, unit tests run (even
  if there are none yet, the step must exist and pass trivially), and a
  debug APK is downloadable from the Actions run's artifacts. This is the
  gate for every subsequent phase's "did it actually build" check.

## Phase 1 — Permissions & SIM Identification (reading only)
**Goal:** reliable permission flow + SIM identification for the *ingestion*
side. No SEND_SMS anywhere in this phase.
- Runtime permission flow for `RECEIVE_SMS`, `READ_SMS`, `READ_PHONE_STATE`,
  `POST_NOTIFICATIONS` (API 33+), and `INTERNET` (declared in manifest, no
  runtime prompt needed for it, but call out in onboarding copy that the app
  needs connectivity to send replies).
- `SubscriptionManager` wrapper: list active SIMs (slot index, subscription
  ID, carrier name, display number if available).
- Persist user's SIM choice (SIM 1 / SIM 2 / Both) via DataStore.
- Battery-optimization exemption request flow, with a Settings status
  indicator — still needed, since the ingestion receiver and the queue
  worker both need to survive OEM background killing.
- **Exit criteria:** CI-built APK installed on a real dual-SIM device
  correctly lists both SIMs and persists the chosen filter across app
  restarts and reboot.

## Phase 2 — SMS Ingestion & M-Pesa Parser
**Goal:** turn a raw till-confirmation M-Pesa SMS into structured data,
fast and synchronously.
- Manifest-registered `BroadcastReceiver` for `SMS_RECEIVED_ACTION`, using
  `goAsync()`.
- Read `SLOT_INDEX`/`SUBSCRIPTION_INDEX` extras; drop immediately if not
  from the selected SIM(s).
- Parser built against the **till-confirmation format** (see the example in
  `CLAUDE.md` — note the irregular spacing, e.g. `"...PMKsh20.00..."`).
  Extract: M-Pesa code, date, time, amount, sender phone, sender name, new
  balance, transaction cost.
- **Get at least 5–10 more real (redacted) sample messages from the agent**
  before finalizing the regex — one sample is not enough to trust variant
  handling (long names, missing balance line, different cost values, etc).
- Explicitly ignore non-"received" M-Pesa SMS types (sent, withdrawal,
  balance check, airtime purchase).
- This phase must not touch the network or Room on the hot path — parsing
  itself should be pure/synchronous.
- **Exit criteria:** unit tests (run in CI) pass against all collected real
  sample messages, including edge cases. No network or DB calls inside the
  receiver's parse step.

## Phase 3 — Rules Engine + In-Memory Cache
**Goal:** amount → bundle mapping, fast enough for burst traffic.
- Room entity: `PricingRule(id, amount, bundleDescription, isActive)`.
- CRUD DAO + repository (source of truth).
- **In-memory cache** (e.g. a singleton `Map<Amount, PricingRule>` held in
  the app process) that the receiver's decision path reads from directly —
  never a fresh Room query per incoming SMS. Cache is rebuilt/updated
  whenever a rule is added/edited/deleted via the UI, and loaded on app/
  process start.
- Matching function: given a parsed amount, return the matching rule from
  the cache or `null`.
- Empty state: no rules seeded by default — app should prompt the agent to
  add bundle prices before doing anything, not assume defaults.
- **Exit criteria:** unit tests for exact-match/no-match/duplicate-amount;
  a test asserting cache and Room stay in sync after CRUD operations; a
  benchmark-style test confirming lookup is O(1)-ish (map access), not a
  DB round trip.

## Phase 4 — Two Message Template Types
**Goal:** personalized reply text for both flows, independently editable.
- Room entity: `MessageTemplate(id, type[UNMATCHED | MATCHED], body,
  isDefault)`.
- **Unmatched-amount template** variables: `{name}`, `{amount}`, `{phone}`,
  `{bundle_list}` (renders the active price list).
- **Matched-amount template** variables: `{name}`, `{package}` (bundle
  description from the matched rule), `{amount}`, `{phone}`.
- Both loaded into the in-memory cache alongside rules (Phase 3) for the
  same speed reason.
- Variable substitution engine shared between both types.
- SMS segment-length calculator/warning (160/153 char split) for both.
- **Exit criteria:** unit tests confirming correct substitution for both
  template types, and no crash on a missing variable (e.g. sender name
  absent from SMS body).

## Phase 5 — SCOPE SMS Gateway Client
**Goal:** a tested HTTP client for the gateway, decoupled from anything
telephony-related.
- HTTP client (Retrofit/OkHttp or Ktor — pick one, note the choice in
  `memory.md`) targeting `https://sms.blazetechscope.com/v1/`.
- `sendSms(phone, message, senderId, apiKey): Result<SendResult>` wrapping
  `POST /sendsms`. Model the success response (`response-code`, `mobile`,
  `messageid`, `networkid`) and documented error shapes.
- Map HTTP status codes (400/401/403/429/500) and documented error message
  bodies (invalid API key, insufficient balance, invalid phone, invalid/
  unregistered sender ID) to distinct typed failure reasons — not a generic
  "send failed."
- Timeout: 30–60s per the docs' own recommendation; this runs inside a
  background worker, not the receiver, so a slow call is fine as long as it
  doesn't block ingestion (Phase 5b enforces that).
- API key + sender ID read from encrypted local storage (Settings, Phase
  6/7) — never hardcoded.
- Optional in this phase: `smsstatus` client for delivery-status lookups —
  nice-to-have, not a blocker.
- **Exit criteria:** unit tests against a mocked HTTP layer covering
  success, each documented error code, and a timeout/no-connectivity case.

## Phase 5b — Outbound Queue & Burst-Speed Architecture (critical phase)
**Goal:** guarantee the client's stated worst case — ~10 incoming SMS in
1–3 seconds — never drops a decision or delays ingestion.
- Room entity: `OutboundJob(id, transactionCode, phone, message, senderId,
  status[PENDING|SENDING|SENT|FAILED], attemptCount, createdAt,
  lastError)`.
- Receiver's decision path (Phase 2+3+4) writes a job row and returns
  immediately — it does **not** call the network client directly.
- A `WorkManager` worker (expedited work request, or a `CoroutineWorker`
  triggered on job insert) drains pending jobs, calls the Phase 5 client,
  updates job status, retries with backoff on transient failures
  (network error, 429, 500), and gives up with a clearly logged reason after
  a bounded number of attempts (surfaced in the activity log, not silently
  dropped).
- **Duplicate guard:** dedupe on `transactionCode` — some OEMs redeliver
  `SMS_RECEIVED` more than once; never send two replies for the same
  M-Pesa transaction.
- **Offline-at-arrival handling:** if there's no connectivity when a job is
  created, it stays `PENDING` and is retried once connectivity returns
  (e.g. via a network-available `WorkManager` constraint) — never dropped.
- **Exit criteria:** an explicit test that simulates ~10 `SMS_RECEIVED`
  events within 1–3 seconds (varying amounts, some matched, some not) and
  asserts every one produces exactly one correctly-templated queued job,
  with no drops, no duplicate sends, and no blocking of the next event. This
  test is the single most important exit criterion in the whole plan — do
  not consider this phase done without it passing in CI.

## Phase 6 — Independent Notification Toggles
**Goal:** the agent can run unmatched-only, matched-only, both, or neither.
- DataStore settings: `unmatchedReplyEnabled: Boolean`,
  `matchedReplyEnabled: Boolean`, both defaulting to a sensible starting
  state — **confirm the intended default with the agent** (a reasonable
  starting recommendation is unmatched=ON since that's the original pain
  point, matched=OFF since it's the higher-volume, ban-risk flow — but this
  is a product decision for the client to confirm, not something to assume
  silently).
- Decision path (Phase 2–5b) checks the relevant toggle before enqueueing a
  job — a matched amount with `matchedReplyEnabled=false` still gets logged
  in the activity log (Phase 8) as "matched, notification off," just no SMS
  sent.
- **Exit criteria:** unit tests confirming all four toggle-state
  combinations produce the correct enqueue/no-enqueue behavior.

## Phase 7 — Compose UI Implementation
**Goal:** implement the screens, updated for the gateway + two-flow model.
- Onboarding: unchanged permission/SIM steps, plus a new step for entering
  the SMS gateway API key + sender ID (with a "send test SMS" action to
  confirm before finishing setup).
- Home/Dashboard: master monitoring status **plus** the two independent
  toggles (Unmatched auto-reply, Matched purchase-confirmation), visible at
  a glance, not buried in Settings.
- Rules screen: unchanged from original design.
- Templates screen: two sections/tabs — Unmatched template, Matched
  template — each with its own variable-chip set and preview.
- Activity Log: status values now include at minimum `MATCHED_NOTIFIED`,
  `MATCHED_SILENT` (toggle off), `UNMATCHED_REPLIED`, `UNMATCHED_SILENT`,
  `SEND_FAILED` (with the gateway's error reason shown).
- Settings: SIM selection (unchanged) + new "SMS Gateway" section (API key,
  sender ID, test-send button, masked key display).
- Keep the calm/minimal motion language from the original design brief.
- **Exit criteria:** full manual click-through on a real device (installed
  from the CI artifact) matches the design intent for both light and dark
  mode, including both new toggle states and both template types.

## Phase 8 — Activity Log & Dashboard Stats
**Goal:** every processed message is logged with enough detail to diagnose
issues without re-reading raw SMS.
- Room entity: `ActivityLogEntry(id, timestamp, transactionCode, senderName,
  senderPhone, amount, matchType[MATCHED|UNMATCHED], notifyStatus[SENT|
  SILENT|FAILED], replyBody, gatewayMessageId, failureReason)`.
- Dashboard stat tiles: today's messages processed, matched-notified sent,
  unmatched-replied sent, failed sends (should be visually distinct/urgent
  if non-zero — a failed send is money-adjacent and the agent should notice).
- Log list with search/filter by date/status/flow type.
- **Exit criteria:** stats and log entries match real on-device SMS traffic
  and real gateway responses during a manual test session.

## Phase 9 — Reliability Hardening (Kenyan device + network reality)
**Goal:** survive OEM background aggressiveness and real-world
connectivity gaps, not just a clean test run.
- `BOOT_COMPLETED` receiver: re-verify battery-exemption status and that
  saved SIM subscription IDs are still valid after reboot (dual-SIM devices
  can reorder subscription IDs on some OEMs).
- In-app guidance for OEM "autostart"/"protected apps" settings
  (Tecno/Infinix/itel via HiOS/XOS, Xiaomi MIUI, Oppo ColorOS) — no code fix
  solves this, only user settings + clear instructions.
- Confirm the Phase 5b offline-queue-and-retry behavior survives a real
  connectivity gap (airplane mode toggle test), not just a mocked one.
- Malformed/unparseable SMS: log and skip, never crash.
- **Exit criteria:** app survives a 24-hour idle soak test on at least one
  Transsion device with screen off; correctly resumes after reboot; queued
  jobs created during a simulated connectivity gap are sent once connectivity
  returns.

## Phase 10 — Cross-Version Testing (CI-first approach)
**Goal:** confirm the "Android 11 to latest" requirement without relying on
local Android Studio.
- Unit test suite (parser, rules, templates, gateway client, queue logic)
  runs on every CI push — this is the primary safety net.
- Optional: `android-emulator-runner` GitHub Action for instrumented tests
  on a small API matrix (e.g. 30, 34) — note this is slower and can be
  deferred if CI minutes/time become a constraint; flag the decision in
  `memory.md` either way.
- Primary real-device validation: install the CI-built artifact APK
  manually on whatever real devices are available (ideally spanning Android
  11/12 low-end and Android 14/15), spot-checking ingestion, matching, and
  gateway sending end to end.
- **Exit criteria:** documented pass/fail matrix in `memory.md`; any
  version-specific workaround noted there, not fixed silently.

## Phase 11 — Release Packaging & Distribution via CI
**Goal:** a signed, installable APK produced entirely by GitHub Actions,
downloadable without any local build step.
- Tag-triggered release workflow (e.g. push tag `v1.0.0`): build, run tests,
  sign with the release keystore (stored as GitHub Secrets — base64
  keystore + store/key passwords), attach the signed APK to a **GitHub
  Release**.
- Versioning: semantic version + build number, shown in Settings.
- Minimal in-app "check for updates": query the GitHub Releases API for the
  latest tag, compare to current version, prompt with a download link if
  newer — no auto-install.
- Document install steps for the agent: "allow install from unknown
  sources" + where to download from.
- **Exit criteria:** a tagged commit produces a signed APK attached to a
  GitHub Release, installable on a device with zero prior dev setup.

## Phase 12 — Future / Not MVP (flag only, don't build yet)
- Automated delivery-status polling via `smsstatus` for the activity log.
- SMS balance monitoring/low-balance alert (would need a gateway balance
  endpoint if one exists — not in the docs provided; confirm with the
  client if wanted).
- Multi-agent distribution / remote config, if this ever moves from a
  single-agent tool to something shared with other Bingwa agents.
- Path to Play Store default-SMS-handler compliance, if broader distribution
  ever justifies that scope increase.