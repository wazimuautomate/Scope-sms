# `network/` — SMS gateway clients

**Owned by:** Phase 5, extended for dual-gateway support.

## What belongs here
The HTTP clients for the two gateways the agent can choose between
([`SmsGateway`](SmsGateway.kt), [`GatewayProvider`](GatewayProvider.kt),
[`GatewayRegistry`](GatewayRegistry.kt)), their request/response models, and
the mapping from gateway failures to typed, loggable reasons. Nothing else in
the app should know either gateway's wire format.

## Two gateways, dropdown-selectable, independent credentials
The agent picks one in Settings; each has its own API key + sender ID
(`data/settings/GatewayCredentialsStore`, provider-scoped). BlazeTech is the
original integration and the default for every existing install
(`GatewayProvider.DEFAULT`) — it must keep working exactly as before. A queued
job remembers which provider it was created under
(`queue/OutboundJob.provider`) and always sends through that one, never
"whichever is active now" — see that field's doc for why.

### BlazeTech (`BlazeTechGateway`, `ScopeSmsApi`)
Base URL: `https://sms.blazetechscope.com/v1/`

- `POST /sendsms` — JSON body `{ message, phone, sender_id, api_key }`. Success
  is `response-code: 200` with a `messageid` (documented shape) — the LIVE
  gateway actually answers with `{"status":"success","statusCode":"200",...}`;
  see `SendSmsResponseInterpreter`. Phone format `07XXXXXXXX` / `01XXXXXXXX`
  (`PhoneNumbers.toLocalFormat`); `254…` is accepted and converted.
- `POST /smsstatus` — optional delivery-status lookup. Nice-to-have, not MVP.
- `POST /bulksms` — **do not use.** Both reply flows are personalised per
  recipient (different name, amount, bundle), so the shared-message bulk
  endpoint doesn't fit.

Rate limit: 100 requests/minute per API key.

### HostPinnacle (`HostPinnacleGateway`, `HostPinnacleApi`)
Base URL: `https://smsportal.hostpinnacle.co.ke/SMSApi/`

- `POST send` — **form-encoded**, not JSON: `userid`, `password`, `mobile`,
  `msg`, `senderid`, `sendMethod=quick`, `msgType=text`,
  `duplicatecheck=true`, `output=json`. Auth is **`userid`+`password` body
  fields, not the `apikey` header** — HostPinnacle documents both, but this
  client's account only authenticates via userid+password (verified live
  2026-08-07; the apikey header failed identically to a bogus key). See
  `GatewayCredentials`'s doc: `.apiKey` holds the password for this
  provider, paired with `.userId`. Phone format is **international**, no
  leading `+` (`254XXXXXXXXX`, via `PhoneNumbers.toInternationalFormat`) —
  the opposite of BlazeTech's local format.
- The response shape is verified byte-for-byte the same as BlazeTech's live
  shape (`status`/`mobile`/`invalidMobile`/`transactionId`/`statusCode`/`reason`)
  — `SendSmsResponse` and `SendSmsResponseInterpreter` are shared, not
  duplicated, between the two gateways.
- `POST reports/status` — delivery-status lookup by `uuid` (the `transactionId`
  `send` returned), `fromdate`/`todate` (`YYYY-MM-DD`) and `output=json`, same
  form-encoding + `userid`+`password` auth as `send`. Exposed as
  `SmsGateway.checkStatus`, default-implemented as `NotSupported` on the
  interface so `BlazeTechGateway` needed no changes — BlazeTech's own
  optional `/smsstatus` isn't wired up.

## Failure mapping is the point of this package
"Send failed" is not an acceptable log line — the agent has to be able to
diagnose it. `SendSmsResponseInterpreter` maps each documented case to a
distinct typed reason — invalid API key, insufficient balance, invalid phone,
invalid/unregistered sender ID, plus HTTP 400/401/403/429/500 and
timeout/no-connectivity — for BOTH gateways, since their wire contracts share
the same loose, vendor-agnostic error-text keywords.

The distinction that matters most is **retryable vs. terminal**. A 429 or a 500
or a dropped connection should back off and retry. An unregistered sender ID or
a bad API key will *never* succeed on retry — surface it to the agent in
Settings instead of burning the queue against it forever (CLAUDE.md, gateway
section).

## Secrets
Each provider's API key and sender ID are read from encrypted storage at call
time, entered independently by the agent in Settings. Never hardcode them,
never commit them, never log the raw key — including in an error path or an
OkHttp interceptor, which is the easiest place to leak it by accident
(constraint 7). Neither gateway client installs a logging interceptor, ever.

## Timeouts
30–60s per the gateway docs. This runs inside a WorkManager worker, never the
receiver, so a slow call is fine — it must never delay ingestion.
