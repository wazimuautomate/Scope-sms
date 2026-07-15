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
| **3** | Rules engine + in-memory cache | ✅ **Done** — CI green, 83 tests, exit criteria met |
| **4** | Two message template types | ✅ **Done** — CI green, exit criteria met |
| 5 | SCOPE SMS gateway client | Not started |
| 5b | Outbound queue & burst-speed | Not started |
| 6 | Independent notification toggles | Not started |
| 7 | Compose UI | Not started |
| 8 | Activity log & dashboard stats | Not started |
| 9 | Reliability hardening | Not started |
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

### 3. ~~DI: manual vs Hilt~~ — ✅ RESOLVED by Phase 3: **manual DI**
Phase 3 was the first phase with a real graph to wire, and made the call exactly
as Phase 0 intended. **Manual DI via `di/AppContainer`, reached through the
`Context.appContainer` extension** (use that, don't cast `ScopeSmsApplication`
by hand). Rationale in full: `AppContainer`'s KDoc and `di/README.md`. Short
version:
- The graph is five process-scoped singletons — no scopes, qualifiers, or
  swappable implementations. None of what Hilt is good at is present.
- The awkward consumer, a system-constructed `BroadcastReceiver`, is handled by
  reading a field off the Application. No annotation processor, nothing
  generated to reason about while debugging a cold start.
- Every build mistake costs a CI round trip (constraint 8). Room already brings
  KSP; Hilt would add a second processor plus a Gradle plugin whose behaviour
  under AGP 9's built-in Kotlin nobody here has verified.

**Settled — do not relitigate per phase.** Revisit only if the graph grows
scopes and swappable implementations, and record the change here.

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

## Decisions made in Phase 3 & 4 (and why)

### 🔴 Money is `KshAmount` (Long cents) — Phase 2 and 5b please read
`domain/money/KshAmount` is the canonical money type across the app. Matching
tests amounts for **equality**, which rules out `Double`/`Float` outright, and
rules out `Int` shillings too: M-Pesa states two decimals, and a customer sending
`Ksh20.50` must *not* match the Ksh20 bundle and get a confirmation for a
purchase they didn't make. Cents represent what actually arrived.

**Phase 2's parser should return `KshAmount` (`KshAmount.parse` handles
`20`, `20.00`, `Ksh20.00`, `1,300.22`, `20.5`→20.50, and returns null rather
than guessing).** If Phase 2 has already produced an `Int`-shillings amount,
that's an integration seam to reconcile at merge — this note is here so it's
found before Phase 5b builds on top of it. Room stores raw `Long` cents; the
conversion lives in `data/`, so no `@TypeConverter` and Room never sees the
value class.

### 🔴 `MatchOutcome` is three-way, not a nullable rule
`RuleSnapshot.classify()` returns `NoRulesConfigured | Matched | Unmatched`.
This is the single most important design call in Phase 3 and it must survive
into Phase 5b/6.

"No rule matched Ksh 35" and "the agent hasn't entered any prices yet" are both
`null` in a nullable API, and conflating them is a live bug: on a fresh install
every payment fails to match, so **every paying customer would be texted a price
list that renders empty**. The sealed type makes the compiler force all three
arms. `NoRulesConfigured` means *send nothing* — it is a setup state, not a
customer who paid wrong. It also covers "rules exist but all are inactive".

### 🔴 `awaitLoaded()` — the cold-start hazard, and why it's not optional
`SnapshotCache.awaitLoaded()` is the **only** way to get a snapshot for a send
decision. `currentOrNull()` exists for the UI and must not be used to decide.

Why: an incoming SMS starts the process from cold. Android constructs the
Application, the receiver runs within milliseconds, and the first Room read has
not returned. A cache answering "empty" in that window classifies a perfectly
good Ksh 20 payment as unmatched and texts the customer a price list they never
needed. Making the snapshot unobtainable until loaded makes the window
unrepresentable rather than merely documented.

Costs one Room read **per process start, not per SMS**, so constraint 5 holds.
**Phase 5b:** call it inside `goAsync()` and wrap in `withTimeout` — if Room
can't be read at all it never resumes, and the caller owns that deadline. Treat
expiry as a loud logged failure, never a dropped message.

