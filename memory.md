# memory.md — running technical memory

> Read with `CLAUDE.md` and `changelog.md` at the start of every session.
> Decisions and rationale, gotchas, per-phase state, open questions.
> Prune stale "in progress" notes once superseded.

---

## Phase status

| Phase | Scope | State |
| --- | --- | --- |
| **0** | Repo, scaffolding & CI pipeline | ✅ **Done** — CI green, APK artifact verified downloadable |
| 1 | Permissions & SIM identification | Not started |
| 2 | SMS ingestion & M-Pesa parser | Not started |
| 3 | Rules engine + in-memory cache | Not started |
| 4 | Two message template types | Not started |
| 5 | SCOPE SMS gateway client | Not started |
| 5b | Outbound queue & burst-speed | Not started |
| 6 | Independent notification toggles | ✅ **Done** — `feature/phase-6-7-8-toggles-ui-log`, verified green |
| 7 | Compose UI | ⛔ **Not started — blocked.** See "Phase 7 is blocked" below |
| 8 | Activity log & dashboard stats | ✅ **Code done** — same branch. Exit criterion needs a real device |
| 9 | Reliability hardening | Not started |
| 10 | Cross-version testing | Not started |
| 11 | Release packaging & distribution | Not started |

---

## 🔴🔴 PARALLEL-SESSION COLLISIONS — read before merging anything to main

Found by Phase 6/8 on 2026-07-16 while reading the other branches. **No single
session can see these from inside its own worktree**, which is exactly why they
went unnoticed: every phase is being built in isolation against a plan that
assumed sequential work. Whoever merges to `main` first will be fine; everyone
after inherits the mess.

### 1. 🔴 TWO MONEY TYPES — unresolved, and the worst of the three
The whole app hinges on `payment.amount == rule.amount`. There are currently
**two incompatible representations of money**, written independently, each with
a thorough doc-comment explaining why it is right (they are both right; they are
just not the same):

| Phase | Type | Shape |
| --- | --- | --- |
| 2 (`domain/parser/`) | `Money` object + `MpesaPayment.amountCents: Long` | raw `Long` cents |
| 3/4 (`domain/money/`) | `KshAmount` `@JvmInline value class` | wrapped `Long` cents |

Both chose **integer cents**, so the underlying data agrees and this is a type
mismatch, not a correctness bug — but it will not compile once the branches meet:
Phase 3's `RuleSnapshot.classify(amount: KshAmount)` cannot be handed Phase 2's
`Long`. `KshAmount`'s own doc already claims the role ("this is the canonical
money type across the app. Phase 2's parser should produce a KshAmount rather
than a number") — Phase 2 just never saw that file.

**Recommendation: `KshAmount` wins, Phase 2 adapts.** It is the richer type, it
is what Phases 3/4/8 already carry, and it is a `value class` so it costs nothing
at runtime on the SMS hot path. The change to Phase 2 is small: `MpesaPayment
.amountCents: Long` → `amount: KshAmount`, and `Money` deleted. Phase 8's
`ActivityRecord` already carries `KshAmount`.
**Owner: whoever merges Phase 2 and Phase 3/4 — do not let both land unreconciled.**

### 2. ✅ THREE ROOM DATABASES — resolved for Phase 8, still live for Phase 3/4
Three sessions each wrote "the app's Room database", each correctly reasoning it
was the first to need one:

| Phase | File | Status |
| --- | --- | --- |
| 5b | `data/AppDatabase.kt` | **Pushed first — this is the one that wins** |
| 8 | `data/db/ScopeSmsDatabase.kt` | **Deleted.** Phase 8 yielded; entity moved into `AppDatabase` |
| 3/4 | `data/db/ScopeSmsDatabase.kt` | ⚠️ **Still live in their worktree — same filename Phase 8 used** |

