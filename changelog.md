# changelog.md

Dated, terse session outcomes. Not a copy of git log.

---

## 2026-07-16 — Round-1 device-testing fixes ✅ (merged to main)

Six issues came back from the agent's real-device test; all fixed, plus a batch
of bugs a review of the integration work surfaced. All unit tests green locally
and pushed to `main`.

### The crash (#4) — reproduced and root-caused, not guessed
The Messages tab force-closed on open. Reproduced on an API 30 emulator in dark
mode. Cause: a scrollable (`LazyColumn` / `verticalScroll` Column) nested inside
another `Column` is measured with an **infinite** max height and throws
"measured with an infinity maximum height". The working screens escape it by
putting their scroll directly in the `Scaffold` body (bounded); Templates put it
under a `TabRow` inside a Column. **The same latent bug was in Activity and
Prices** — they only survived because an empty list/log never composes the
scrollable, so they'd have crashed the first time the agent had data. All three
now give the scroll area `weight(1f)`.

### The rest
- **#1 onboarding** — black-on-black in dark mode was a missing `Surface`
  (`LocalContentColor` defaults to black without one). Rebuilt: Surface-wrapped,
  animated between steps, centred, pulsing hero icon. **Gateway step removed** —
  API key/sender ID belong in Settings, not first-run.
- **#1b** — sender ID defaults to `SKYSCOPE_`; gateway setup only in Settings.
- **#6** — a delivered SMS was reported failed: a 200 whose message text
  mentioned the recipient number tripped the phone/number error classifier. A
  delivery signal (messageid, or response-code 200) is now authoritative and
  checked *before* text classification — the exact case the client warned about.
  Regression tests added.
- **#6b** — Settings can send a real matched/unmatched **sample** reply, rendered
  from the live templates + prices.
- **#3** — OEM "Open settings" now always lands somewhere, falling back to the
  app's own system settings page; always shown.
- **#5** — quiet ongoing "watching" notification (low importance, NOT a
  foreground service — constraint 6), re-posted on start and boot.
- **#2** — share/import price list as JSON (SAF; merges, skips duplicates).

### Review-found bugs also landed
sender ID was stored per job but the gateway ignored it (offline-queued replies
went out under the wrong ID); a send cancelled mid-flight never burned an attempt
(flaky-2G re-send-forever); `drain()` stranded jobs past 100; undecryptable
credentials never cleared (app insisted it was set up while every send failed);
the dashboard's "today" froze for the ViewModel's life; a template edit typed
during a save was dropped; `1,000` was rejected as having cents; `KshAmount.parse`
lacked the overflow guard its sibling had.

---

## 2026-07-16 — Integration + Phases 7, 10, 11: the app exists ✅

**Branch:** `integration/all-phases` → merged to `main` (8d86134).
**CI:** ✅ green — **276 unit tests + 6 instrumented tests on API 30 and API 36**,
0 failures, 0 skipped. Counts read out of the downloaded reports, not off the tick.

### The state this session found
`main` held **Phase 0 only**. All six phase branches were unmerged, and they did
not compile together. Phase 7 had never been built — the session that owned it
correctly declined, because it integrates layers that were still moving.

### Integration — the collisions no session could fix from inside its own worktree
- **Two money types.** Phase 2's `Money`/`amountCents: Long` vs Phase 3/4's
  `KshAmount`. Both chose integer cents, so the *data* agreed; they just would not
  compile. **KshAmount wins** — a value class (free at runtime on the hot path)
  whose `format()` already drops a trailing `.00`. `Money` deleted.
- **Three "the app's database".** Phases 5b, 3/4 and 8 each wrote one. Consolidated
  into `AppDatabase`, whose doc had already reserved the slots. Two databases
  would mean no transaction could span a queue job and its log row.
- `QueueGraph` absorbed into `AppContainer`, as its own doc invited.

### The receive path now exists
Every session left it as a comment because it needs Phases 2, 3, 4, 6 and 5b at
once. `PaymentPlanner` (pure, JVM-testable) does classify → toggles → render;
`PaymentPipeline` logs, then enqueues. **Log first**: die between the two and the
agent sees a `QUEUED` reply that never sends — visible and diagnosable — rather
than a customer texted with no trace of why. The log insert carries the duplicate
guard, so an OEM redelivery stops before it can text anyone twice.

### Whole-shilling amounts, per the client
`parseWholeShillings()` rejects decimals at **entry**, so no rule can hold cents
and `format()` can be trusted to render no decimal point. Deliberately asymmetric
with `parse()`: what the agent types is constrained, what a customer *sends* is
not — Ksh 20.50 keeps its cents and correctly matches nothing, rather than being
rounded into the Ksh 20 bundle and confirming a purchase that never happened.

### Built
- **Phase 7 — the whole UI.** Onboarding (permissions → SIM → gateway + test send
  → battery), Home with both toggles visible at a glance and the four stat tiles,
  Rules, Templates (two tabs, live preview through the *real* engine, segment
  count), Activity Log (search + filters), Settings (SIM, gateway, battery, OEM
  guidance, version, update check). Navigation hand-rolled — five flat screens,
  no arguments; a nav library would add a route DSL to express `current = RULES`.
