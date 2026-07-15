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
| **5** | SCOPE SMS gateway client | ✅ **Done** — CI green, all exit criteria met |
| **5b** | Outbound queue & burst-speed | 🟡 **Code complete, criterion partly blocked** — queue proven; end-to-end needs Phases 2–4. See below. |
| 6 | Independent notification toggles | Not started |
| 7 | Compose UI | Not started |
| 8 | Activity log & dashboard stats | Not started |
| 9 | Reliability hardening | Not started |
| 10 | Cross-version testing | Not started |
| 11 | Release packaging & distribution | Not started |

---

## 🔴 Open decisions — resolve before the owning phase ships

### 0. 🔴 Phase 5b's headline exit criterion is only PARTLY met (owned by Phase 2/3/4)
Phase 5b is code-complete and CI-green, but BUILD-PLAN's criterion — *"the
single most important exit criterion in the whole plan"* — is not fully
satisfied, and **Phase 5b must not be ticked off until it is.**

The criterion asks for ~10 `SMS_RECEIVED` events in 1–3s each producing exactly
one **correctly-templated** job. The burst is driven at the **queue boundary**
(`OutboundQueueBurstTest`) rather than through a real broadcast, because the
receiver (Phase 2), rules (Phase 3) and templates (Phase 4) did not exist when
this was built — all three were in parallel sessions with nothing committed.