Phase 8 has already registered `ActivityLogEntity` + `activityLogDao()` in
`data/AppDatabase.kt`. **Phase 3/4 must do the same and delete their
`ScopeSmsDatabase.kt`** — its doc block invites exactly this ("keep both entity
lists, keep both DAO accessors"). Two databases = two SQLite files, two
connections, and no transaction able to span a rule change and its log row.

Note Phase 3/4 also has its own `di/AppContainer.kt` edits; Phase 8's version on
this branch already wires `activityLog`. Union the two, don't pick one.

### 3. ⚠️ `versionName` still says `0.1.0-phase0`
Cosmetic, but it ships in Settings (Phase 11). Nobody owns bumping it yet.

---

## Phase 7 is blocked (and why it was not attempted)

Phase 7 wires every screen to the Phase 1–6 data layers. On 2026-07-16 those
layers were spread across four unmerged branches, two of which (3/4) had not been
pushed at all and existed only as uncommitted files in a private worktree.

Building the Rules and Templates screens against `PricingRule`/`MessageTemplate`
signatures that are still being actively edited would produce code that cannot
compile, cannot be reviewed, and would need rewriting the moment those branches
land. **Phase 7 should start once Phases 2, 3/4 and 5 are merged to `main`** —
it is the integration layer and is genuinely last by nature, regardless of its
number in the plan.

What Phase 7 can rely on from this branch when it does start:
- `NotificationToggles` + `SettingsRepository.notificationToggles` for the
  dashboard's two toggles (BUILD-PLAN wants them on Home, not buried in Settings).
- `ActivityLogRepository.recent` / `.search(...)` / `.statsForToday()` for the
  log screen and the four dashboard tiles, all as `Flow`s that re-emit when the
  receiver writes from a background process start.
- `RuleSnapshot.duplicateAmounts` (Phase 3) — the rules screen is supposed to warn
  when two bundles share a price.
- `GatewayCredentialsProvider` (Phase 5) is **still an unimplemented port**, and
  open decision 1 (API-key storage) is still open. Phase 7's Settings screen owns
  both — see below.

---

## 🔴 Open decisions — resolve before the owning phase ships

### 1. How to store the gateway API key (blocks Phase 5/7) — IMPORTANT
`androidx.security:security-crypto` (`EncryptedSharedPreferences`) is the
obvious choice and **it is a dead end**. Version 1.1.0 is "stable", but *every
API in it was deprecated at 1.1.0-beta01* — it shipped stable purely as a
final landing point. Google's own note: *"Deprecated all APIs in favour of
existing platform APIs and direct use of Android Keystore."*

It also has known **keyset-corruption crashes on specific OEM devices** —
which is precisely this app's target market (Tecno/Infinix/itel/Xiaomi), and
a corrupted keyset means the agent's gateway credentials are unrecoverable and
replies stop going out.

Recommended path (minSdk 30): **DataStore for persistence + an Android
Keystore-held `AES/GCM` key to encrypt the value yourself.** Tink if a
higher-level primitive is wanted. There is a community fork
(`dev.spght:encryptedprefs-ktx`) offering drop-in `EncryptedSharedPreferences`
semantics — **unverified**, maintenance health unchecked; verify before
adopting.

Whoever picks: record the choice here, and make sure the failure path is
handled — a decrypt failure must prompt re-entry in Settings, not crash or
silently stop sending.

### 2. targetSdk 36 vs 37 (owned by Phase 10)
Currently `compileSdk = 37`, `targetSdk = 36`. See "SDK levels" below for the
full reasoning. Phase 10 should evaluate 37 against a real Android 17 device.
CLAUDE.md constraint 1 says "target latest stable", so this is a **deliberate,
flagged deviation**, not an oversight.

### 3. DI: manual vs Hilt (owned by whichever phase first needs it — likely 3)
Phase 0 established **neither**, deliberately — a scaffold with one Activity
has nothing to inject and guessing wrong forces a later unpick. CLAUDE.md says
"whichever is already established... check memory.md" — so: **nothing is
established yet. First phase that needs it decides and records it here.**
Constraint to respect: a `BroadcastReceiver` is constructed by the system, so
the object graph must be reachable from process scope. See
`app/src/main/java/com/scopesms/autoreply/di/README.md`.

### 4. Default state of the two notification toggles (owned by Phase 6)
BUILD-PLAN explicitly says confirm with the agent, don't assume. Starting
recommendation in the plan: unmatched=ON (the original pain point),
matched=OFF (higher volume, sender-ID ban risk). **STILL UNCONFIRMED — the
client has not answered.**

**Phase 6 status (2026-07-16):** shipped with the plan's recommendation as
`NotificationToggles.DEFAULT`, because the code needs *some* value on first
launch. This is **not** the question being answered — it is a placeholder with a
test pinning it (`ReplyDecisionTest.default toggles are unmatched-on
matched-off`) so that changing it is a decision rather than a drift.

**Why it was safe to ship un-confirmed:** a fresh install has no rules, so every
payment classifies as `MatchOutcome.NoRulesConfigured` and `decideReply` returns
`NoRulesConfigured` regardless of the toggles. The default physically cannot text
a customer before the agent enters prices. Pinned by
`default cannot text anyone before the agent enters prices`.

**To change it:** edit `NotificationToggles.DEFAULT` + the two tests. One place.

### 5. Real M-Pesa sample messages (blocks Phase 2)
We have **exactly one** real till-confirmation sample (in CLAUDE.md).
BUILD-PLAN Phase 2 requires 5–10 more real redacted samples from the agent
before the regex is finalised. **This is a hard blocker for Phase 2** — one
sample cannot validate variant handling. Someone needs to ask the client.

---

## Decisions made in Phase 0 (and why)

### Toolchain — pinned, CI-verified
```
AGP 9.2.1 · Gradle 9.4.1 · Kotlin 2.2.10 · Compose BOM 2026.06.01 · JDK 17
minSdk 30 · compileSdk 37 · targetSdk 36
```
All versions verified against primary sources on 2026-07-15, not recalled.
**Do not bump from memory** — there is no local build, so a bad pin is only
found by a red CI run.

### 🔴 AGP 9 has built-in Kotlin — do NOT apply `org.jetbrains.kotlin.android`
The single biggest trap in this toolchain. AGP 9.0+ compiles Kotlin itself.
Applying the `kotlin.android` plugin **hard-fails**:
> "The 'org.jetbrains.kotlin.android' plugin is no longer required for Kotlin
> support since AGP 9.0."

Consequences that will bite anyone pasting from an AGP 8-era tutorial:
- Only `org.jetbrains.kotlin.plugin.compose` is applied. Its version must
  **equal** the Kotlin version.
- `kotlin-kapt` is **incompatible** with built-in Kotlin → **use KSP** (Room
  already plans to).
- `android { kotlinOptions { } }` is gone → `kotlin { compilerOptions { } }`.
  Phase 0 omits that block entirely; AGP aligns jvmTarget with
  `compileOptions` on its own. If a "jvmTarget mismatch" ever appears, that
  block is the fix.

### Why Kotlin 2.2.10 and not 2.4.10 (latest)
`2.2.10` is **AGP 9.2.1's own bundled compiler** — verified from AGP's POM.
Pinning a different version in the catalog does nothing; you'd have to
override the buildscript classpath, via a mechanism that is poorly documented.

More to the point: Kotlin's official compatibility table only certifies KGP up
to **AGP 9.1.0**, so *no* Kotlin release is certified against AGP 9.2 — the
newest-everything combo is no safer, just less tested. AGP 9.2.1 + its bundled
2.2.10 is the one combination Google actually tests. Nothing this app does
needs a 2.3/2.4 language feature. Revisit only with a concrete reason.

### Why AGP 9.2.1 and not 9.2.0 or 9.3.0
- **Not 9.2.0** — known R8 bug: `ClassNotFoundException: Didn't find class
  "com.android.tools.r8.RecordTag"`. Fixed in 9.2.1.
- **Not 9.3.0** — stable, but days old, and would pull Gradle 9.5.0. Nothing
  here needs it (constraint 9: correctness over speed of delivery).
- Gradle version **must track AGP**: 9.2.x→9.4.1, 9.3.0→9.5.0. Gradle's own
  latest is 9.6.1 — don't exceed 9.5.0 without re-checking Kotlin's cap.

### SDK levels: compileSdk 37, targetSdk 36
`compileSdk 37` is **forced**: current AndroidX (core-ktx 1.19.0, activity,
lifecycle) refuses to build against less, failing with *"requires libraries and
applications that depend on it to compile against version 37 or later."* CI
proved this — the first run failed exactly there. 37 is also AGP 9.2's ceiling.

`targetSdk 36` is a **judgement call, flagged for Phase 10**. Android 17 (API
37) went stable ~16 June 2026, about a month ago. targetSdk is what opts into
new *runtime* behavior, and Android 17's changes are untested here — the
categories Android keeps tightening (background execution, broadcast delivery,
telephony permissions) are exactly the path between "customer pays" and
"customer gets a reply". No API 37 behavior is needed, and direct-APK
distribution means Play's targetSdk deadline doesn't apply. The two are
independently settable; this takes the forced half and declines the risky half.

### Architecture guard tests instead of a trivial one
BUILD-PLAN Phase 0 only requires a test step that "passes trivially".
`ArchitectureGuardTest` does real work instead: it fails the build if
`SEND_SMS` is ever declared or `SmsManager` is ever referenced in `src/main`.

Rationale: phases are being built by **parallel sessions** against a plan that
was rewritten mid-flight. The pre-pivot design sent via `SmsManager`, and most
Android SMS tutorials do too — a session reaching for it out of habit is a
realistic mistake that would silently send replies from the agent's personal
number instead of the sender ID. Prose in CLAUDE.md can't catch that; a red CI
run can.

**Verified to actually fail**, not just to pass: a scratch branch
(`chore/verify-architecture-guard`, since deleted) added `SEND_SMS` and
confirmed CI went red. A guard that has never failed is not known to guard.

The class includes a fixture test asserting the manifest/source paths resolve —
without it, a working-directory change would make every other assertion pass
vacuously.

### Other Phase 0 choices
- **`allowBackup=false`** + backup/data-extraction rules excluding everything.
  The DB will hold customer PII and the prefs the gateway API key. Also avoids
  restoring Keystore-encrypted prefs onto a handset that can't decrypt them.
  **Consequence:** the agent switching phones loses rules/templates and
  re-enters credentials. If that becomes a complaint, the answer is an explicit
  in-app export/import — not re-enabling backup.
- **`queue/` is a top-level package.** BUILD-PLAN allowed it under
  `data/`+`domain/`; it's the app's critical reliability boundary and deserves
  to be obvious. Be consistent.
- **No permissions in the manifest yet** — Phase 1 owns the permission set.
  Kept out to avoid merge conflicts with that parallel session.
- **Every package has a README** naming its owning phase and constraints —
  because parallel sessions won't read each other's code.
- **Wrapper jar is committed** (CI runs `./gradlew` and has no other bootstrap).
  Verified against Gradle's published sha256, distribution pinned via
  `distributionSha256Sum`, and re-validated in CI by
  `gradle/actions/wrapper-validation` on every run.
- **Gradle configuration cache left off.** Supported, but Phase 0 optimised for
  a build that works; KSP/Room are the likely trip hazards. Revisit once the
  dependency graph settles.

---

## Decisions made in Phases 6 & 8 (and why)

### Toggles live in Phase 1's `SettingsRepository` — forced, not chosen
DataStore permits **exactly one instance per file per process**; a second racing
the first corrupts it, and Phase 1's `preferencesDataStore` delegate is private to
that file's companion. A separate `NotificationSettingsRepository` was therefore
never an option. Both keys read from one `Preferences` snapshot, so the pair is
always internally consistent.

`NotificationToggles` is a **pair, not two loose booleans**: the decision path
reads both while classifying one payment, and reading them independently would
let a toggle flip mid-decision and produce an outcome matching neither the old
settings nor the new. Rare, unfalsifiable after the fact, and it would surface as
"it texted a customer after I turned it off".

### `decideReply()` is a pure function, deliberately
It is the rule that decides whether a paying customer gets a text, so it lives in
`domain/` as a total function over `(MatchOutcome, NotificationToggles)` with no
I/O and no Android types — testable exhaustively on the JVM in milliseconds. The
`when` is exhaustive over `MatchOutcome`'s three arms, so adding a fourth arm
later **fails the build here** instead of silently falling through to "send
nothing", which is the failure mode that would cost the agent customers quietly.

### `NoRulesConfigured` beats both toggles
Checked *before* the toggles. With an empty price list `{bundle_list}` renders
empty, so an "unmatched" reply would text a paying customer a blank price list.
The toggle says "the agent wants this flow"; the empty list says "there is
nothing truthful to send yet". The latter wins.

### Stats are computed in SQL, not in Kotlin
The log grows without bound, the dashboard is the first screen drawn on launch,
and the tiles are four integers — no reason to move rows across the JNI boundary
to count them. Note `COALESCE`: **`SUM()` over zero rows is `NULL` in SQLite, not
0**, which would crash a fresh install on its first screen. Pinned by
`an empty log reads as zeroes rather than crashing on null sums`.

### "Today" is the agent's local day
Nairobi is UTC+3. A UTC-based boundary would roll the dashboard over at **3am
local** and show a busy morning's work as yesterday's. `ActivityLogRepository`
takes an injectable `Clock` so this is testable without waiting for midnight.

### Log dedupe: unique index + `INSERT … ON CONFLICT IGNORE`, first write wins
Some OEMs redeliver `SMS_RECEIVED` (BUILD-PLAN Phase 5b names this). Phase 5b
dedupes the *send* on `transactionCode`; Phase 8 dedupes the *log* on the same
key, so one payment is one row however many times Android hands it to us.
**First write wins** because the first decision is the one the queue acted on.
`record()` returns `false` on a duplicate — Phase 5b can use that as its
log-side guard.

### Enums stored by `name`, not ordinal
An ordinal column silently re-points every historical row if someone inserts an
enum constant in the middle — the agent's history becomes fiction with no error
anywhere. `toRecord()` also degrades an unrecognised string to a safe value
rather than throwing: a row written by an older build should render vaguely, not
crash-loop the activity screen with no way back.

---

## How Phase 6/8 was verified without a local build (reusable technique)

There is still **no local JDK and no Android Studio** on this machine — checked,
not assumed (`java` is not on PATH; the Android SDK exists but nothing can drive
it). CI remains the only compiler, exactly as Phase 0 recorded.

That is a problem for an integration-layer phase: this branch imports Phase 3/4's
`KshAmount`/`MatchOutcome`/`TemplateType`, and Phase 3/4 had pushed nothing, so
the branch could not compile on its own and CI could not tell "your code is
wrong" from "your dependency is missing".

**Solution — a throwaway scratch branch**, reusing Phase 0's own precedent (it
proved `ArchitectureGuardTest` fails by adding a real violation on a scratch
branch, since deleted): branch off the work, copy Phase 3/4's in-flight domain
files in *temporarily*, push, read CI, delete the branch. `scratch/verify-phase-6-8`
existed for ~15 minutes and is gone.

