# memory.md — running technical memory

> Read with `CLAUDE.md` and `changelog.md` at the start of every session.
> Decisions and rationale, gotchas, per-phase state, open questions.
> Prune stale "in progress" notes once superseded.

---

## Phase status

| Phase | Scope | State |
| --- | --- | --- |
| **0** | Repo, scaffolding & CI pipeline | ✅ **Done** — CI green, APK artifact verified downloadable |
| 1 | Permissions & SIM identification | 🔨 In progress (parallel session) — code on `feature/phase-1-permissions-sim` |
| 2 | SMS ingestion & M-Pesa parser | Not started |
| 3 | Rules engine + in-memory cache | Not started |
| 4 | Two message template types | Not started |
| 5 | SCOPE SMS gateway client | Not started |
| 5b | Outbound queue & burst-speed | Not started |
| 6 | Independent notification toggles | Not started |
| 7 | Compose UI | Not started |
| 8 | Activity log & dashboard stats | Not started |
| 9 | Reliability hardening | 🟡 **Code done, CI green — exit criteria UNMET.** Every one needs a real device. See below. |
| 10 | Cross-version testing | Not started |
| 11 | Release packaging & distribution | Not started |

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
matched=OFF (higher volume, sender-ID ban risk). **Still unconfirmed.**

### 5. Real M-Pesa sample messages (blocks Phase 2)
We have **exactly one** real till-confirmation sample (in CLAUDE.md).
BUILD-PLAN Phase 2 requires 5–10 more real redacted samples from the agent
before the regex is finalised. **This is a hard blocker for Phase 2** — one
sample cannot validate variant handling. Someone needs to ask the client.

---

## Phase 9 — reliability hardening (branch `feature/phase-9-reliability-hardening`)

**Built on `feature/phase-1-permissions-sim`, not `main`** — a deliberate break
from workflow rule 2, because Phase 9 consumes Phase 1's `SettingsRepository`,
`SimReader` and `BatteryOptimizationManager`, and `main` has only Phase 0. There
was nothing on `main` to build against. **Merge order: Phase 1 first, then this.**

CI green (run 29452472972): 64 tests, 0 failures, APK artifact built. 29 of those
tests are Phase 9's.

### 🔴 Exit criteria are NOT met, and cannot be met from CI
Every one of Phase 9's exit criteria is real-device work, and **none has been
run**: the 24-hour idle soak on a Transsion device, the reboot pass, and the
airplane-mode queue test. What exists is the code they will exercise. **Do not
mark Phase 9 done on a green CI run** — a green run here means "it compiles and
the pure logic is right", which is precisely the half of this phase that was
never in doubt.

The airplane-mode criterion is also **blocked on Phase 5b** — there is no
outbound queue to test yet.

### DEVIATION: the plan's two boot-check tasks were already solved by Phase 1
BUILD-PLAN Phase 9 asks the boot receiver to "re-verify battery-exemption status
and that saved SIM subscription IDs are still valid after reboot". Neither is
possible, because Phase 1 persists neither:

- **Subscription IDs are never stored.** `SimSelection` stores the agent's choice
  by *physical slot*, and its KDoc cites this exact Phase 9 line as the reason.
  The reorder the plan fears cannot corrupt the setting.
- **Exemption status is never stored.** `BatteryOptimizationManager.isExempt()`
  reads `PowerManager` live and explicitly refuses to cache, so there is no stale
  copy to re-verify.

Phase 1 read the plan and designed the problem away — the right outcome, worth
noticing rather than papering over with a check that re-validates nothing.
`ReliabilityCheck` therefore checks the **equivalent conditions that can still
happen**, which is what the plan was actually reaching for:

| Checked | Why it matters |
| --- | --- |
| Watched slot holds no SIM | **The headline case.** Agent moves the business SIM to the other tray → `SimFilter` correctly drops *every* message as `UNWATCHED_SIM` → app looks perfectly healthy while replying to nobody. |
| Required permission revoked | Android 11 — **our minSdk** — auto-resets permissions for unused apps. An agent back from a long trip has a configured-looking app holding no SMS permission. |
| No SIM readable | Blocking, obviously. |
| Battery exemption missing | `DEGRADED`, not blocking: works awake, dies once the screen's been off. |

### Design decisions worth not relitigating
- **Pure logic in `domain/reliability/`, Android boundary in `reliability/`.**
  `ReliabilityCheck` takes a frozen `ReliabilitySnapshot` and returns issues, so
  all 16 of its tests run on the JVM. No Robolectric → **CI stays on JDK 17**
  (see the Robolectric/JDK-21 gotcha below). `reliability/` is top-level for the
  same reason `queue/` is: it's a reliability boundary and burying it hides it.
