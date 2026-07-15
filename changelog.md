# changelog.md

Dated, terse session outcomes. Not a copy of git log.

---

## 2026-07-16 — Phase 5: SCOPE SMS gateway client ✅ · Phase 5b: outbound queue 🟡

**Branch:** `feature/phase-5-gateway-queue` (PR open, CI green)
**State:** Phase 5 complete. Phase 5b code-complete, headline criterion partly
blocked on Phases 2–4 — **do not tick 5b off yet** (memory.md, open decision 0).

Built in a git worktree off `main`, because this repo's working tree was sitting
on the Phase 1 session's uncommitted changes.

### Built — Phase 5 (`network/`)
- **`ScopeSmsGateway`** over Retrofit/OkHttp/Moshi → `POST /sendsms`.
- **Typed failure taxonomy** (`SendFailure`) splitting **retryable vs terminal** —
  the property the queue branches on. Bad API key / unregistered sender ID are
  terminal and surface to the agent; 429/5xx/timeout/offline retry.
- **Errors are values, never exceptions** — the client cannot throw into the worker.
- Phone normalisation from M-Pesa's `254…` to the gateway's documented local format.
- Credentials behind a port; storage remains Phase 6/7's open decision.

### Built — Phase 5b (`queue/`)
- `OutboundJob` + DAO. **Dedupe enforced by a unique index on `transactionCode`**,
  not a Kotlin-side check, which races under burst.
- `OutboundQueue` — `enqueue` is one insert with no network; `drain` retries with
  a bounded budget and reclaims jobs stranded by process death.
- `SendJobWorker` on WorkManager with `NetworkType.CONNECTED`: a payment arriving
  offline queues and sends when data returns.
- `AppDatabase` created here (first phase to need Room) but **shared with Phases
  3/4/8** — merge instructions in its class doc.

### Exit criteria
| Criterion | Result |
| --- | --- |
| Phase 5 — success, each documented error, timeout/no-connectivity | ✅ 42 tests, 0 failures, 0 skipped |
| Phase 5b — no drops, no duplicates, no blocking of ingestion | ✅ at the queue boundary |
| Phase 5b — "correctly-templated", real `SMS_RECEIVED` burst | 🟡 needs Phases 2–4 |

### Verified, not assumed
- **The burst test actually fails when the dedupe breaks.** Scratch branch
  (`chore/verify-burst-dedupe-guard`, deleted) swapped the atomic insert for a
  check-then-insert race; CI went red on exactly the race assertion. A
  concurrency test that has never failed may simply never have hit the window.
- **Tests ran non-vacuously** — 42 across 5 classes, verified from the CI report.

### Decisions (full rationale in `memory.md`)
- **Retrofit**, not Ktor. **Moshi via reflection**, not codegen — Moshi 1.15.2's
  codegen is KSP1-era and the catalog pins KSP2; reflection has no processor
  risk. Cost: R8 keep rules are now load-bearing, and R8 only runs in release.
- **`InsufficientBalance` is retryable** — the one judgement call; a top-up takes
  a minute and the customer is still waiting.
- **DI still undecided** — `QueueGraph` is a one-slot seam, not a decision.
  Phase 3 (or whoever) should absorb or delete it.

### Raised for the next sessions
- 🔴 **Phase 5b is not done.** Phase 2 must wire receiver → `OutboundQueue.enqueue`
  and re-run the burst end-to-end.
- ⚠️ **`INTERNET` is still not in the manifest** (Phase 1 owns it), so on a real
  device every send fails until Phase 1 merges. Unit tests can't catch this; it
  will look like "the APK does nothing".
- ✅ **Most "later phases" catalog pins are now CI-verified** — Retrofit, OkHttp,
  Moshi, Room, KSP, WorkManager, Truth, coroutines-test, mockwebserver3.
  DataStore/Robolectric/room-testing still unexercised.

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