**Proven now:** no drops (10 concurrent → 10 jobs), no duplicates (incl. a
genuine 8-way race), no blocking of ingestion (enqueue never touches the
network; a 30s hung gateway doesn't delay a 10-payment burst), one gateway call
per payment, bounded retries, stranded-job recovery.

**Not proven, and needing the other phases:**
- *"correctly-templated"* — bodies are fixtures. Only the queue's half is
  covered: the body handed to `enqueue` is stored byte-identical.
- Real `SMS_RECEIVED` delivery and real OEM redelivery.

**Whoever lands Phase 2:** wire the receiver → `OutboundQueue.enqueue` and
re-run the burst end-to-end. That closes this. Until then Phase 5b is 🟡.

### 0b. ⚠️ Phase 5 cannot actually send until Phase 1 declares `INTERNET`
The manifest still declares **no permissions** — Phase 0 left the whole set to
Phase 1 to avoid merge conflicts, and Phase 5 respected that rather than
sneaking `INTERNET` in. Consequence: the gateway client is correct and fully
tested, but on a real device every send fails until Phase 1 merges. Unit tests
don't catch this (no manifest involved), so it will present as "the APK does
nothing" on the first real-device test. Not a bug — just an ordering dependency.

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

**Phase 5 update — this is no longer blocking, only pending.** The gateway reads
credentials through a port, `network/GatewayCredentialsProvider`, which Phase 5
deliberately left unimplemented. Nothing about the client, its failure mapping
or its tests depends on where the key lives, so the decision doesn't need making
to unblock work — it needs making before Settings (Phase 6/7) can capture the
key. Implement that one interface and the gateway is wired. Returning `null`
from it is a supported state (agent hasn't finished setup) and maps to a
terminal failure rather than a crash or a retry loop.

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

**Phase 5 update — STILL OPEN. Phase 5b did not decide it, on purpose.**
`SendJobWorker` needs an `OutboundQueue`, and WorkManager constructs workers
reflectively, so it hit the same process-scope problem. Rather than settle the
project's DI style from a phase with exactly one binding — while Phase 3/4 was
running in parallel and is the likelier owner — Phase 5b added
`queue/QueueGraph`: one nullable slot, `install()` + `outboundQueue()`, no
framework. `di/` is untouched and still empty.

**Whoever decides:** absorb or delete `QueueGraph`. `SendJobWorker` is its only
reader and nothing else references it. Have the real container call
`QueueGraph.install { … }` from `ScopeSmsApplication.onCreate`, or replace the
call site outright — either is a few lines. It exists so Phase 5b could ship
without pre-empting you, not to constrain you.

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

## Decisions made in Phase 5 / 5b (and why)

### HTTP client: Retrofit + OkHttp + Moshi (BUILD-PLAN asked us to pick and record)
Retrofit over Ktor. The catalog had already researched and pinned it; the gateway
is three plain JSON POSTs with no streaming or websockets; and OkHttp is the
better-understood client on the low-end Android 11 handsets this ships to.
Ktor's advantages (multiplatform, engine-swapping) buy this app nothing.

### 🔴 Moshi uses REFLECTION, not codegen — and that's why there are R8 rules
`KotlinJsonAdapterFactory` (`moshi-kotlin`), **not** `moshi-kotlin-codegen`.
Reason: Moshi 1.15.2's codegen is written against the KSP1 API, and the catalog
pins **KSP 2.3.10 (KSP2)**. Moshi-on-KSP2 is a known rough edge and CI is the
only compiler here — a wrong guess costs a red run and blocks whoever is waiting.
Reflection has no processor risk at all.

**The cost, and it's a real one:** R8 can't see reflective field reads, so
without keep rules it renames `senderId` → `a` and the gateway silently receives
JSON it doesn't understand. **That breaks in release only — every debug CI run
stays green.** Handled by `@Keep` on the models plus rules in
`proguard-rules.pro`. Phase 11: run a release build through CI *early*, not at
tag time. Also pulls `kotlin-reflect` (~1–2 MB pre-shrink); revisit codegen if
APK size ever matters, or once Moshi ships proper KSP2 support.

### `InsufficientBalance` is classified RETRYABLE — the one judgement call
Everything else in `SendFailure` is obvious (bad key/sender ID = terminal; 429,
5xx, timeout, no-connectivity = retryable). Balance is genuinely arguable: no
retry succeeds until the agent tops up, which argues terminal.

Called retryable because unlike a bad key, *nothing is misconfigured* — the
top-up takes a minute from the agent's own phone, and the customer is still
waiting on their prices. Bounded retries (5, ~10 min against WorkManager's
backoff) cover the realistic case; if they run out the job lands `FAILED` with
"top up to resume sending", which is exactly the signal needed. **Revisit if the
agent reports the queue thrashing on an empty balance.**

### Dedupe is a unique DB index, not a Kotlin-side check
`OutboundJob` has a unique index on `transactionCode`; the DAO uses
`OnConflictStrategy.IGNORE`. A read-then-write guard in application code races:
under the ~10-in-1–3s burst, two deliveries of one transaction can both see "no
row" before either inserts, and the customer gets two SMS at the agent's expense.
SQLite resolves it atomically instead. **Verified, not assumed** — see below.

### Queue rules are pure Kotlin behind a port (`OutboundJobStore`)
Room's generated code needs an Android runtime, so testing the queue through the
DAO would mean Robolectric → **JDK 21** → bumping the whole CI pipeline for one
test (the trap already flagged below). Instead the rules live above a port and
test on the JVM in ~2.5s; `RoomOutboundJobStore` is a thin adapter with no logic.
Keeps CI on JDK 17 and the safety net fast.

### The drain is sequential, deliberately
Parallel sends buy nothing: the worst case is ~10 messages against a 100/min
limit, so concurrency mainly raises the odds of tripping a 429. The burst
requirement is about never blocking *ingestion*, which `enqueue` already
guarantees by returning before any network call.

### `AppDatabase` was created by Phase 5b but is NOT owned by it
`data/README.md` gives the DB four owners (Phases 3, 4, 5b, 8). Phase 5b was
simply the first to need Room, and neither parallel session had committed
anything. The file carries merge instructions in its class doc: add your entity,
add your DAO accessor, bump the version, commit the schema JSON. **If you hit an
add/add conflict: keep both entity lists and both DAO accessors.** Nothing there
is Phase-5b-specific beyond the `OutboundJob` lines.

`fallbackToDestructiveMigration()` is deliberately absent and schema export is on
from v1, per `data/README.md`.

### Verified, not assumed: the burst test actually fails when the guarantee breaks
Following Phase 0's precedent with `ArchitectureGuardTest`. A scratch branch
(`chore/verify-burst-dedupe-guard`, since deleted) replaced the fake store's
atomic insert with a check-then-insert race; CI went red on exactly
`simultaneous redelivery of one payment still produces exactly one job`
(42 tests, 1 failed). A concurrency test that has never failed is not known to
detect a race — it may simply never have hit the window.

---

## Gotchas discovered (save the next session the debugging)

### ✅ The "later phases" catalog entries are now CI-verified (was: unverified)
The caveat below said an unused catalog pin stays silent until first use. Phase
5/5b used most of them and **they all resolve and compile together**: Retrofit
3.0.0, OkHttp 5.4.0, Moshi 1.15.2, Room 2.8.4, KSP 2.3.10, WorkManager 2.11.2,
Truth 1.4.5, coroutines-test 1.11.0, mockwebserver3-junit4 5.4.0. Room + KSP 2.3.10
under AGP 9's built-in Kotlin works. The renamed `mockwebserver3-junit4`
coordinate is correct.

**Still unexercised:** DataStore 1.2.1, Robolectric 4.16.1, room-testing.

### `org.json` is an Android stub in unit tests — don't parse JSON with it
`JSONObject` lives in `android.jar`, so in a JVM unit test it's a stub that
throws "not mocked" (or silently returns defaults if anyone ever sets
`isReturnDefaultValues = true` — which is why Phase 5 deliberately left that
off; see the comment in `app/build.gradle.kts`). Assert on the JSON string or
use Moshi directly. `ScopeSmsGatewayTest` proves the wire field names by
serialising with Moshi, not by parsing with `JSONObject`.

### Retrofit: return `Response<T>`, not `T`
Declaring the API method as `suspend fun sendSms(...): T` makes Retrofit throw
`HttpException` on any non-2xx, which erases the distinction between "retry this"
and "the agent must fix their API key" — the whole point of `network/`. Returning
`Response<T>` keeps the status code available to the failure mapping.

### The gateway can report failure under HTTP 200
It carries its own `response-code` in the body, and documented error bodies
(invalid key, insufficient balance) can arrive under a 200. Trusting the HTTP
status alone would mark those jobs `SENT` and lose the customer's SMS silently.
`ScopeSmsGatewayTest` pins this. Note `response-code` is **hyphenated** on the
wire — it can't be a Kotlin identifier, so it only works via `@Json(name=...)`.

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

### Version catalog contains unverified entries — ⚠️ MOSTLY SUPERSEDED
Everything under "later phases" in `gradle/libs.versions.toml` (Room,
WorkManager, DataStore, Retrofit, OkHttp, Moshi, Robolectric, Truth) is
researched but **not exercised by any build** — Gradle never resolves an unused
entry, so a wrong pin stays silent until first use. The phase that first uses
one confirms it resolves.

**Update (Phase 5/5b):** most are now CI-verified — see "The 'later phases'
catalog entries are now CI-verified" above for the exact list. The principle
still holds for **DataStore, Robolectric and room-testing**, which no build has
touched yet.

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

0. **Phase 5b's burst test is driven at the queue boundary, not from a real
   `SMS_RECEIVED` broadcast** — because Phases 2/3/4 don't exist yet. Everything
   the queue owns is proven; "correctly-templated" and real broadcast delivery
   are not. Flagged as open decision 0 above; Phase 5b stays 🟡 until closed.
   This is the one deviation on this branch that a reader must not miss.

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
