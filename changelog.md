# changelog.md

Dated, terse session outcomes. Not a copy of git log.

---

## 2026-07-15 — Phases 1 & 2: Permissions/SIM foundation + M-Pesa ingestion 🟡

**Branches:** `feature/phase-1-permissions-sim` (PR #2) →
`feature/phase-2-sms-ingestion-parser` (PR #3, stacked on #2).
**State:** both **code-complete and CI-green, neither verified-complete.** Read
the exit-criteria section below before reporting either as done.

### Built — Phase 1
- **Permission set** in the manifest + `AppPermission`, a pure SDK-gated model.
  `POST_NOTIFICATIONS` requested only on 33+; install-time permissions never
  passed to the runtime request (doing so builds a screen that can never be
  satisfied).
- **`SimSelection` / `SimFilter`** — the agent's SIM choice and the
  process/drop decision. Pure, JVM-tested, keyed on **physical slot**.
- **`SimReader`** — defensive `SubscriptionManager` wrapper; degrades to an
  empty list rather than throwing into the receiver.
- **`SettingsRepository`** — DataStore, with a volatile snapshot so the SMS hot
  path does no disk I/O (constraint 5).
- **`BatteryOptimizationManager`** — live read + exemption intents with an
  OEM fallback.
- **`AppContainer`** — manual DI. This is the decision `di/README.md` deferred.
- **`SetupScreen`** — deliberately plain; exists only so the exit criteria are
  tappable on a real device. Phase 7 replaces it.

### Built — Phase 2
- **`SmsReceiver`** — manifest-registered, `goAsync()`, locked to
  `BROADCAST_SMS`. Rejects cheapest-first (action → sender → SIM → parse); the
  SIM drop happens **before the body is read**, so the agent's personal messages
  are never parsed.
- **`MpesaParser`** — pure till-confirmation parser, tolerant of M-Pesa's
  irregular spacing (`Confirmed.on`, `PMKsh20.00`), strict about structure.
  Explicitly rejects sent/withdrawn/paid/airtime/balance types and non-M-Pesa
  senders.
- **`Money`** — integer cents.
- **`SubscriptionExtras`** — OEM-tolerant subId/slot extraction, pure.

### CI
| | Result |
| --- | --- |
| Phase 1 | ✅ 35 tests, 0 failures; debug APK 11.4 MB |
| Phase 2 | ✅ 84 tests, 0 failures |

Counts read from the downloaded CI test report, not from the green tick.

### 🔴 Exit criteria NOT met — both phases
- **Phase 1** needs a real dual-SIM device to list both SIMs and persist the
  filter across restart/reboot. No device exists in this workflow — **only the
  agent can run this.** Steps are in `README.md`.
- **Phase 2** needs the suite passing against real samples. **We still have
  exactly one** (open decision 5). Every other case in `MpesaParserTest` is a
  *constructed* variant and is labelled as such in the file. Green means "no
  known case is broken", not "the parser is done".

Merged to `main` regardless so the parallel Phase 3/4/5 sessions aren't blocked
on `AppContainer`/`SettingsRepository`/`Money`. Logged as deviation 0 in
`memory.md`.

### Decisions (full rationale in `memory.md`)
- **Manual DI, not Hilt** — resolves the open decision Phase 0 deferred.
- 🔴 **Money is integer cents. Phase 3's `PricingRule.amount` must match** — a
  rules table in shillings against a parser in cents matches nothing, and the
  app would silently treat every payment as unmatched.
- 🔴 **SIM choice keyed on slot, not subscription ID** — subscription IDs are
  not stable across re-seat/reboot, so persisting one would silently repoint the
  agent's filter at their personal SIM (constraint 4's worst case).
- **Unresolvable slot → drop**, except on a single-active-SIM device.
- **Battery exemption read live, never persisted** — a cached copy shows a green
  "protected" badge for an app the system is killing.

### Fixed during the session
- **CI red:** Kotlin enum entries can't read their own companion's constants.
  Moved to file-level constants.
- **`git add -A` swept another session's `Design.md`** into a Phase 1 commit;
  untracked in a follow-up. It remains on disk, untouched.

### Raised for the client / next sessions
- 🔴 **Still only one real M-Pesa sample.** Phase 2's regex cannot be trusted
  until the agent supplies 5–10 more redacted ones. Highest-value ask on the
  project right now.
- ⚠️ **The M-Pesa sender rule (`^M-?PESA$`) is unverified on the agent's
  handset.** If real payments are dropped, look here first — the rejected
  address is logged.
- 🔴 **Parallel sessions share one working directory.** Two near-misses this
  session (see `memory.md` gotchas). `git worktree` per session would remove the
  whole class of problem.

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
