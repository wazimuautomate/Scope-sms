# `telephony/` — SMS ingestion and SIM identification

**Owned by:** Phase 1 (SIM identification, permissions), Phase 2 (receiver),
Phase 9 (`BOOT_COMPLETED`, SIM hot-swap re-validation).

## What belongs here
The manifest-registered `BroadcastReceiver` for `SMS_RECEIVED_ACTION`, the
`SubscriptionManager` wrapper that lists active SIMs, and the SIM filter that
drops messages arriving on a SIM the agent didn't select.

## This package is INGESTION ONLY — it does not send
**Never use `SmsManager` here, or anywhere.** All outbound messages go through
the SCOPE SMS HTTP gateway with the agent's registered sender ID
(CLAUDE.md constraint 3). `SEND_SMS` must not appear in the manifest. If a
future session finds itself reaching for `SmsManager.sendTextMessage`, that is
the pre-pivot architecture and it is wrong — the whole point of the gateway is
that replies come from "SCOPE SMS", not from the agent's phone number.

Reading is where dual-SIM still matters: the receiver must read `SLOT_INDEX` /
`SUBSCRIPTION_INDEX` from the intent and drop anything that isn't from the
selected SIM(s). That filter is what stops the agent's *personal* M-Pesa
traffic triggering customer replies. Outbound routing per-SIM is no longer a
concern (constraint 4).

## Receiver rules
- Use `goAsync()` — but finish fast. The decide path must not block.
- Do not call the network from the receiver. Write a queue row and return;
  `queue/` drains it later (constraint 5).
- Never crash on a malformed SMS: log and skip (Phase 9).
- Expect duplicate deliveries. Some OEMs fire `SMS_RECEIVED` more than once for
  the same message, so dedupe on the M-Pesa transaction code — a double-send
  means the customer gets the same reply twice and the agent pays twice.
