# `network/` — SCOPE SMS gateway client

**Owned by:** Phase 5.

## What belongs here
The HTTP client for the client's own gateway, its request/response models, and
the mapping from gateway failures to typed, loggable reasons. Nothing else in
the app should know the gateway's wire format.

## Gateway
Base URL: `https://sms.blazetechscope.com/v1/`

- `POST /sendsms` — `{ message, phone, sender_id, api_key }`. Success is
  `response-code: 200` with a `messageid`. Phone format `07XXXXXXXX` /
  `01XXXXXXXX`; `254…` is accepted and converted.
- `POST /smsstatus` — optional delivery-status lookup. Nice-to-have, not MVP.
- `POST /bulksms` — **do not use.** Both reply flows are personalised per
  recipient (different name, amount, bundle), so the shared-message bulk
  endpoint doesn't fit.

Rate limit: 100 requests/minute per API key.

## Failure mapping is the point of this package
"Send failed" is not an acceptable log line — the agent has to be able to
diagnose it. Map each documented case to a distinct typed reason: invalid API
key, insufficient balance, invalid phone, invalid/unregistered sender ID, plus
HTTP 400/401/403/429/500 and timeout/no-connectivity.

The distinction that matters most is **retryable vs. terminal**. A 429 or a 500
or a dropped connection should back off and retry. An unregistered sender ID or
a bad API key will *never* succeed on retry — surface it to the agent in
Settings instead of burning the queue against it forever (CLAUDE.md, gateway
section).

## Secrets
The API key and sender ID are read from encrypted storage at call time, entered
once by the agent in Settings. Never hardcode them, never commit them, never
log the raw key — including in an error path or an OkHttp interceptor, which is
the easiest place to leak it by accident (constraint 7).

## Timeouts
30–60s per the gateway docs. This runs inside a WorkManager worker, never the
receiver, so a slow call is fine — it must never delay ingestion.
