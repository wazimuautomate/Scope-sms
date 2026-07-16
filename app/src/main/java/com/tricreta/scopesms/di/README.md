# `di/` — dependency injection

**Decided by:** Phase 3, the first phase that needed to wire anything together —
exactly as Phase 0 planned.

## Status: settled — manual DI, via `AppContainer`

Phase 0 established neither manual DI nor Hilt, deliberately: a scaffold with one
Activity has nothing to inject, and guessing wrong would have forced a later
session to unpick it. Phase 3 brought the first real graph (a database, two
repositories, two caches) and made the call.

**It is manual.** The reasoning is in the KDoc on `AppContainer` — read that
rather than re-deriving it. In short:

1. The graph is five process-scoped singletons with no variants, no qualifiers
   and no swappable implementations. Hilt earns its keep on graphs that have
   those; this one doesn't.
2. The awkward consumer is a `BroadcastReceiver`, which Android constructs
   itself. Hilt solves that with `@AndroidEntryPoint`; manual DI solves it by
   reading a field off the `Application` — with no annotation processor and
   nothing generated to reason about at 2am.
3. There is no local Android Studio (CLAUDE.md constraint 8), so every build
   mistake costs a CI round trip. Room already brings KSP; Hilt would add a
   second processor plus a Gradle plugin whose behaviour under AGP 9's built-in
   Kotlin nobody on this project has verified.

**Do not relitigate this per phase.** If the graph later grows scopes and
swappable implementations, revisit it deliberately and record the change in
`memory.md`.

## How to get the graph

```kotlin
// From a receiver, Activity, or anything with a Context:
val container = context.appContainer
```

`appContainer` is an extension on `Context` in `AppContainer.kt`. Use it rather
than casting `ScopeSmsApplication` by hand.

## Constraints to respect when extending it

- **Keep `Application.onCreate` cheap.** It runs on every process start,
  including the headless ones an incoming SMS causes, and sits on the path
  CLAUDE.md constraint 5 asks to keep fast. New dependencies should be `by lazy`
  or initialised on a background dispatcher — `AppContainer` opens no database on
  the main thread today, and it should stay that way.
- **The caches are fed from Room's `Flow`, not by writers.** `start()` owns that.
  Don't add a "poke the cache after saving" call to a repository; see
  `SnapshotCache`'s KDoc for why that pattern is the stale-cache bug waiting to
  happen.
- **Anything read on the SMS path must be reachable from process scope**, because
  a receiver has no Activity to hang off.