- **Phase 10 — emulator matrix** on API 30 (minSdk, what the target handsets run)
  and 36 (targetSdk). ON despite being optional in the plan, for one reason: the
  Keystore holds the agent's API key, has no JVM equivalent, and breaks on exactly
  this market's OEMs. `SmokeTest` round-trips a real secret through the real
  Keystore. `continue-on-error` — emulator jobs flake for reasons unrelated to the
  app, and a red tick nobody trusts is worse than none.
- **Phase 11 — tag-triggered signed release.** `release.yml`: test → sign → verify
  with apksigner → attach to a GitHub Release. Fails if the tag and `versionName`
  disagree. In-app update check (pure comparator + a thin GitHub client), on
  demand only — the agent pays for that data.
- **Gateway credentials encrypted** — Android Keystore AES/GCM over DataStore.
  Closes open decision 1. Verified on real emulators, not just unit-tested.
- **The queue reports outcomes to the activity log.** Phase 5 had no way to; a
  failed reply updated a job row the agent never sees.

### Found and fixed
- 🔴 **`WorkManager.enqueue` from `Application.onCreate`** — my own bug, two at
  once: a **disk write on the main thread of every process start** (including the
  headless SMS wakeups constraint 5 exists to protect), and it **throws** if
  WorkManager's initializer hasn't run, which is a dead app at launch *and a dead
  receiver*. Caught by the Robolectric suite.
- 🔴 **`Icons.Default.*` needs `material-icons-core` explicitly** — Material3
  doesn't bring it transitively.
- 🔴 **There is a working local compiler.** No Android Studio, but a JDK 21 + the
  already-installed SDK builds and tests this project fine. Every prior session
  believed CI was the only compiler and paid a 5–10 min round trip per typo. This
  is now the top gotcha in `memory.md`.

### 🔴 Still open — needs you or the agent, not a session
- **Still exactly one real M-Pesa sample.** Unchanged since Phase 2 and still the
  highest-value ask on the project. The parser is green against ~30 *constructed*
  variants; that means "no known case is broken", not "the parser works".
- **No signing key exists.** `release.yml` is written but unexercised — key custody
  is the client's decision, deliberately handed back rather than assumed. Testing
  APK is debug-signed. See README → "Cutting a release".
- **Toggle defaults unconfirmed** (unmatched=ON, matched=OFF). Safe to ship: a
  fresh install has no rules, so nothing can send regardless.
- **targetSdk stays 36** — now a decision, not a deferral. No Android 17 device to
  test against, and targetSdk opts into exactly the runtime behaviour (background
  execution, broadcast delivery) that sits between "customer pays" and "customer
  gets a reply".
- **Real-device checks**: dual-SIM, the `^M-?PESA$` sender rule, the Phase 7
  click-through, Phase 9's soak/reboot/airplane-mode, and the Transsion autostart
  deep links. Steps in `README.md`.

---

## 2026-07-16 — Phase 9: Reliability hardening 🟡 code done, exit criteria unmet

**Branch:** `feature/phase-9-reliability-hardening` (cut from
`feature/phase-1-permissions-sim`, **not** `main` — Phase 9 uses Phase 1's
classes and `main` has only Phase 0). **Merge Phase 1 first.**
**CI:** ✅ green — run 29452472972, 64 tests / 0 failures, debug APK built.

### Built
- **Boot health check** — `BootCompletedReceiver` → `ReliabilityInspector` →
  pure `domain/reliability/ReliabilityCheck`. Detects a revoked permission, no
  SIM, a watched slot with no SIM in it, and a missing battery exemption; warns
  the agent via a `health` notification channel. Silent when healthy.
- **OEM autostart guidance** — per-OEM instructions (Transsion / Xiaomi /
  ColorOS / Vivo / Huawei / Samsung / generic) plus best-effort deep links,
  probed at runtime and discarded when absent.
- **UI** — stateless composables only (`OemGuidanceSection`,
  `ReliabilityIssueCard`) for Phase 7 to place. No screen, no ViewModel.
- **29 JVM tests**, no Robolectric (CI stays on JDK 17).

### 🔴 Not done — Phase 9's exit criteria are all real-device work
The 24h Transsion soak test, the reboot pass, and the airplane-mode queue test
have **not been run**. Green CI here proves the code compiles and the pure logic
is right — the half that was never in doubt. The airplane-mode criterion is also
blocked on Phase 5b (no outbound queue exists yet). **Phase 9 is not done.**

### Found
- **The plan's boot-check tasks were already solved by Phase 1.** BUILD-PLAN asks
  the receiver to re-validate saved subscription IDs and saved exemption status;
  Phase 1 persists neither, on purpose, citing this same Phase 9 line. Checks the
  equivalent live conditions instead — chiefly "the watched slot has no SIM in
  it", where `SimFilter` correctly drops every message and the app looks healthy
  while replying to nobody.