### The caches are fed from Room's Flow — never poke them from a writer
`AppContainer.start()` collects each repository's `Flow` into its cache. The
tempting alternative (every write also updates the cache) depends on every future
caller remembering; one `INSERT` in Phase 7 that forgets, and the agent edits a
bundle price while the receiver quotes the old one at paying customers. Driving
from Room's invalidation means **any** write, through any DAO, from any phase,
lands in the cache automatically. Don't add a `cache.publish()` call to a
repository.

The collectors retry forever with capped backoff, deliberately: a dead collector
means a cache that never loads, so every payment silently gets no reply while the
app looks fine — the exact "silence is the unacceptable outcome" case.

### Duplicate rule amounts: most-recent-wins, and reported
The DB has **no unique index on `amountCents`**, on purpose. The constraint that
matters is "unique among *active* rules" — an agent must be free to deactivate
the old Ksh 50 bundle and add a new one at the same price — and Room's `@Index`
can't express a partial index, so a unique index would forbid a legitimate edit
while still not being the rule we mean.

Duplicates resolve to the **highest id** (most recently added). Someone
re-pricing by adding a row rather than editing means the new one; oldest-wins
would make their correction silently do nothing. The collision is surfaced via
`RuleSnapshot.duplicateAmounts` — **Phase 7 should warn on the rules screen**,
since only one of the two will ever be quoted.

### Templates ship defaults; rules deliberately don't
Asymmetric on purpose. An empty rule list is *safe* (`NoRulesConfigured` → stay
quiet). An empty template is not: the agent flips a toggle on, a customer pays,
and a blank SMS goes out. There is no sensible default price list, but there is a
sensible default sentence.

Defaults live in code (`DefaultTemplates`), **not seeded into Room**. A row
exists only when the agent customises that flow, so "still default" is "no row"
rather than a flag that can contradict the body beside it. Improving the shipped
wording then needs no migration and can never overwrite the agent's own text.

### Template rendering never emits a token
Output goes straight to a paying customer with no human in the loop. So: a known
variable with no value (`{name}` when the parser found none) renders empty and
the text is tidied ("Hi {name}, thanks" → "Hi, thanks"). An *unrecognised* token
(`{nmae}`) is left visible — it's the agent's typo, the Phase 7 preview renders
through the same method, and deleting it silently would hide the mistake at the
only moment it's catchable. Values are inserted literally, so a customer named
`A$AP` can't be read as a regex backreference.

### Room schema JSON is committed, and CI publishes it
`app/schemas/…/1.json` is committed. `exportSchema = true`, no
`fallbackToDestructiveMigration()` anywhere, and `ScopeSmsDatabase.build()` will
throw rather than silently recover from a missing migration — because destructive
migration on this app wipes the agent's live pricing and history.

With no local build, the generated JSON can't be produced locally, so the CI
workflow now uploads `app/schemas/` as the **`room-schemas-<run>`** artifact.
**Phase 5b/8: after adding your entity and bumping the version, download that
artifact and commit the new JSON**, or the migration after yours has no baseline
to diff against.

---

## Gotchas discovered (save the next session the debugging)

### Kotlin block comments NEST — `/*` inside a KDoc breaks the file
Cost a CI round trip in Phase 3. A KDoc containing the path `app/schemas/*.json`
has a `/*` in it, which **opens a nested comment**; the comment never closes and
the compiler swallows the rest of the file, reporting a confusing "Unclosed
comment" at EOF plus a cascade of unresolved references in *other* files. Kotlin
differs from Java here. Don't put glob paths in comments.

### An `object`'s properties initialise in declaration order
Also cost a round trip. `SmsSegments.GSM_EXTENDED` read `FORM_FEED`, declared
below it → "Variable 'FORM_FEED' must be initialized". Functions are fine in any
order; property initialisers are not.

### Truth's `containsExactly()` returns `Ordered`, not void
Cost a third round trip, and it fails in a way that names none of this: an
expression-bodied test (`fun x() = runBlocking { … }`) ending in
`containsExactly(...)` infers a non-`Unit` return type, JUnit4 rejects it as not
`void`, and the **whole class** dies with `InvalidTestClassError` —
`initializationError`, no mention of the method responsible. Use
`runBlocking<Unit> { … }` to pin the return type regardless of what the last
assertion happens to return.

