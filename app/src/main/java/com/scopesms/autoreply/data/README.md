# `data/` — persistence

**Owned by:** Phase 1 (DataStore settings), Phase 3 (`PricingRule`), Phase 4
(`MessageTemplate`), Phase 5b (`OutboundJob`), Phase 8 (`ActivityLogEntry`).

## What belongs here
Room entities, DAOs, the database class, repositories, and the DataStore /
EncryptedSharedPreferences settings layer.

## Split
- **Room** — rules, templates (two types), the outbound send queue, activity
  log.
- **DataStore** — non-secret settings: SIM selection, the two independent
  notification toggles, battery-exemption status, onboarding state.
- **Encrypted storage** — gateway API key + sender ID *only*. These are
  secrets (constraint 7): never in source, never committed, never logged.

  The encryption mechanism is an **open decision** — `androidx.security:security-crypto`
  (`EncryptedSharedPreferences`) is the obvious candidate but its status needs
  confirming before Phase 5/7 leans on it. See `memory.md`.

## Room is the source of truth, not the hot path
Rules and templates are read on the SMS-receive hot path, which must not hit
the database (CLAUDE.md constraint 5). Room remains the durable source of
truth; `domain/` reads an in-memory cache built from it. **Every write through
these DAOs must update that cache**, or the agent edits a bundle price in the
UI and the receiver keeps quoting the old one.

## Schema migrations
Once the agent is running this on their live business, a destructive migration
throws away their bundle rules and activity history. Export Room schemas
(`room.schemaLocation`) and write real migrations from the first release
onward — `fallbackToDestructiveMigration()` is not acceptable in a shipped
build.