**It paid for itself immediately** — it caught a real bug in the day-boundary
test (asserted 2 where the correct answer was 1; `20:30Z` is 23:30 *on the 15th*
in Nairobi, i.e. yesterday, not "02:30 on the 16th" as the comment claimed). The
code was right and the test was wrong, which is the failure you cannot find by
re-reading your own test.

**Result: 102 tests pass, `assembleDebug` succeeds** with Phase 6 + 8 + Phase 5/5b
+ Phase 1 + Phase 3/4's domain types all compiled together. That is currently the
only evidence in the project that these phases integrate at all. Recommend the
next integration session do the same before merging.

---

## Gotchas discovered (save the next session the debugging)

### Windows authoring → Linux CI: `gradlew` line endings
Repo is authored on Windows, built on Linux runners. Without `.gitattributes`
forcing LF, `gradlew` checks out with CRLF and CI dies on the shebang:
`bad interpreter: sh^M: no such file or directory`. `.gitattributes` handles
it; `gradlew` is also committed mode `100755`. **Don't "fix" .gitattributes.**

### ✅ Robolectric + JDK 21 — DONE, Phase 8 made the bump (was: "will bite Phase 2")
**Resolved 2026-07-16.** Phase 8 was the phase that first needed Robolectric (its
DAO tests run real SQLite), so `build.yml` now provisions **JDK 21**.
`compileOptions`/`jvmTarget` stay at **17** — 17 is AGP's minimum, not its
maximum, so a 21 toolchain emits 17 bytecode and the app's floor is unchanged.
**Verified green in CI**, so the predicted `UnsupportedClassVersionError` is
behind us and no future phase needs to re-solve this.

