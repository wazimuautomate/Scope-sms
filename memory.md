# memory.md — running technical memory

> Read with `CLAUDE.md` and `changelog.md` at the start of every session.
> Decisions and rationale, gotchas, per-phase state, open questions.
> Prune stale "in progress" notes once superseded.

---

## Phase status

All phases are on `main` as of 2026-07-16. CI green: **276 unit tests + 6
instrumented tests on API 30 and API 36, 0 failures, 0 skipped** (counts read out
of the downloaded CI reports, not off the green tick).

| Phase | Scope | State |
| --- | --- | --- |
| **0** | Repo, scaffolding & CI pipeline | ✅ **Done** |
| **1** | Permissions & SIM identification | 🟡 **Code-complete** — needs a real dual-SIM device |
| **2** | SMS ingestion & M-Pesa parser | 🟡 **Code-complete** — still only **1** real sample message |
| **3** | Rules engine + in-memory cache | ✅ **Done** |
| **4** | Two message template types | ✅ **Done** |
| **5** | SCOPE SMS gateway client | ✅ **Done** |
| **5b** | Outbound queue & burst-speed | ✅ **Done** — headline criterion now proven end-to-end |
| **6** | Independent notification toggles | 🟡 **Done, defaults unconfirmed with the agent** |
| **7** | Compose UI | 🟡 **Built** — needs the manual click-through on a real device |
| **8** | Activity log & dashboard stats | 🟡 **Done** — stats-vs-real-traffic check needs a device |
| **9** | Reliability hardening | 🟡 **Built** — soak/reboot/airplane-mode tests need a device |
| **10** | Cross-version testing | ✅ **Done** — emulator matrix runs on API 30 + 36 |
| **11** | Release packaging & distribution | 🟡 **Key generated 2026-07-16** — needs the agent to add 4 GitHub secrets, then first signed build |

**What "🟡" means here: the code is written and tested as far as this workflow
can test it. Every remaining item needs a physical phone or an answer from the
client.** Nothing is 🟡 because it was left half-finished.

---

## 🔴 Release identity & in-app updates (2026-07-16) — supersedes earlier release notes

A client-driven pivot to a permanent, self-updating private distribution. This
section overrides the pre-pivot notes below about `com.scopesms.autoreply`,
`0.9.0`, the `SIGNING_*` secrets, and the rolling "testing" pre-release.

### App identity is now permanent — `com.tricreta.scopesms`
`applicationId` + `namespace`. The old `com.scopesms.autoreply` is retired.
Because it's a **new package**, the agent does **one** uninstall of the old app,
then updates are seamless forever. The rename moved source/test/androidTest trees
and the Room schema dir (`app/schemas/com.tricreta.scopesms.data.AppDatabase`)
with `git mv`. `ArchitectureGuardTest` asserts the base id **after stripping
`.debug`** — unit tests run the debug variant, whose id carries the suffix.

### versionCode 1 / versionName 1.0.0
Reset from 3/0.9.0. Safe because the new package has zero installs. **versionCode
only ever increases**; `release.yml` fails a tag whose versionCode isn't strictly
greater than the one published in `main:update.json`.

### Debug ≠ release, and no debug distribution
Debug: `applicationIdSuffix ".debug"`, label **Scope SMS Debug**, default debug
key — coexists with the real app, never shipped. The client was explicit: "no
debug apks, real apps." `build.yml` no longer publishes anything (verification
only); real APKs come from tags via `release.yml`.

### Keystore REUSED, not regenerated — alias `scope-sms`
Client chose to keep the `scope-sms` keystore from the prior session (they hold
the base64 + password). CI secrets renamed to `ANDROID_KEYSTORE_BASE64 /
ANDROID_KEYSTORE_PASSWORD / ANDROID_KEY_ALIAS (=scope-sms) / ANDROID_KEY_PASSWORD`.
**`build.gradle.kts` env vars renamed to match** (`ANDROID_KEYSTORE_PATH`, …). The
whole point still holds: whatever signs 1.0.0 must sign every update. README has a
from-scratch fallback — safe because no signed release has shipped under the new
package yet.

