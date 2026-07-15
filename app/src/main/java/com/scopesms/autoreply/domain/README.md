# `domain/` — parsing, matching, decision logic

**Owned by:** Phase 2 (parser), Phase 3 (rules + in-memory cache), Phase 4
(template engine), Phase 6 (toggle checks).

## What belongs here
Pure Kotlin. The M-Pesa till-confirmation parser, the in-memory rule cache and
lookup, the template variable-substitution engine, and the decision function
that turns a parsed payment into "enqueue this message" or "do nothing".

## What does not belong here
No `android.*` imports beyond the unavoidable, no Room types, no HTTP, no
Android framework dependencies. This package is where the unit tests live
(see `app/src/test/`) and it must stay testable on the JVM without Robolectric.
That's not style preference — CI is the only place this project builds
(CLAUDE.md constraint 8), so JVM-fast tests are the primary safety net.

## The constraint that shapes this package
CLAUDE.md constraint 5: the detect-and-decide path is synchronous and must
survive ~10 SMS arriving in 1–3 seconds. Everything here runs on that hot
path. So:

- **No I/O.** No Room query, no disk read, no network — the rule lookup reads
  an in-memory `Map`, not the database.
- **No blocking.** Parsing is pure and synchronous.

Room is the source of truth for rules and templates; this package reads a cache
built *from* Room. Keeping that cache in sync on every CRUD edit is Phase 3's
job, and a stale-cache bug here means the agent quotes wrong prices to a paying
customer.

## Parser note
Build the parser against the **business till** format, which is not the
person-to-person format most sample regexes online target. Real example (from
CLAUDE.md, note the missing space in `PMKsh20.00`):

```
UGFMXB3GR6 Confirmed.on 15/7/26 at 1:06 PMKsh20.00 received from
254700000000 Skycope Bonke. New Account balance is Ksh1300.22.
Transaction cost, Ksh0.00.
```

Do not ship a regex built from that single sample — BUILD-PLAN Phase 2 requires
5–10 more real redacted messages from the agent first.