Also added for Robolectric: `testOptions { unitTests { isIncludeAndroidResources
= true } }` in `app/build.gradle.kts`. Without it Robolectric fails at startup
rather than on an assertion.

Standing advice unchanged: **prefer JVM-pure tests.** The parser, rules and
template engines are pure Kotlin by design (`domain/`) and need no Robolectric.
Phase 8 uses it only where Android is genuinely required — Room's SQL is a string
until something executes it, and a wrong column name or a bad boolean-sum idiom
compiles fine and returns confidently wrong numbers on the agent's dashboard.
Phase 6's `decideReply` gate is JVM-pure and has no Robolectric anywhere.

### KSP versioning scheme changed (relevant Phase 3+)
KSP moved to **independent versioning at 2.3.0** — the old
`<kotlin>-<ksp>` format (e.g. `2.0.21-1.0.25`) is gone. Any doc still
describing it, *including KSP's own `docs/ksp2.md`*, is stale.
**Hard requirement: AGP 9 needs KSP ≥ 2.3.6.** Catalog pins `2.3.10`.
KSP 2.3.x is not tied to Kotlin 2.3 — it works with Kotlin 2.2.x.

### Retrofit 3 ships OkHttp 4.12 (relevant Phase 5)
Catalog pins OkHttp `5.4.0`, which is a **deliberate override** of Retrofit
3.0.0's default 4.12 (binary-compatible). MockWebServer's coordinate was
renamed in OkHttp 5.x → `mockwebserver3-junit4`. Retrofit 3 also added a
transitive Kotlin dependency.

