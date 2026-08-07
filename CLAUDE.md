# CLAUDE.md
> Read this file — plus `memory.md` and `changelog.md` — before doing
> anything in this repository. This file is the project's operating
> contract. It does not change per-session; `memory.md` and `changelog.md` do.
>
> **v2 — updated after a client-driven architecture pivot.** If you are
> resuming mid-Phase-0 work from before this update, re-read this whole file
> before writing more code — several earlier assumptions (offline-only,
> on-device SmsManager sending) are no longer correct. Check `memory.md` for
> exactly what was already built and whether it needs to change.

## What this app is
A native Android utility for a single Bingwa Sokoni (Safaricom reseller)
agent. It watches incoming M-Pesa "money received" SMS on a chosen SIM and,
for each payment, can independently:
- **Unmatched-amount flow:** if the amount matches no bundle price, send the
  customer a personalized SMS with the correct prices (replaces a manual
  phone call).
- **Matched-amount flow:** if the amount matches a known bundle price, send
  the customer a personalized purchase-confirmation SMS (e.g. "Thank you
  {name} for purchasing {package}...").
- **Off-window flow:** if the amount matches a bundle price, but the bundle
  is only purchasable during a restricted time-of-day window (Safaricom
  restricts some offers this way, e.g. "1GB 1Hr" @ Ksh19 buyable 4:00 PM–
  10:59 PM only) and the payment arrived outside it, send the customer a
  reassurance SMS naming the correct window rather than an instant
  confirmation. Fires only for bundles the agent has explicitly restricted
  (`domain/rules/PurchaseWindow`) — every bundle defaults to all-day/every-day,
  so this flow is structurally silent until the agent sets a window.

All three flows are **independently toggled on/off**. The client's stated
reason for the first two: on a busy day the agent gets many matched
purchases, and sending a notification for every single one — on top of
unmatched replies — raises the volume sent under one sender ID, which is a
real deliverability/ban risk with SMS gateways. Being able to run any
combination of the three is a deliberate operational control, not a
nice-to-have.

All three flows send via one of the client's registered **SMS gateways**
(BlazeTech or HostPinnacle, agent-selectable — see "SMS Gateway Integration"
below) using a registered **sender ID** — not the device's SIM card.

Full screen-by-screen UI spec: `01-UI-DESIGN-PROMPT.md` (Settings and
Templates sections are now out of date vs. this file — see note at the
bottom of this doc).
Full phased implementation plan: `02-BUILD-PLAN.md`.

## Non-negotiable constraints
1. **`minSdk = 30` (Android 11), target latest stable SDK.** Unchanged.
   Every feature must be verified to work on the floor, not just the
   ceiling.
2. **Reading is local and offline; sending is online via SMS gateway.**
   The app still reads M-Pesa SMS entirely on-device (no network call in
   that path). But it is **no longer offline-first overall** — outbound
   replies require internet connectivity to reach whichever gateway is
   currently active (`https://sms.blazetechscope.com/v1/` for BlazeTech,
   `https://smsportal.hostpinnacle.co.ke/SMSApi/` for HostPinnacle — see "SMS
   Gateway Integration" below). `INTERNET` permission is required. Design for
   the case where the phone has no data connection *at the moment* a payment
   SMS arrives: the decision (matched/unmatched, which template) must still
   be made instantly and durably queued for sending — never silently
   dropped, never blocking on network inside the receiver.
3. **No `SEND_SMS` permission, no `SmsManager` sending.** All outbound
   messages go through the SCOPE SMS API using a sender ID. This removes
   the entire "send via correct SIM subscription" problem from earlier
   plans. If you find yourself reaching for `SmsManager` to send a reply,
   stop — that's the old architecture.
4. **Dual-SIM logic now applies only to *reading*.** The app still needs to
   know which SIM slot an incoming M-Pesa SMS arrived on, to filter out the
   agent's personal SIM traffic. It does **not** need to route outbound
   sends to a specific SIM anymore — outbound is always via the HTTP
   gateway.
5. **Speed is a hard constraint: bursts of ~10 SMS in 1–3 seconds must all
   be matched correctly with no drops and no perceptible delay.** This
   drives a specific architecture split:
   - **Detect-and-decide path** (receiver → parse → match against rules →
     pick template) must be synchronous, in-process, and backed by an
     **in-memory cache** of rules and templates (not a fresh Room/DB query
     per SMS). Keep this path free of any I/O that can block — no network,
     no disk write, before the decision is made.
   - **Send path** (HTTP call to the SMS gateway) must be fully decoupled
     from the receiver via a durable queue (Room-backed job table +
     WorkManager, or equivalent) so that a slow or failed network call
     never delays processing of the next incoming SMS.
   - The in-memory cache must be kept in sync with Room whenever rules or
     templates are edited in the UI (no stale-cache bugs).
6. **No persistent foreground service for the *detection* path.** Core SMS
   ingestion still runs from a manifest-registered `BroadcastReceiver` on
   `SMS_RECEIVED_ACTION` using `goAsync()`, per the original design. The
   **outbound queue**, however, is legitimately background network work —
   use `WorkManager` (expedited work / a periodic or event-triggered worker)
   for it rather than inventing a custom foreground service.
7. **SMS gateway credentials are secrets.** The API key and sender ID must
   never be hardcoded in source or committed to the repo. Store them in
   `EncryptedSharedPreferences` or DataStore with encryption, entered once by
   the agent in Settings. Never log the raw API key.
8. **Distribution is a direct-install APK via GitHub Actions CI, not Play
   Store and not local Android Studio builds.** The developer does not have
   Android Studio installed locally — **all builds happen in CI.** Every
   phase's "done" definition includes "CI build succeeds," not "it built on
   my machine." Play Store is still out of scope: the app still requires
   `READ_SMS`/`RECEIVE_SMS` for the ingestion side, which triggers Play's
   default-SMS-handler policy requirement regardless of how sending works.
9. **This is a real system an agent depends on for their livelihood.**
   Prioritize correctness and failure handling — what happens when the
   gateway is unreachable, rate-limits, or returns an error — over speed of
   delivery. No flow is "done" without its failure path handled.

## Architecture
- **Language/UI:** Kotlin, Jetpack Compose.
- **Persistence:** Room (rules — now carrying an optional purchase-window per
  bundle, templates — now three template *types*, outbound send-queue/job
  table — each job remembers which gateway provider it was created under,
  activity log — same), DataStore/EncryptedSharedPreferences (settings: SIM
  selection, **per-gateway** credentials (API key for BlazeTech; username +
  password for HostPinnacle) + sender ID, and which gateway is active,
  the three independent notification toggles, battery-exemption status,
  onboarding state).
- **Ingestion (sync, local):** `BroadcastReceiver` (SMS_RECEIVED) → SIM
  filter → parser (`domain/`) → in-memory rule lookup (`domain/`) → decide
  matched/unmatched → check the relevant toggle → if enabled, render
  template and enqueue a send job. This entire path must complete in
  milliseconds and touch no network.
- **Sending (async, networked):** `network/` module — HTTP client for the
  SCOPE SMS API (`sendsms`, optionally `smsstatus`), a queue worker
  (WorkManager) that drains pending send jobs from Room with retry/backoff
  on failure (respect the 100 req/min rate limit; handle 401/403/429/500 per
  the gateway's documented error responses), and writes the outcome back to
  the activity log.
- **DI:** keep it simple — manual DI or Hilt, whichever is already
  established in the repo by the time you read this (check `memory.md`).
- Package layout: see Phase 0 in `02-BUILD-PLAN.md`. The `telephony/`
  package is now ingestion-only (no sending); `network/` is new and owns all
  gateway communication.

## SMS Gateway Integration — two gateways, dropdown-selectable

The app supports **two independently-selectable SMS gateways**: BlazeTech
(the original "SCOPE SMS API" integration, live in production) and
HostPinnacle (added later, at the client's request, to switch providers
*without* removing BlazeTech — an agent depends on BlazeTech for their
livelihood, so it must keep working exactly as before regardless of which
gateway is added). Settings has a dropdown (`GatewayProvider`: `BLAZETECH` /
`HOSTPINNACLE`, default `BLAZETECH`) that picks the active one; each provider
has its **own credentials + sender ID**, entered and saved independently
(`data/settings/GatewayCredentialsStore`, provider-scoped) — switching the
dropdown never loses or overwrites the other provider's saved credentials.
BlazeTech's credential is an API key; HostPinnacle's is a **username +
password** (see its own subsection below for why — this was originally built
as apikey-only and corrected after live testing).
Both gateway clients implement one `network/SmsGateway` interface and share
one response-interpretation function (`network/SendSmsResponseInterpreter`),
since both providers' send responses are verified to be the same shape.

A queued job remembers which provider it was created under
(`queue/OutboundJob.provider`, nullable — `null` for every job queued before
this feature existed, decoding to `BLAZETECH`) and always sends through
*that* provider, never "whichever is active now": a job's sender ID was
registered with one specific gateway account, so routing it through the
other provider live would almost certainly fail with an unregistered-sender
error even though nothing else about the job changed.

### BlazeTech (`network/BlazeTechGateway`, `network/ScopeSmsApi`)
- Base URL: `https://sms.blazetechscope.com/v1/`
- `POST /sendsms` — JSON body: `{ message, phone, sender_id, api_key }`.
  Documented success: `response-code: 200` with `messageid`. The **live**
  gateway actually answers `{"status":"success","statusCode":"200",...}`
  instead — see `SendSmsResponseInterpreter`, which checks for either shape.
  Phone format: local `07XXXXXXXX`/`01XXXXXXXX` (docs say international
  `254...` is also accepted and converted; the app converts explicitly via
  `PhoneNumbers.toLocalFormat` rather than depending on that).
- `POST /bulksms` exists (`phones` array + one shared `message`) but is
  **not a fit for this app's core flows**, since both unmatched and matched
  replies are personalized per recipient (different name/amount/bundle) —
  do not reach for bulk endpoint unless a genuinely identical-message
  broadcast feature is explicitly requested later.
- `POST /smsstatus` — optional delivery-status check by `message_id`; treat
  as a nice-to-have for the activity log, not a blocker for MVP.
- Rate limit: 100 requests/minute per API key.

### HostPinnacle (`network/HostPinnacleGateway`, `network/HostPinnacleApi`)
- Base URL: `https://smsportal.hostpinnacle.co.ke/SMSApi/`
- `POST send` — **`application/x-www-form-urlencoded`**, not JSON (the
  single biggest wire-format difference from BlazeTech). Auth is **`userid`+
  `password` body fields, not the `apikey` header.** HostPinnacle documents
  both as valid; this app originally used the apikey header on the
  assumption that's what the client's account had. Verified live
  (2026-08-07, with the client's real credentials) that this account's
  apikey-header auth does not work at all — every value tried, including a
  freshly portal-generated key, failed identically to a bogus string
  (`{"statusCode":"216","reason":"Invalid credentials"}`). userid+password
  authenticated immediately. Don't revert to the header mode without
  re-verifying live against the account in use — the docs presenting both
  as valid doesn't mean both are actually provisioned. `GatewayCredentials
  .apiKey` holds the **password** for this provider (paired with
  `.userId`), not an API key — see that class's doc. Form fields: `userid`,
  `password`, `mobile` (recipient, **international format with country
  code, no leading `+`**, e.g. `254712345678` — the opposite of BlazeTech's
  local format, hence `PhoneNumbers.toInternationalFormat`), `msg`,
  `senderid`, `sendMethod=quick`, `msgType=text`, `duplicatecheck=true`,
  `output=json`.
- Response shape is verified **byte-for-byte the same** as BlazeTech's live
  shape (`status`/`mobile`/`invalidMobile`/`transactionId`/`statusCode`/
  `reason`) — `SendSmsResponse` and `SendSmsResponseInterpreter` are reused
  as-is, not duplicated.
- No documented error-body examples exist for the send endpoint specifically;
  the same loose, vendor-agnostic error-text keyword matching
  (`SendSmsResponseInterpreter.classifyErrorMessage`) applies unchanged.
- `POST reports/status` — delivery-status lookup by transaction id (`uuid` —
  the same value returned from `send` as `transactionId`, surfaced to callers
  as `SendOutcome.Sent.messageId`), **implemented** (unlike BlazeTech's
  `/smsstatus`, still a documented-but-unbuilt nice-to-have). Manual, per-row
  "Check status" action on the Activity Log screen
  (`SmsGateway.checkStatus`/`DeliveryStatusOutcome`), not automatic polling —
  no background infrastructure was added for this. `BlazeTechGateway`
  inherits `SmsGateway.checkStatus`'s default (`DeliveryStatusOutcome
  .NotSupported`) rather than guessing at an undocumented endpoint.

### Shared across both gateways
- The burst requirement (10 SMS in 1–3 seconds) is well under the rate
  limit in isolated bursts, but the queue worker must still handle
  `429 Too Many Requests` gracefully (backoff + retry, not a dropped
  message) in case of a busier sustained period.
- Error handling: map documented/observed HTTP codes (400/401/403/429/500)
  and error-message bodies (invalid API key, insufficient balance, invalid
  phone, invalid/unregistered sender ID) to distinct, loggable failure
  reasons in the activity log — "send failed" alone is not sufficient for
  the agent to diagnose an issue.
- A sender ID must be pre-registered with the respective gateway before it
  will work — this is an account-setup prerequisite on the client's side,
  not something the app can fix. Settings surfaces a clear error if a
  gateway reports an unregistered sender ID, rather than retrying forever.

## M-Pesa message format — important
The client's actual till-confirmation SMS format (note: this is the
**business till** format, not the personal-to-personal format most sample
regexes online are built from):

```
UGFMXB3GR6 Confirmed.on 15/7/26 at 1:06 PMKsh20.00 received from
254700000000 Skycope Bonke. New Account balance is Ksh1300.22.
Transaction cost, Ksh0.00.
```

Fields: M-Pesa code, date, time, amount, sender phone, sender name, new
balance, transaction cost. Note the irregular spacing/concatenation around
"PMKsh20.00" — do not assume clean delimiters. The parser (Phase 2 of the
build plan) must be built and tested against **several real redacted
examples from the agent**, not a single assumed template — Safaricom
till-confirmation wording has known minor variants.

## Workflow rules (follow exactly)
1. **Start of every session:** read `CLAUDE.md` (this file), `memory.md`,
   `changelog.md`, and the relevant phase in `02-BUILD-PLAN.md`. Confirm
   current state from these files before writing code — don't assume.
2. **One phase (or clearly-scoped sub-task) per feature branch:**
   `feature/phase-N-<short-slug>`, branched from up-to-date `main`.
3. **All builds happen via GitHub Actions CI**, not locally. A phase isn't
   verified until its CI run is green (build + unit tests) and, where
   relevant, the agent has installed the resulting CI artifact APK on a
   real device to confirm behavior.
4. Exit criteria are listed per phase in `02-BUILD-PLAN.md` — treat them as
   a checklist, not a suggestion.
5. **Merge to `main` only after exit criteria are met and CI is green.** No
   direct commits to `main`. Squash or clean commit history on merge. Do not be lazy to commit to main.
6. **After every execution (every session, whether it finished a phase, made
   partial progress, or got blocked):**
   - Append an entry to `changelog.md` (date, what changed, branch/PR ref).
   - Update `memory.md` with anything future-you needs: decisions made and
     why, gotchas discovered, current state of in-progress work, anything
     that deviated from the build plan and why.
   - Update `README.md` if user-facing or setup instructions changed.
   - **This step is not optional and is not "if there's time."**
7. If a build-plan phase turns out to be wrong, too large, or based on a bad
   assumption — say so in `memory.md` and propose the correction, as just
   happened with this whole architecture pivot.

## `memory.md` / `changelog.md` / `README.md`
Unchanged in purpose from the original plan:
- `memory.md` — running technical memory: decisions + rationale, gotchas
  (OEM behavior, gateway quirks discovered in testing), per-phase status.
- `changelog.md` — dated, terse session-outcome log.
- `README.md` — project purpose, CI build/install instructions (since there's
  no local build), current feature status.

## Testing expectations
- Parser, rule-matching, and template-rendering need real unit tests, run in
  CI on every push — this is the primary safety net given there's no local
  Android Studio for manual poking.
- Gateway client (`network/`) needs unit tests against a mocked HTTP layer
  covering success, each documented error code, and timeout/no-connectivity
  — these are the failure paths that matter most now that sending is
  online-only.
- The burst-speed requirement (Phase covering the outbound queue) needs an
  explicit test that fires ~10 simulated SMS_RECEIVED events within 1–3
  seconds and asserts every one is matched and queued for send with no
  drops — don't consider this phase done on "it seemed fine manually."
- Anything touching real SIM/dual-SIM reading behavior still needs a
  real-device check — emulators can't fully validate that. Note in
  `memory.md` when something was only CI/unit-tested and still needs a
  real-device pass.
- Low-end/OEM background reliability (Tecno/Infinix/itel/Xiaomi are common
  among agents in Kenya) is still a first-class requirement for the
  ingestion side.

## Security/privacy notes
SMS content is sensitive (customer names, phone numbers, transaction data).
Incoming SMS parsing stays on-device. Outbound content is necessarily sent
to the SCOPE gateway (that's the whole point) — but the API key and sender
ID are secrets and must be stored encrypted, entered by the agent, never
committed to the repo or logged in plaintext. Don't add crash/analytics SDKs
that transmit device or usage data without this being explicitly decided and
recorded in `memory.md` first.