### In-app updater rebuilt: download → verify → install (was: open browser)
Reads `update.json` at `BuildConfig.UPDATE_MANIFEST_URL`
(`raw.githubusercontent.com/wazimuautomate/Scope-sms/main/update.json`), compares
by **versionCode**. Pure/JVM-tested core: `domain/update/UpdateResolver`
(versionCode compare + forced logic), `Sha256`, `SignatureMatch`. Android engine
`update/AppUpdater` (OkHttp streaming + incremental SHA-256 to `cacheDir/updates`,
package + signing-cert verify, `ACTION_VIEW`+FileProvider install, unknown-sources
grant). UI `ui/update/{UpdateViewModel,UpdateSection}`.

Gotchas locked in for next time:
- **`getPackageArchiveInfo` does not set `applicationInfo.sourceDir/publicSourceDir`**
  — signature reads return null until you point them at the archive path. Load-
  bearing; without it every cert check silently CANT_VERIFYs.
- **Signature CANT_VERIFY is a soft proceed** (the OS installer enforces sigs
  anyway); only a *readable* mismatch hard-blocks. Some low-end OEM ROMs fail to
  read archive certs even for valid APKs.
- `lint { abortOnError = false; checkReleaseBuilds = false }` — a pre-existing
  warning must not block cutting a release fix. Lint still runs + reports in CI.
  Tighten to gating once a `lint-baseline.xml` is captured.
- `update.json` is seeded at repo root as a **versionCode 0 placeholder** so the
  raw URL resolves without colliding with the strictly-increasing guard; the first
  `v1.0.0` release overwrites it with the real manifest.

### Device-only, still unproven (as ever, needs a handset)
The install intent + system-installer confirmation, the unknown-sources grant
round-trip, and real signing-cert enforcement at install. JVM covers the compare/
verify *decisions*, not the platform behaviour.

---

## 🔴 Open — needs the client, not a session

### 1. Real M-Pesa sample messages — STILL ONE
Unchanged since Phase 2, and still the highest-value ask on the project. We have
**exactly one** real till-confirmation (the one in CLAUDE.md). BUILD-PLAN Phase 2
asks for 5–10 before the regex is trusted.

`MpesaParserTest` is green and covers ~30 variants, but every case beyond that
single sample is a **hypothesis about M-Pesa's wording, not an observed
message**. Green means "no known case is broken", not "the parser works".

For whoever gets them:
- `MpesaParser.PATTERNS` is an **ordered list** precisely so a new real variant is
  a new entry + a test, not a rewrite. Add, don't restructure.
- A `Rejection.NOT_A_RECEIVED_MESSAGE` in the wild means *the regex is wrong*, not
  *the message was junk*. Logged at WARN for that reason.
- The riskiest untested assumption is the **sender rule** (see gotchas).

### 2. The two toggle defaults are still unconfirmed
BUILD-PLAN Phase 6 says confirm with the agent, don't assume. Shipped as the
plan's own recommendation: `unmatched=ON`, `matched=OFF`.

**Safe to ship un-confirmed**, and this is why: on a fresh install the rule list
is empty, so every payment classifies as `NoRulesConfigured` and nothing sends
regardless of these values. The default cannot text a customer before the agent
has entered prices. Change `NotificationToggles.DEFAULT` and the tests that pin
it if the answer differs.