### GitHub Actions majors moved a lot
`checkout@v7`, `setup-java@v5`, `upload-artifact@v7`,
`gradle/actions/setup-gradle@v6`. Verified via the GitHub Releases API on
2026-07-15. `gradle/gradle-build-action` is **deprecated** — use
`gradle/actions/setup-gradle`. Don't pass `arguments:` to it (removed after
v3); use a separate `run: ./gradlew …` step. Note `gradle/actions` v6 changed
how the caching component is licensed — worth a glance for commercial use.

### Version catalog contains unverified entries
Everything under "later phases" in `gradle/libs.versions.toml` (Room,
WorkManager, DataStore, Retrofit, OkHttp, Moshi, Robolectric, Truth) is
researched but **not exercised by any build** — Gradle never resolves an unused
entry, so a wrong pin stays silent until first use. The phase that first uses
one confirms it resolves.

---

## Notes on the reference material (local only, deleted after Phase 8)

`bingwa-auto-reply/` — the Google AI Studio UI generation. Git-ignored: it's a
**separate, unrelated Gradle project** and committing it would nest a second
Android build in the repo. It is a **UI reference only** and is *not* a
suitable base:
- `minSdk 24` (we need 30), `namespace com.example`, applicationId
  `com.aistudio.bingwasokoni.jwyhsq`.