- **🔴 Transsion component names are unverified** and it's the primary market. The
  ~12 repos carrying them largely copy each other, and the most-used library has
  no Transsion entry at all. Needs the agent's real Tecno/Infinix to settle. The
  written instructions are the contract precisely because of this.
- **A bug caught by its own test:** `none {}` is vacuously true on an empty set,
  so an empty SIM selection rendered *"You told Scope SMS to watch , but those
  slots are empty."* Red on the first CI run, then fixed.
- **🔴 Parallel sessions share one working directory and one git HEAD.** Another
  agent's `git checkout -b` moved this session's branch mid-work; Phase 9's
  uncommitted files ended up on Phase 2's branch. Nothing lost — Phase 2's tree
  was handed back untouched and Phase 9 moved to a `git worktree`. **Every
  session should use its own worktree**; see memory.md.

### Raised for the client / next sessions
- Someone must install a CI APK on the agent's **real Tecno/Infinix** and report
  which autostart screen opens (or that none does).
- **Phase 8:** use a *different* notification channel for send failures — muting
  "a reply failed" must not mute "the app has stopped working".
- **Phase 2** owns "malformed SMS: log and skip, never crash" (a Phase 9 bullet)
  — left alone to avoid colliding with that live session.

---

## 2026-07-15 — Phase 0: Repository, scaffolding & CI pipeline ✅

**Branch:** `feature/phase-0-scaffold-ci` → merged to `main`
**State:** Phase 0 complete. All exit criteria met.

First session on the project. Repo was empty; `main` seeded with the existing
governance docs, all Phase 0 work done on a feature branch per the no-direct-
commits rule.

### Built
- **Android project** — Kotlin + Compose, `minSdk 30`, `compileSdk 37`,
  `targetSdk 36`, JDK 17, applicationId `com.scopesms.autoreply`.
- **Package structure** per BUILD-PLAN: `data/ domain/ telephony/ network/
  queue/ ui/ di/`. Each has a README naming its owning phase and constraints,
  since phases are being built by parallel sessions.
- **GitHub Actions CI** (`.github/workflows/build.yml`) — the project's only
  compiler. On push to any branch + PRs to main: validate wrapper → unit tests
  → debug APK → upload artifact. Test report uploaded on failure.
- **Brand theme + launcher icons** carried over from the local reference
  material, with the reference's inverted dark-mode bug fixed rather than
  copied.
- **Governance files** — `memory.md`, `changelog.md`, `README.md`.
- **`ArchitectureGuardTest`** — fails the build if `SEND_SMS` is declared or
  `SmsManager` referenced. Replaces the trivially-passing test the plan allows.

### Exit criteria — met
| Criterion | Result |
| --- | --- |
| Push triggers CI | ✅ |
| Unit tests run and pass | ✅ 4 tests, 0 failures, 0 skipped |
| Debug APK downloadable from artifacts | ✅ `scope-sms-debug-2-7c34f10…` (10.5 MB) |

### Verified, not assumed
- **The guard test actually fails when violated.** A scratch branch added
  `SEND_SMS`, CI went red on exactly that assertion, branch deleted. A guard
  that has never failed is not known to guard.
- **Wrapper jar is authentic** — sha256 matched Gradle's published checksum;
  CI re-validates it every run.
- **Tests ran non-vacuously** — a fixture assertion proves the manifest and
  sources are actually being read.

### Fixed during the session
- **CI red on first run:** current AndroidX requires `compileSdk 37` since
  Android 17 shipped. Bumped compileSdk; deliberately left `targetSdk` at 36
  (see memory.md — flagged deviation, Phase 10 owns it).

### Decisions (full rationale in `memory.md`)
- **AGP 9 has built-in Kotlin** — `org.jetbrains.kotlin.android` must NOT be
  applied; it hard-fails. Kotlin pinned to **2.2.10** (AGP 9.2.1's own bundled
  compiler) rather than the newer 2.4.10, because that's the combination Google
  actually tests and nothing here needs newer language features.
- **AGP 9.2.1**, not 9.2.0 (known R8 bug) and not 9.3.0 (days old).
- `allowBackup=false`; `queue/` kept top-level; no permissions in the manifest
  yet (Phase 1 owns them); DI deliberately undecided.

### Raised for the client / next sessions
- 🔴 **`security-crypto` is deprecated** and has OEM keyset-corruption crashes
  on this app's exact target devices. Blocks the Phase 5/7 API-key storage
  decision.
- 🔴 **Only one real M-Pesa sample message exists.** BUILD-PLAN Phase 2 needs
  5–10 more redacted samples from the agent — a hard blocker for the parser.
- 🔴 **`01-UI-DESIGN-PROMPT.md` does not exist in the repo**, though both
  governance docs reference it. Phase 7 has no UI spec beyond the out-of-date
  AI Studio reference.
- ⚠️ **Robolectric needs JDK 21** against SDK 36+; CI is on 17. Will bite
  whichever phase adds it.
- ⚠️ Launcher icons are legacy flat PNGs — no adaptive icon layers.