### 3. The release signing key — GENERATED 2026-07-16, custody handed to the agent
Previously "does not exist yet". It now exists: a permanent RSA-2048 keystore
(alias `scope-sms`, 10 000-day validity) generated this session and handed to the
agent as a base64 blob + password, to load into the four GitHub Secrets
(`SIGNING_KEYSTORE_BASE64`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS` = `scope-sms`,
`SIGNING_KEY_PASSWORD`). **The keystore is NOT in the repo and must never be** —
it lived only in this session's scratchpad.

Open until the agent confirms: the four secrets are added, and the first signed
`testing` pre-release actually builds. Until the secrets exist, `build.yml`'s
signed-APK path and the whole tagged `release.yml` are still unexercised.

**Still true and load-bearing:** whatever signs v1.0.0 must sign every future
update. A lost key ⇒ the agent uninstalls to update, losing prices/templates/
history. **New this session:** CI now signs the *testing* (debug) APK with this
same key (`app/build.gradle.kts` debug `signingConfig`, active only when
`SIGNING_KEYSTORE_PATH` is set), so testing builds and the eventual release update
over each other seamlessly. The one unavoidable uninstall is the switch *from* the
old random-debug-key build *to* the first signed build — after that, never again.

### 4. targetSdk 36 vs 37 — decided: stay at 36 for v1.0.0
Was owned by Phase 10. `compileSdk = 37`, `targetSdk = 36`, unchanged, and now a
deliberate final call rather than a deferral.

targetSdk opts into new *runtime* behaviour. Android 17's changes are ~1 month
old, and the categories Android keeps tightening — background execution,
broadcast delivery, telephony permissions — are precisely the path between
"customer pays" and "customer gets a reply". Nothing here needs an API 37
behaviour, and direct-APK distribution means Play's targetSdk deadline doesn't
apply. There is still no Android 17 device in this workflow to test against, so
raising it would trade real risk for no benefit.

Revisit when an Android 17 handset is available. Remains a flagged deviation from
CLAUDE.md constraint 1.

---

## 🔴 Still needs a real device (nobody here has one)

Grouped because the answer is the same: **only the agent can run these.** Steps
are in `README.md`.

| What | Why it can't be done here |
| --- | --- |
| Phase 1 — both SIMs listed, filter survives reboot | Needs a real dual-SIM phone |
| Phase 2 — the sender rule `^M-?PESA$` | Verified from docs, not from the agent's handset |
| Phase 7 — the click-through, light and dark | The criterion asks a human to judge the screens |
| Phase 8 — stats match real traffic | Needs real payments |
| Phase 9 — 24h Transsion soak, reboot, airplane mode | Needs a Transsion device and 24 hours |
| Phase 9 — the OEM autostart deep links | Component names unverified; **Transsion is the primary market** |
| Phase 11 — install from a GitHub Release | No release exists yet (open item 3) |

The emulator matrix (Phase 10) covers what an emulator honestly can: the graph
builds, Room opens, the **Android Keystore really round-trips a secret on API 30
and 36**, and the receiver survives a junk broadcast. It cannot cover dual-SIM,
OEM battery managers, or real M-Pesa traffic.

---

## Decisions made during integration (2026-07-16)

### 🔴 `KshAmount` is the one money type — `Money` is gone
Phase 2 shipped `Money` + `amountCents: Long`; Phase 3/4 shipped the `KshAmount`
value class. **Both chose integer cents, so the data always agreed — they simply
would not compile together.**

KshAmount won: it's a `@JvmInline value class` (a plain `long` at runtime, so the
type safety costs nothing on the SMS hot path), and its `format()` already drops a
trailing `.00`. `MpesaPayment.amount/balance/transactionCost` now carry it;
`Money` and `MoneyTest` were deleted (`KshAmountTest` covers the type).

Why integer cents at all, since this keeps coming up: the app's core operation is
`payment.amount == rule.amount`. `20.10` as a double is `20.099999999999998`, so
a bundle priced at 20.10 could fail to match a payment of exactly 20.10 — and the
customer gets a "you paid the wrong amount" price list for a payment that was
right.

### 🔴 Amounts the agent types are whole shillings; amounts customers send are not
The client's requirement: *"Amount should never be in decimal like 123.50. It
should be a whole integer."* Implemented as an **asymmetry**, and the asymmetry is
the point:

- **Entry** — `KshAmount.parseWholeShillings()` rejects any decimal. The Rules
  editor uses it, so no rule can hold cents. That's what lets `format()` be
  trusted to render a price with no decimal point anywhere else in the app.
- **Parsing** — `KshAmount.parse()` keeps the cents faithfully. A customer *can*
  send Ksh 20.50, and it must match nothing. Truncating to 20 would confirm a
  purchase that never happened; rounding to 21 is just as wrong.

So the agent never sees or types a decimal, and the app never lies about what
arrived. Both halves are tested.

### 🔴 One database: `AppDatabase`
Phase 5b, Phase 3/4 and Phase 8 each wrote "the app's database", each correctly
reasoning it was first. Phase 8 already yielded to Phase 5b's; Phase 3/4's
`ScopeSmsDatabase` was deleted during integration and its entities moved across.

Two databases mean two SQLite files, two connections, and no transaction can ever
span a queue job and its log row. `AppDatabase`'s own doc had already reserved the
slots. The v1 schema JSON under `app/schemas/` was regenerated and committed.

### The decide path exists now — `PaymentPlanner` + `PaymentPipeline`
Every session left this as a comment, because it needs Phases 2, 3, 4, 6 and 5b
to exist at once and none of them ever did. It's split deliberately:

- **`domain/PaymentProcessor.kt` — `PaymentPlanner`**: pure and total. Takes
  snapshots, not caches, so it *cannot* do I/O even by accident. This is
  constraint 5 made structural rather than promised.
- **`telephony/PaymentPipeline`**: awaits the caches, logs, enqueues.

**Log first, enqueue second.** If the process dies between them, the agent sees a
`QUEUED` reply that never sends — visible and diagnosable. Reversed, they'd see a
customer texted with no trace of why, which constraint 9 rates worst.

The **log insert carries the duplicate guard** (unique on `transactionCode`), so a
redelivered `SMS_RECEIVED` stops before it can text anyone twice. The queue has
its own unique index too — belt and braces, because a double send costs the agent
money and annoys their customer.

### 🔴 Gateway credentials: Android Keystore AES/GCM + DataStore — closes old open decision 1
`androidx.security:security-crypto` was rejected, and the reasoning still holds:
1.1.0 is "stable" but every API in it is deprecated ("in favour of … direct use of
Android Keystore" — Google's own note), and it has known **keyset-corruption
crashes on Tecno/Infinix/itel/Xiaomi**, which is precisely this market. A
corrupted keyset = unrecoverable credentials = replies silently stop.

`data/settings/GatewayCredentialsStore.kt` does what Google now recommends: a
256-bit AES key in the Keystore (never leaves it, TEE-backed on most handsets), a
fresh random IV per write, GCM so tampering fails to decrypt rather than
decrypting to garbage that gets POSTed. No `setUserAuthenticationRequired` — the
queue sends while the phone is locked in the agent's pocket, which is the point of
the app.

**A decrypt failure returns null and clears the value**, never crashes: null is a
state the app already models ("gateway not set up"), so it surfaces in Settings
for re-entry. Its own DataStore *file* (separate instances over separate files are
fine; two instances over one file corrupt each other).

**Verified on real API 30 and 36 emulators**, not just unit-tested — `SmokeTest`
round-trips a real secret through the real Keystore, because a unit test with a
fake `Crypto` proves the store's logic and nothing about the phone.

### The queue reports outcomes to the activity log
Phase 5's queue knew a send's outcome and had nowhere to put it, so a failed reply
updated a job row the agent never sees. Added `SendResultListener`, a port (not a
direct `ActivityLogRepository` dependency — the queue is pure Kotlin over
`OutboundJobStore` precisely so its retry rules are provable on the JVM, and
taking a Room repository would drag Robolectric into all of those tests).

**Retryable failures are deliberately not reported.** The log row stays `QUEUED`,
which is the truth — the reply is still coming. Flipping it to `FAILED` and back
would have the agent chasing a customer the app is about to text anyway.

### `QueueGraph` absorbed into `AppContainer`
Phase 5b's one-slot seam existed so it could ship without pre-empting the DI
decision, and its own doc said "absorb this". Done; `SendJobWorker` reads
`applicationContext.appContainer.outboundQueue`.

### Navigation is hand-rolled — no `navigation-compose`
Five flat destinations, no arguments, no deep links, no nested stacks. The whole
graph is one `when`. A nav library would add a dependency and a route-string DSL
to express `current = RULES`. Back behaviour — the one thing the library gives
free — is explicit: from any tab, back goes Home; from Home, back exits.

Reach for the library when a screen needs real arguments or deep linking.

### `material-icons-core`, not `-extended`
`-extended` is ~10MB of vectors for a handful of icons. Every icon this app uses
is in core.

---

## Decisions carried forward from Phases 1–9 (unchanged, still true)

### 🔴 SIM choice is keyed on **physical slot**, not subscription ID
`SimSelection` stores slot indices (`ALL` or `SLOTS:0,1`), never subscription IDs.

Subscription IDs are **not stable**: re-seat a SIM, factory reset, or on some OEMs
just reboot, and the same card returns with a different ID. Persisting one means
the agent's setting silently starts pointing at their *personal* SIM — constraint
4's worst case, arriving with no error and no warning. A physical slot ("the SIM
in tray 1") survives all of it and is what the agent actually reasons about.

The cost: the SMS intent carries a *subscription ID*, so the receiver resolves
subId → slot at delivery time. That's the price of a setting that doesn't rot. It
also means **Phase 9's "re-validate saved subscription IDs after reboot" was
already handled** — there is no saved subscription ID. What Phase 9 checks instead
is that the *slot* the agent picked still holds a SIM.

### Unresolvable SIM slot → drop, except when unambiguous
`SimFilter` returns `Drop(UNRESOLVED_SLOT)` when the slot can't be determined *and*
several SIMs are active. Constraint 4 ranks a misdirected reply above a missed
one: process it and we might text the agent's private contact; drop it and one
customer misses a price list.

The exception: with a **single active SIM** there's only one place the message can
have come from, so a missing extra is still unambiguous and we process it — and
missing/renamed extras are exactly what low-end OEM builds get wrong.

### Sender must be the M-Pesa shortcode — ⚠️ needs a real-device check
`MpesaParser.isMpesaSender()` requires the originating address to match
`^M-?PESA$`. It's a **security control**: without it, anyone who knows the agent's
number can text a fake "Ksh20.00 received from …" and make the app send a stranger
an SMS at the agent's expense — and poison their books with a payment that never
happened. The originating address is set by the network, so an ordinary sender
can't forge it.

**Verified from documentation, not from the agent's handset.** If real payments are
ever dropped, look here first; the rejected address is logged for exactly that
reason.

### Battery-exemption status is read live, never persisted
The agent can revoke it at any moment, and an OEM battery manager can revoke it
*for* them with no signal to us. A persisted copy goes stale silently — and then
Settings shows a confident green "protected" badge for an app the system is
actively killing, which is the exact failure the indicator exists to reveal.

### `MatchOutcome` is three-way, not a nullable rule
"No rule matched Ksh 35" and "the agent hasn't entered any prices yet" are both
`null` in a nullable API, and conflating them ships a real bug: on a fresh install
*every* payment fails to match, so every paying customer gets texted a price list
that renders empty. `NoRulesConfigured` makes the compiler force the distinction.

Same reasoning gives `ActivityRecord.MatchType` a third arm and `NotifyStatus` a
`QUEUED` arm beyond BUILD-PLAN's stated lists — both deviations, both recorded.

### An empty price list overrides both toggles
`decideReply` checks `NoRulesConfigured` **before** the toggles. The toggle says
"the agent wants this flow"; an empty rule list says "there is nothing truthful to
send yet".

### DI is manual — settled, don't relitigate
`AppContainer`, built by `ScopeSmsApplication`, reached via `AppContainer.from(context)`
or `context.appContainer` — which is how the `BroadcastReceiver` (constructed by
the system, handed nothing) gets the graph. Phases 1 and 3 arrived here
independently.

Two rules for anything added: everything stays `by lazy` (it's constructed on
every headless process start an incoming SMS causes), and nothing holds an
Activity `Context`.

### The hot path does no disk I/O
`SettingsRepository` keeps a `@Volatile` snapshot of the SIM selection;
`AppContainer.start()` warms it. Rules and templates are `SnapshotCache`s fed from
Room by the container — **not** poked by writers, because that relies on every
future caller remembering, and one forgotten `INSERT` means the receiver quotes a
stale price at paying customers.

### `allowBackup=false`
The DB holds customer PII and the prefs hold the gateway key. **Consequence:** the
agent switching phones loses rules/templates and re-enters credentials. If that
becomes a complaint, the answer is an explicit in-app export/import — not
re-enabling backup.

---

## Gotchas discovered (save the next session the debugging)

### 🔴 The Messages tab crashed TWICE — `weight(1f)` was correct but not enough
Round 1 hit "Vertically scrollable component was measured with an infinity
maximum height" on the Messages (Templates) tab and fixed it the textbook way:
give the nested scroll `Modifier.weight(1f)` so its parent `Column` hands it a
finite height. That fix is genuinely correct — a Column measures a *non-weighted*
child with `maxHeight = Infinity`, and `weight` (fill=true) replaces that with the
finite leftover.

**It still force-closed on the agent's round-2 build** (which carried round-1's
sample-send buttons, so the fix was definitely in it). Rather than keep betting on
`weight`, round 2 rebuilt Templates to the *exact* shape of Home and Settings —
the only scrolling screens that never crashed on this handset: a **nested
`Scaffold`** with the `TabRow` as `topBar` and the body as a **root-level
`verticalScroll` Scaffold body**, no `weight`, no intermediate `Column` measure.

Lesson for the next scroll bug: prefer the root-of-a-Scaffold scroll shape that is
already proven on the device over any nested-`Column` arrangement, however
theoretically sound. Activity still uses `LazyColumn(weight(1f))` — left alone
because it opens fine, but note we have **no on-device proof** of that path since
an empty log early-returns before the LazyColumn composes. If Activity ever
crashes once it has data, this is the first place to look.

### 🔴 There IS a working local toolchain now — CI is no longer the only compiler
This changes the project's central assumption (CLAUDE.md constraint 8) and is the
single most useful thing in this file.

Every previous session believed there was no local build and paid a 5–10 minute CI
round trip per compile error. There is no Android Studio, but that was never what
was needed:

```bash
# JDK 21 (Temurin), unzipped anywhere:
export JAVA_HOME="/path/to/jdk-21"
./gradlew test          # ~6 min cold, seconds warm
./gradlew assembleDebug
```

The Android SDK is already at `~/AppData/Local/Android/Sdk`; `local.properties`
needs `sdk.dir=C:/Users/ADMIN/AppData/Local/Android/Sdk` (**forward slashes** — in
a `.properties` file a backslash is an escape character, so `C:\Users` silently
becomes `C:Users` and Gradle dies with "The filename, directory name, or volume
label syntax is incorrect").

Gradle uses `JAVA_HOME` directly and doesn't need `java` on `PATH` — which is
lucky, because Git Bash won't resolve a `PATH` entry containing `+`
(`jdk-21.0.11+10`) or one written `C:/...` rather than `/c/...`.

**CI is still the source of truth** (it's the clean-room build that produces the
agent's APK, and it runs the emulator matrix). But debugging locally first is
strictly faster and should be the default.

### Two Gradle builds on one project directory corrupt each other
Running a background `./gradlew test` while starting another build fails KSP with
`java.lang.IllegalStateException: failed to make parent directories`. Nothing
subtle — just don't run two at once.

### 🔴 `Icons.Default.*` needs an explicit dependency
Material3 does **not** bring `material-icons-core` transitively. Every
`Icons.Default.Home` is an unresolved reference until you add it. Found by the
first local compile; would have been a red CI run otherwise.

### 🔴 WorkManager from `Application.onCreate` is a trap
`SendJobWorker.enqueueDrain()` on process start looked obviously right and was two
bugs at once:
1. `WorkManager.enqueue` **writes to its own database** — disk I/O on the main
   thread of every process start, including the headless SMS wakeups constraint 5
   exists to protect.
2. `WorkManager.getInstance` **throws** if its initializer hasn't run. Normally its
   `InitializationProvider` is created before `onCreate`, but "normally" is doing
   real work: an uncaught throw there is a dead app at launch **and a dead
   receiver**, so payments stop being read entirely. A skipped drain is a delayed
   reply; a crash is a silent outage.

Now on the background scope, with the guard owned by `enqueueDrain` itself. Caught
by the Robolectric suite, which is the only reason it wasn't shipped.

### Kotlin enum entries can't read their own companion's constants
`minSdkInclusive = SDK_TIRAMISU` inside an enum entry fails with *"Companion object
of enum class 'AppPermission' is uninitialized here"* — entries are constructed
before the companion initialises. Fix: file-level `private const`, re-exposed by
the companion.

### Reading intent extras: never pass a default
`intent.getIntExtra("slot", 0)` turns every device that doesn't publish the key
into a confident **"slot 0"** — a wrong answer wearing a right one's clothes, on
the highest-severity decision in the app. `SmsReceiver.readInt()` returns null
instead, and accepts Long/String because some OEMs write the value with the wrong
type (a `ClassCastException` there would crash the receiver).

There is no single reliable extra for "which SIM": AOSP uses `"subscription"`,
`SubscriptionManager` documents `EXTRA_SUBSCRIPTION_INDEX`, and OEMs invented
their own. `SubscriptionExtras` tries them in a defined precedence — some builds
ship several keys with *different values*, so the order is load-bearing.

### Kotlin block comments nest
`app/schemas/*.json` inside a KDoc silently swallowed a whole file. Cost a red CI
run.

### Truth's `containsExactly()` returns `Ordered`, not void
A test method returning non-Unit kills the **entire** JUnit4 class with
`InvalidTestClassError`, naming nothing useful.

### `SubscriptionInfo.getNumber()` is mostly useless — don't key logic on it
Returns empty far more often than not (Kenyan SIMs commonly have no number
provisioned), needs `READ_PHONE_NUMBERS` from API 33, and is deprecated from 33.
`SimInfo.phoneNumber` is nullable and every caller treats it as decoration.

### Windows authoring → Linux CI: `gradlew` line endings
Without `.gitattributes` forcing LF, `gradlew` checks out with CRLF and CI dies on
the shebang: `bad interpreter: sh^M`. **Don't "fix" `.gitattributes`.**

### Robolectric is pinned to `@Config(sdk = [30])`
The API 30 android-all jar is Java 11-compiled, so it runs on any JDK ≥ 17 — and 30
is minSdk, which constraint 1 says is the level to verify on anyway. CI is on JDK
21 regardless (Phase 8 bumped it). Prefer JVM-pure tests: `domain/` is pure Kotlin
by design and needs none of this.

### The AGP 9 toolchain rules (unchanged, still true)
- **AGP 9 has built-in Kotlin.** Applying `org.jetbrains.kotlin.android` is a hard
  build failure. Only `org.jetbrains.kotlin.plugin.compose` is applied, and its
  version must **equal** the Kotlin version.
- **`kotlin-kapt` is incompatible** with built-in Kotlin → **use KSP**.
- `android { kotlinOptions { } }` is gone → `kotlin { compilerOptions { } }`
  (omitted entirely; AGP aligns jvmTarget with `compileOptions` on its own).
- **Kotlin 2.2.10 is AGP 9.2.1's own bundled compiler.** Pinning a different
  version in the catalog does nothing. Kotlin's table only certifies KGP up to AGP
  9.1.0, so no release is certified against 9.2 — newest-everything is not safer,
  just less tested.
- **AGP 9.2.1**, not 9.2.0 (R8 `ClassNotFoundException: RecordTag`), not 9.3.0
  (days old, pulls Gradle 9.5.0). Gradle **must** track AGP: 9.2.x→9.4.1.
- **KSP moved to independent versioning at 2.3.0.** AGP 9 needs KSP ≥ 2.3.6;
  catalog pins 2.3.10. KSP 2.3.x is not tied to Kotlin 2.3.
- **`compileSdk 37` is forced** — current AndroidX refuses to build against less.

### The cmdline-tools can't see newer SDK packages
`sdkmanager` warns *"This version only understands SDK XML versions up to 3 but an
SDK XML file of version 4 was encountered"* and then can't find
`platforms;android-37`. Doesn't matter — AGP resolves what it needs — but don't
waste time on it.

### `security-crypto` is deliberately ABSENT from the catalog
See the credentials decision above. Don't add it.

### R8 only runs in release builds
Every debug CI run stays green regardless of the ProGuard rules, so a broken keep
rule first appears in the APK the agent installs. The gateway and GitHub models
carry `@Keep` and `proguard-rules.pro` keeps them by that annotation — verified as
matching, since a keep rule scoped to `@Keep` on classes that don't carry it keeps
nothing at all while looking protective.

---

## Notes on the reference material (local only, git-ignored)

`bingwa-auto-reply/` — the Google AI Studio UI generation. **UI reference only**,
and not a suitable base: `minSdk 24`, `namespace com.example`, pulls Firebase +
firebase-ai, and its `SmsReceiver`/manifest declare **SEND_SMS** (the pre-pivot
architecture — copying from it trips `ArchitectureGuardTest`, working as intended).

Two real bugs in it, not copied forward:
1. Its `darkColorScheme` is built from the *light* neutrals, so **dark mode renders
   light**. `ui/theme/Color.kt` fixes this.
2. It fetches fonts from Google Fonts **at runtime** — a network call on first
   render and a visible swap on low-end devices.

`app-icons/` — git-ignored; icons already copied into `app/src/main/res/mipmap-*/`.
⚠️ **Legacy flat PNGs — there is no adaptive icon.** With minSdk 30 every device
supports adaptive icons, so some launchers will mask or letterbox the flat PNG. A
proper fix needs separate foreground/background layers (108dp, 66dp safe zone),
which the flat export can't be split into automatically. Cosmetic; worth raising
with the client.

---

## Deviations from the build plan (per workflow rule 7)

1. **`targetSdk 36`, not "latest stable" (37)** — reasoned above. The only
   deviation from a stated constraint.
2. **CLAUDE.md constraint 8 ("all builds happen in CI") is now only half true.** A
   local JDK 21 + the existing SDK compiles and tests this project fine (see
   gotchas). CI remains the source of truth and the only thing that builds the
   agent's APK, but "there is no local compiler" is no longer accurate and cost
   earlier sessions a great deal of time.
3. **Phase 0's test step exceeds the plan** — real architecture guards rather than a
   trivially-passing test.
4. **`READ_PHONE_NUMBERS` and `ACCESS_NETWORK_STATE`** added beyond Phase 1's listed
   permission set — the first to satisfy the plan's own "display number if
   available" (impossible without it from API 33), the second for the queue
   worker's network constraint.
5. **`MatchType.NO_RULES_CONFIGURED` and `NotifyStatus.QUEUED`** added beyond Phase
   8's stated enum values — reasoned above.
6. **Phase 7 replaced Phase 1's `SetupScreen` outright**, as Phase 1 asked. Its
   `SetupViewModel` survives and is reused by onboarding — that's the code Phase 1's
   exit criteria were proven against.
7. **Doc filenames don't match the docs' own references.** `CLAUDE.md` and
   `BUILD-PLAN.md` both refer to **`02-BUILD-PLAN.md`** (actual file:
   `BUILD-PLAN.md`) and **`01-UI-DESIGN-PROMPT.md`**, which **does not exist in the
   repo at all**. Phase 7 was therefore built without a UI spec, against BUILD-PLAN
   Phase 7's prose and the brand palette. If the client has that Stitch spec, the
   screens should be checked against it.

### Retired
- ~~Deviation 0: Phases 1 and 2 merged with exit criteria unmet~~ — still true of
  those criteria (they need a device), but no longer a *deviation*: every phase is
  merged, and the outstanding items are tracked under "Still needs a real device".
- ~~Open decision 1 (API key storage)~~ → resolved, Keystore AES/GCM.
- ~~Open decision 3 (DI)~~ → resolved, manual.
- ~~Parallel-session collisions~~ → resolved during integration. The sessions are
  done; there is one branch now.