- **SIM findings are suppressed when READ_PHONE_STATE is denied.** `SimReader`
  returns an empty list for *both* "denied" and "no SIM" and cannot tell them
  apart. Believing it would tell an agent to reseat a perfectly good SIM while
  the real fault (the revoked permission) sits correctly diagnosed one line
  above. One fault must produce one true alarm.
- **A partially-present selection is left alone.** Watch {0,1}, pull SIM 2 → slot
  0 still ingests. That's a working app and pulling a SIM is usually deliberate.
  Only a selection with *nothing* behind it is an outage.
- **Notification channel `health` at IMPORTANCE_HIGH.** Intrusive on purpose: it
  only ever fires when the agent is already losing money, and it's self-limiting
  (silent when healthy, gone once fixed). **Phase 8's send-failure alerts must
  use a different channel** — muting "a reply failed" must not also mute "the app
  has stopped working".
- **Boot receiver has NO `android:permission`.** There is no
  `BROADCAST_BOOT_COMPLETED` permission (SMS has `BROADCAST_SMS`; boot has no
  equivalent). Naming one anyway is not harmless — a permission no caller can
  hold blocks the *system* too, so the receiver would never fire and the health
  check would silently never run. `BOOT_COMPLETED` is a protected broadcast in
  AOSP, so the platform provides the guarantee. `QUICKBOOT_POWERON` (non-AOSP,
  some OEM fast-boot builds) is *not* protected — hence the action check in code.

### 🔴 OEM autostart component names are UNVERIFIED — Transsion most of all
BUILD-PLAN is right that "no code fix solves this, only user settings + clear
instructions", so the design inverts the usual priority: **the written steps are
the contract; the deep links are a probed, disposable convenience.** Nothing
breaks when a link is absent — that is the expected case on most phones.

