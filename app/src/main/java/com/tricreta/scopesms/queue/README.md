# `queue/` — outbound send queue

**Owned by:** Phase 5b — the single most important phase in the plan.

## Why this package exists
It decouples "decide to send" from "actually send". The receiver decides in
milliseconds and writes a row; a worker drains that row over the network
whenever it can. Without this split, a slow gateway call inside the receiver
would stall the next incoming SMS, and the client's stated worst case —
**~10 payments landing in 1–3 seconds** — would drop messages.

BUILD-PLAN made this a top-level package. It could have lived under
`data/` + `domain/`; it doesn't, because the queue is the app's critical
reliability boundary and deserves to be obvious. Keep it here.

## Contents
- `OutboundJob` Room entity: `(id, transactionCode, phone, message, senderId,
  status[PENDING|SENDING|SENT|FAILED], attemptCount, createdAt, lastError)`.
- A `WorkManager` `CoroutineWorker` that drains `PENDING` jobs, calls the
  `network/` client, updates status, and retries with backoff.

WorkManager — not a custom foreground service. CLAUDE.md constraint 6 bars a
persistent foreground service for detection; the outbound queue is legitimate
background network work and WorkManager is the sanctioned tool.

## Non-negotiables
- **Never drop a message.** No connectivity at arrival → the job stays
  `PENDING` and sends when the network returns (use a `NetworkType.CONNECTED`
  constraint). Exhausted retries → `FAILED` with a readable reason surfaced in
  the activity log. Silence is the one unacceptable outcome; the agent's
  customer is waiting on that SMS.
- **Never double-send.** Dedupe on `transactionCode`. OEMs redeliver
  `SMS_RECEIVED`, and a duplicate costs the agent money and annoys a customer.
- **Never block ingestion.** The receiver writes and returns. It does not wait
  on this worker.

## The exit criterion
A test firing ~10 `SMS_RECEIVED` events within 1–3 seconds (mixed matched and
unmatched) asserting every one produces exactly one correctly-templated job,
no drops, no duplicates. Per BUILD-PLAN, this phase is not done without that
test passing in CI. Not "it seemed fine manually".
