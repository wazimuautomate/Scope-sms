# changelog.md

Dated, terse session outcomes. Not a copy of git log.

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