Manual paths are from `dontkillmyapp.com` (matches the OEMs' own UI wording).
Component names come from the maintained libraries (`judemanutd/AutoStarter`,
`chris-wolf/autostart_settings`, Threema, pano-scrobbler).

**The Transsion entries are the weakest evidence and the most important market.**
They appear in ~12 repos, but those repos largely copy one another — popularity,
not independent confirmation — and `AutoStarter`, the most-used of the set, has
**no Transsion entry at all**. No decompiled manifest proving the activity exists
and is exported could be found. **Someone must install a CI APK on the agent's
real Tecno/Infinix and see which candidate actually resolves.** Order tried:
`com.transsion.phonemaster/com.cyin.himgr.autostart.AutoStartActivity` → action
`…AUTO_START_ACTIVITY` → `com.transsion.phonemanager/…AutoBootMgrActivity` (itel's
separate app) → two weaker guesses → Phone Master's launcher.

Gotchas that cost real debugging if forgotten:
- **`<queries>` is mandatory, not hygiene.** Android 11+ package visibility makes
  `resolveActivity()` return `null` for any undeclared package — so a component
  missing from the manifest can never resolve, on any device, and it looks
  identical to "this phone doesn't have that screen". `OemAutostartGuideTest`
  fails the build on this; it already caught the missing
  `com.transsion.phonemanager`, i.e. itel's entire autostart path.
- **`OemSettingsLauncher.open()` catches `Exception`, not
  `ActivityNotFoundException`.** HiOS/XOS ship system activities that *resolve*
  but aren't exported and throw `SecurityException` on launch — a crash on
  exactly the handsets the screen exists for.
- **Match `Build.BRAND` + `Build.MANUFACTURER` together.** Transsion reports
  `MANUFACTURER` inconsistently; matching it alone drops real Tecnos into
  `GENERIC`.
- **OnePlus's `com.oneplus.security` chain-launch screen is deliberately absent** —
  reported broken from Android 11, which is this app's *minimum*, so it could
  never work for a single user. Modern OnePlus runs ColorOS anyway.
- **Huawei (PowerGenie, EMUI 9+) and Samsung ("Sleeping apps") have traps no deep
  link or exemption fixes.** Both carry a `caveat` string saying so, because an
  agent whose replies keep dying needs to be told the phone is the problem rather
  than retry steps that cannot work.

### Bug caught by its own test (worth remembering)
`watched.none { it in activeSlots }` is **vacuously true on an empty set**, so a
`SimSelection.Slots(emptySet())` fell into the missing-SIM branch and rendered
*"You told Scope SMS to watch , but those slots are empty."* Went red on the
first CI run and is now guarded. Near-unreachable (`decode()` maps empty →
DEFAULT) but an unreachable branch emitting a broken sentence is a bug waiting
for someone to make it reachable.

### Left for other phases, deliberately
- **"Malformed SMS: log and skip, never crash"** (a Phase 9 bullet) is **Phase
  2's** parser and receiver. Implementing it from here would collide head-on with
  that live session. Phase 2 owns it; this is a flag, not a hand-off.
- **UI is stateless composables only** (`ui/reliability/OemGuidanceSection.kt` —
  `OemGuidanceSection`, `ReliabilityIssueCard`). No screen, no ViewModel, no
  navigation: **Phase 7 owns screens** and is being built in parallel against a UI
  spec this session cannot see (`01-UI-DESIGN-PROMPT.md` is still missing from the
  repo). Wiring instructions are in the file's KDoc.

---

## 🔴 Process: parallel sessions are sharing ONE working directory

Phase 9's session found this the hard way and it will bite everyone until fixed.
All the "parallel" sessions are operating in the same checkout at
`c:\Users\ADMIN\OneDrive\Desktop\Scope sms`, which has **one git HEAD**. Mid-session,
another agent ran `git checkout -b feature/phase-2-sms-ingestion-parser`, which
**moved this session's branch out from under it** — Phase 9's uncommitted work
was then sitting on Phase 2's branch, mixed into Phase 2's manifest edits.

Nothing was lost (Phase 2's tree was handed back untouched, Phase 9 moved to a
worktree), but the next collision could silently commit one phase's work onto
another's branch, and neither session would notice.

**Use `git worktree` — one per session:**
```
git worktree add ../scope-sms-phase-N feature/phase-N-slug
```
Each session gets its own directory and its own HEAD; the shared `.git` still
holds every branch. It also *locks* the branch — a branch checked out in a
worktree cannot be checked out elsewhere, so the collision becomes impossible
rather than merely unlikely.

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

## Gotchas discovered (save the next session the debugging)

### Windows authoring → Linux CI: `gradlew` line endings
Repo is authored on Windows, built on Linux runners. Without `.gitattributes`
forcing LF, `gradlew` checks out with CRLF and CI dies on the shebang:
`bad interpreter: sh^M: no such file or directory`. `.gitattributes` handles
it; `gradlew` is also committed mode `100755`. **Don't "fix" .gitattributes.**

### 🔴 Robolectric needs JDK 21 against SDK 36+ (will bite Phase 2)
CI provisions **JDK 17** today, which is fine because nothing uses Robolectric
yet. Robolectric requires **JDK 21** to run tests targeting SDK 36+ (those SDK
jars are Java-21 compiled). The phase that first adds Robolectric must bump
`setup-java` to 21 while leaving `compileOptions`/`jvmTarget` at 17 — 17 is
AGP's *minimum*, not its maximum.

Better still: **prefer JVM-pure tests.** The parser, rules and template engines
are pure Kotlin by design (`domain/`), so they need no Robolectric at all.
That's the main safety net and it should stay fast.

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

1. **`targetSdk 36`, not "latest stable" (37)** — reasoned above, flagged for
   Phase 10. This is the only deviation from a stated constraint.
2. **Phase 0's test step exceeds the plan.** The plan permits a trivially
   passing test; we ship real architecture guards instead. Strictly more than
   asked for, but justified by the parallel-session risk.
3. **Phase 9's boot check doesn't do what the plan literally says** — it can't;
   Phase 1 persists neither the subscription IDs nor the exemption status the
   plan asks it to re-validate. It checks the equivalent live conditions
   instead. Full reasoning in the Phase 9 section above.
4. **Phase 9 branched from `feature/phase-1-permissions-sim`, not `main`**
   (workflow rule 2 says `main`). Phase 9 depends on Phase 1's classes and
   `main` has only Phase 0 — there was nothing to build against. Merge Phase 1
   first.
5. **Doc filenames don't match the docs' own references.** `CLAUDE.md` and
   `BUILD-PLAN.md` both refer to **`02-BUILD-PLAN.md`** (actual file:
   `BUILD-PLAN.md`) and **`01-UI-DESIGN-PROMPT.md`**, which **does not exist in
   the repo at all**. The UI spec Phase 7 is told to implement is therefore
   missing — the only UI reference is the local `bingwa-auto-reply/` folder,
   which is itself out of date vs. the pivot. **Phase 7 will need this
   resolved.** Files were left un-renamed deliberately: parallel sessions were
   given the current names, and renaming mid-flight would break them.