- Pulls **Firebase, Retrofit, OkHttp, and firebase-ai** — pre-pivot noise.
- Its `SmsReceiver` and manifest declare **SEND_SMS** — the old architecture.
  Copying from it would trip `ArchitectureGuardTest`, which is working as
  intended.

**Two real bugs in it — do not copy forward:**
1. Its `darkColorScheme` is built from the *light* neutrals
   (`background = 0xFFFDF8F9`), so **dark mode renders light**.
   `ui/theme/Color.kt` here already fixes this.
2. It fetches fonts from Google Fonts **at runtime** — a network call on first
   render and a visible font swap on low-end devices. Bundle a font locally if
   Phase 7 wants one.

`app-icons/` — git-ignored; the launcher icons have **already been copied** into
`app/src/main/res/mipmap-*/` (48/72/96/144/192 → mdpi…xxxhdpi). The 512px is a
store icon, unused (no Play Store).
⚠️ **These are legacy flat PNGs — there is no adaptive icon.** With minSdk 30
every device supports adaptive icons, so some launchers will mask or letterbox
the flat PNG. A proper fix needs separate foreground/background layers (108dp
with a 66dp safe zone), which the flat export can't be split into
automatically. Cosmetic; worth raising with the client before Phase 11.

---

## Deviations from the build plan (per workflow rule 7)

