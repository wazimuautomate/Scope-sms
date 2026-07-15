# changelog.md

Dated, terse session outcomes. Not a copy of git log.

---

## 2026-07-16 — Phases 3 & 4: Rules engine, in-memory cache, template engine ✅

**Branch:** `feature/phase-3-4-rules-templates`
**State:** both phases complete, all exit criteria met, CI green
(run 29452548293). Not yet merged to `main`.

### Built
- **Phase 3 — rules engine.** `PricingRule` entity + DAO + repository (Room is
  the source of truth). `RuleSnapshot`: immutable, indexed, O(1) `classify()`,
  lock-free reads.
- **Phase 4 — template engine.** One `MessageTemplate` per flow, shared
  substitution engine, `SmsSegments` GSM-7/UCS-2 segment counting for the
  editor's cost hint. Defaults ship in code, not seeded into Room.
- **`SnapshotCache`** — process-scoped, fed from Room's `Flow` so any write from
  any phase updates it. Shared by both caches.
- **Manual DI** (`AppContainer`) — the decision Phase 0 deferred to whoever
  needed it first. Now settled; see `di/README.md`.
- **Room schema v1 committed** + a CI step publishing `app/schemas/` as an
  artifact, since there's no local build to generate it.

### Exit criteria — met
| Criterion | Result |
| --- | --- |
| P3: exact-match / no-match / duplicate-amount tests | ✅ 13 in `RuleSnapshotTest` |
| P3: cache and Room stay in sync after CRUD | ✅ 13 in `RoomCacheSyncTest`, real in-memory Room |
| P3: lookup is a map access, not a DB round trip | ✅ proven by matching *after* `db.close()` |
| P4: substitution correct for both template types | ✅ 18 in `TemplateEngineTest` |
| P4: no crash on a missing variable | ✅ renders readable text, never a raw token |
| CI green | ✅ **83 tests, 0 failures, 0 skipped** |

### Decisions (full rationale in `memory.md`)
- 🔴 **`MatchOutcome` is three-way**, not a nullable rule. "No rules configured"
  ≠ "nothing matched" — conflating them makes a fresh install text *every*
  paying customer an empty price list.
- 🔴 **Money is `KshAmount` (Long cents)**, app-wide. Equality matching rules out
  floats; `Ksh20.50` must not match the Ksh20 bundle. **Phase 2's parser should
  return this type** — flagged as an integration seam.
- 🔴 **`awaitLoaded()` is the only way to get a snapshot for a send decision**,
  closing the cold-start window where an SMS arrives before Room's first read.
- **Manual DI**, not Hilt. **Duplicate rule amounts**: most-recent-wins, and
  reported to the UI. **Templates ship defaults; rules deliberately don't.**

### Verified, not assumed
- The Robolectric suite genuinely ran against real Room — 13 tests, 9.4s, 0
  skipped. A green build that had skipped it would have proven nothing.
- **Room 2.8.4 / KSP 2.3.10 / Robolectric 4.16.1 / Truth / coroutines 1.11.0
  resolve and work** under AGP 9 — Phase 0 flagged these pins as researched but
  unexercised. They're now proven.
- Phase 0's `ArchitectureGuardTest` still passes: no `SmsManager`, no `SEND_SMS`.

### Fixed during the session (3 red CI runs, all self-inflicted)
- **Kotlin block comments nest** — `app/schemas/*.json` in a KDoc opened a
  nested comment and swallowed the file.
- **`object` properties initialise in declaration order** — `GSM_EXTENDED` read
  `FORM_FEED` before it was declared.
- **Truth's `containsExactly()` returns `Ordered`, not void** — expression-bodied
  tests ending in it aren't `Unit`, so JUnit4 killed the whole class with a
  bare `InvalidTestClassError`.

### Raised for the client / next sessions
- ⚠️ **Robolectric needed no JDK 21 bump after all** — pinned `@Config(sdk =
  [30])` instead. CI stays on 17. Phase 0's warning is updated, not deleted.
- ⚠️ **Phase 5b/8:** after adding an entity, download the `room-schemas-<run>`
  CI artifact and commit the new JSON, or the next migration has no baseline.
- ⚠️ **Phase 7:** `RuleSnapshot.duplicateAmounts` needs surfacing on the rules
  screen; `TemplateEngine.validate()` is there for the editor to call on save.

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
