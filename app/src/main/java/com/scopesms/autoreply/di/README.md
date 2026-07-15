# `di/` — dependency injection

**Owned by:** Phase 1 (decided it), every phase after (uses it).

## Status: DECIDED — manual DI

CLAUDE.md says "manual DI or Hilt, whichever is already established in the repo
by the time you read this (check `memory.md`)". Phase 0 deliberately established
neither, leaving the call to the first phase that genuinely needed to wire two
things together. That was **Phase 1**, and the answer is **manual DI**:
`AppContainer`, constructed by `ScopeSmsApplication`, reached via
`AppContainer.from(context)`.

**This is settled — don't relitigate it per phase.** Full rationale is in the
KDoc on `AppContainer` and in `memory.md`. The short version:

1. The graph is a handful of process-scoped singletons and will stay that way.
2. Hilt needs KSP, and CI is this project's only compiler (CLAUDE.md constraint
   8) — every annotation processor is a per-push cost and one more failure mode
   nobody can reproduce locally. Room forces KSP on us in Phase 3; that one is
   unavoidable, this one isn't.
3. The awkward bit — a `BroadcastReceiver` is constructed by the system, so the
   graph must be reachable from process scope — is solved identically either
   way. `@AndroidEntryPoint` would hide that lookup, not remove it.

Revisit only if the graph grows real scopes (per-Activity, per-worker) that make
hand-wiring error-prone. If it does, record the change here and in `memory.md`.

## How to use it

```kotlin
// From a BroadcastReceiver, Worker, or anywhere else the system constructs:
val container = AppContainer.from(context)
container.settings.currentSimSelection()
```

From a `ViewModel`, prefer a `Factory` that pulls the container out of
`CreationExtras` — see `SetupViewModel.Factory` for the pattern.

## Rules for anything added here

- **Everything stays lazy.** `AppContainer` is constructed on every process
  start, including the headless ones an incoming SMS causes at 2am. CLAUDE.md
  constraint 5 wants that path fast, so no field may do I/O to be created.
- **Nothing holds an Activity `Context`.** Use `context.applicationContext`;
  the container outlives every screen.
- **`applicationScope` is never cancelled.** Its lifetime is the process's. It
  uses a `SupervisorJob` so one failing child can't take ingestion down with it.
