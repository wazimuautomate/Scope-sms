# changelog.md

Dated, terse session outcomes. Not a copy of git log.

---

## 2026-07-16 — Phase 6 ✅ + Phase 8 ✅ (Phase 7 ⛔ blocked)

**Branch:** `feature/phase-6-7-8-toggles-ui-log` (stacked on phase-1, merges phase-5)
**State:** Phase 6 done. Phase 8 code done. **Phase 7 not started — blocked.**

Session brief was "build Phase 6, 7 & 8". Two of the three shipped; Phase 7 is
the integration layer and its dependencies are still unmerged (see below).

### Built
- **Phase 6 — independent notification toggles.** `NotificationToggles` (the two
  flags as one consistent snapshot), persisted in Phase 1's existing DataStore —
  a second DataStore on the same file would corrupt it, so extending
  `SettingsRepository` was forced, not chosen. `decideReply()` is a pure, total
  gate over `MatchOutcome × toggles`, JVM-testable with no Android runtime.
- **Phase 8 — activity log & dashboard stats.** `ActivityLogEntity`/`Dao`/
  `Repository` + `ActivityRecord`/`DashboardStats`. Stats computed in SQL; search
  and filter by text/status/flow/date; log dedupe on `transactionCode`.
- **CI moved to JDK 21** — the snag Phase 0 predicted, now real (Robolectric vs
  SDK 36+). `compileOptions` stays at 17.

### Exit criteria
| Phase | Criterion | Result |
| --- | --- | --- |
| 6 | All four toggle combinations tested | ✅ enumerated explicitly, plus the `NoRulesConfigured` override across all four |
| 8 | Stats/log match real traffic | ⚠️ **needs a real device** — logic verified against real SQLite, but the plan's criterion is a manual on-device session |

### Verified, not assumed
- **102 tests pass, `assembleDebug` succeeds** with Phases 1 + 5/5b + 6 + 8 and
  Phase 3/4's domain types compiled together — currently the only evidence these
  phases integrate at all.
- Verified via a **throwaway `scratch/verify-phase-6-8` branch** (Phase 0's own
  technique) that temporarily vendored Phase 3/4's unpushed files, because this
  branch cannot compile alone and CI is still the only compiler. Branch deleted.
- **It caught a real bug**: the day-boundary test asserted 2 where 1 was correct
  (`20:30Z` is 23:30 *on the 15th* in Nairobi — yesterday). Code was right, test
  was wrong.

### 🔴 Raised — collisions no session can see from inside its own worktree
- **TWO MONEY TYPES.** Phase 2 ships `Money` + `amountCents: Long`; Phase 3/4
  ships `KshAmount` value class. Both chose integer cents, so the data agrees —
  but they will not compile together. **Recommendation: `KshAmount` wins, Phase 2
  adapts.** Unresolved; owned by whoever merges those two.
- **THREE ROOM DATABASES.** Phase 5b's `data/AppDatabase.kt` (pushed first) wins.
  Phase 8's duplicate was **deleted and its entity moved into it**. Phase 3/4
  still has a competing `data/db/ScopeSmsDatabase.kt` — same filename Phase 8 had
  — that must be dropped the same way.
- **Phase 7 is blocked.** It wires every screen to Phases 1–6, which live across
  four unmerged branches; Phase 3/4 had pushed nothing and existed only as
  uncommitted files. Building screens against signatures still being edited
  produces code that can't compile or be reviewed. Phase 7 should start once
  2, 3/4 and 5 are on `main`.
- **Toggle defaults still unconfirmed with the client** (open decision 4).
  Shipped with BUILD-PLAN's recommendation (unmatched=ON, matched=OFF) as a
  *pinned placeholder* — safe because a fresh install has no rules, so nothing can
  send regardless. One constant + two tests to change.

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