### Phase 8 — two enum values added to the plan's schema
BUILD-PLAN Phase 8 specifies `matchType[MATCHED|UNMATCHED]` and
`notifyStatus[SENT|SILENT|FAILED]`. Both were extended by one value:

- **`MatchType.NO_RULES_CONFIGURED`.** A payment arriving before the agent enters
  prices is neither matched nor unmatched. Forcing it into `UNMATCHED` would be a
  lie in the agent's only diagnostic — it would read as "a customer paid the wrong
  amount" when what happened is "the app isn't set up". Different fixes, so
  different rows. This mirrors Phase 3's `MatchOutcome`, which is *also* three-way
  for the same reason — the plan's two-way schema simply predates that decision.
- **`NotifyStatus.QUEUED`.** Sending is asynchronous (Phase 5b writes a job, a
  worker drains it). Between the decision and the gateway's answer there is a real
  observable state that is none of `SENT|SILENT|FAILED`. Without it the log must
  either claim `SENT` before the gateway agreed, or hide the row until it
  resolves — and a reply stuck behind a dead network would then be invisible in
  the one place the agent looks.

Both are strictly additive; the plan's values all still exist and mean what it says.

### Phase 6/7/8 — branch is stacked, not cut from `main`
Workflow rule 2 says branch off up-to-date `main`. This branch is cut from
`feature/phase-1-permissions-sim` and then **merges `feature/phase-5-gateway-queue`**.
`main` has only Phase 0, and Phases 6/8 cannot exist without Phase 1's DataStore
and Phase 5b's `AppDatabase` — the plan's own numbering makes these phases
dependent, and parallel sessions made them unmerged. **Merge order matters:
Phases 1, 2, 3/4 and 5 must reach `main` before this branch.**

### Phase 7 — not attempted this session
See "Phase 7 is blocked" above. Not a deviation from the plan's content, but it
is a deviation from this session's brief, and the reason is recorded rather than
silently absorbed.

### Pre-existing
1. **`targetSdk 36`, not "latest stable" (37)** — reasoned above, flagged for
   Phase 10. This is the only deviation from a stated constraint.
2. **Phase 0's test step exceeds the plan.** The plan permits a trivially
   passing test; we ship real architecture guards instead. Strictly more than
   asked for, but justified by the parallel-session risk.
3. **Doc filenames don't match the docs' own references.** `CLAUDE.md` and
   `BUILD-PLAN.md` both refer to **`02-BUILD-PLAN.md`** (actual file:
   `BUILD-PLAN.md`) and **`01-UI-DESIGN-PROMPT.md`**, which **does not exist in
   the repo at all**. The UI spec Phase 7 is told to implement is therefore
   missing — the only UI reference is the local `bingwa-auto-reply/` folder,
   which is itself out of date vs. the pivot. **Phase 7 will need this
   resolved.** Files were left un-renamed deliberately: parallel sessions were
   given the current names, and renaming mid-flight would break them.