### Windows authoring → Linux CI: `gradlew` line endings
Repo is authored on Windows, built on Linux runners. Without `.gitattributes`
forcing LF, `gradlew` checks out with CRLF and CI dies on the shebang:
`bad interpreter: sh^M: no such file or directory`. `.gitattributes` handles
it; `gradlew` is also committed mode `100755`. **Don't "fix" .gitattributes.**

### Robolectric needs JDK 21 against SDK 36+ — sidestepped, not solved
Still true: Robolectric needs **JDK 21** for SDK 36+ (those android-all jars are
Java-21 compiled) and CI provisions **JDK 17**.

**Phase 3 added Robolectric anyway without bumping the runner**, by pinning every
Robolectric test `@Config(sdk = [30])`. The API 30 android-all jar is Java
11-compiled, so JDK 17 runs it fine — and 30 is `minSdk`, which constraint 1 says
is the level to verify against anyway. The floor is the more useful place to test
than the ceiling. Verified green: `RoomCacheSyncTest`, 13 tests, real in-memory
Room.

So: **only bump `setup-java` to 21 if a phase genuinely needs Robolectric above
SDK 30** — and if you do, leave `compileOptions`/`jvmTarget` at 17 (17 is AGP's
minimum, not its maximum).

Better still: **prefer JVM-pure tests.** The parser, rules and template engines
are pure Kotlin by design (`domain/`) and need no Robolectric at all — 70 of
Phase 3/4's 83 tests run that way in under a second. Reach for Robolectric only
when the thing under test is genuinely Android's (as with Room's real SQLite
behaviour), not merely adjacent to it.

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

### Version catalog: which pins are now proven
Gradle never resolves an unused entry, so a wrong pin stays silent until first
use. Phase 0 researched these; Phase 3/4 was the first build to actually resolve
some of them.

**✅ CI-verified as of Phase 3/4** — Room `2.8.4`, KSP `2.3.10` (Room's compiler
runs through it and generates fine under AGP 9's built-in Kotlin), Robolectric
`4.16.1`, Truth `1.4.5`, kotlinx-coroutines `1.11.0` (core + test).

**Still unexercised** — WorkManager, DataStore, Retrofit, OkHttp, Moshi,
mockwebserver3. The phase that first uses one confirms it resolves.

Note: Phase 3 renamed the version key `coroutinesTest` → `coroutines`, since
`-core` and `-test` must share a version. Library aliases are unchanged, so
nothing referencing `libs.kotlinx.coroutines.test` breaks.

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
3. **Phase 4: `MessageTemplate` drops `id`; `type` is the primary key.**
   BUILD-PLAN specifies `MessageTemplate(id, type, body, isDefault)`. `id` only
   earns its place if several templates can share a type, with `isDefault`
   picking the live one — and nothing wants that: the Templates screen is two
   editors, one per flow, and the decide path asks for "the unmatched template"
   expecting one answer. Keeping `id` would let the table hold three UNMATCHED
   rows with `isDefault` true on two, a meaningless state every reader would have
   to defend against. A PK on `type` makes it unrepresentable in SQLite.
   `isDefault` is likewise not a column — no row *means* still-default (see the
   templates decision above). If variants are ever genuinely wanted (A/B wording),
   that becomes `(id, type, isDefault)` **with a real migration**, deliberately.

4. **Phase 3 shipped `MatchOutcome` as a sealed three-way type**, where the plan
   says "return the matching rule or `null` (no match = trigger reply)". Taken
   literally, that instruction is a bug on a fresh install: with no rules, every
   payment is `null` → every customer gets an empty price list. Strictly more
   than asked for, and the plan's own "prompt the agent to add prices before it
   does anything" is what it implements.

5. **Doc filenames don't match the docs' own references.** `CLAUDE.md` and
   `BUILD-PLAN.md` both refer to **`02-BUILD-PLAN.md`** (actual file:
   `BUILD-PLAN.md`) and **`01-UI-DESIGN-PROMPT.md`**, which **does not exist in
   the repo at all**. The UI spec Phase 7 is told to implement is therefore
   missing — the only UI reference is the local `bingwa-auto-reply/` folder,
   which is itself out of date vs. the pivot. **Phase 7 will need this
   resolved.** Files were left un-renamed deliberately: parallel sessions were
   given the current names, and renaming mid-flight would break them.
