# `di/` — dependency injection

**Owned by:** whichever phase first needs to wire two things together
(realistically Phase 3).

## Status: undecided — and that's deliberate
CLAUDE.md says "manual DI or Hilt, whichever is already established in the repo
by the time you read this (check `memory.md`)". Phase 0 established **neither**,
because a scaffold with one Activity and no dependencies has nothing to inject,
and guessing wrong would force a later session to unpick it.

**The first phase that genuinely needs DI makes the call and records it in
`memory.md`.** Once recorded, it is settled — don't relitigate it per phase.

## Input for whoever decides
The awkward constraint is that a `BroadcastReceiver` is constructed by the
system, not by you, and it needs the rule cache, the template engine and the
queue. So the graph must be reachable from a static/process scope regardless of
which route is chosen.

- **Manual DI** — a container built in `ScopeSmsApplication` and read by the
  receiver. No annotation processor, so no KSP hit on build times, and the
  receiver's access path stays obvious. Fits an app this size.
- **Hilt** — `@AndroidEntryPoint` supports receivers, but it adds KSP and
  ceremony for a graph that is, realistically, a handful of singletons.

Nothing here is load-bearing yet either way.
