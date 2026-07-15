# memory.md — running technical memory

> Read with `CLAUDE.md` and `changelog.md` at the start of every session.
> Decisions and rationale, gotchas, per-phase state, open questions.
> Prune stale "in progress" notes once superseded.

---

## Phase status

| Phase | Scope | State |
| --- | --- | --- |
| **0** | Repo, scaffolding & CI pipeline | ✅ **Done** — CI green, APK artifact verified downloadable |
| **1** | Permissions & SIM identification | 🟡 **Code-complete, CI green (PR #2)** — real-device exit criterion NOT met, see below |
| **2** | SMS ingestion & M-Pesa parser | 🟡 **Code-complete, CI green (PR #3)** — exit criterion NOT met: still only 1 real sample |
| 3 | Rules engine + in-memory cache | Not started — ⚠️ **read "Money is cents" below before writing the entity** |
| 4 | Two message template types | Not started |
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

### ~~3. DI: manual vs Hilt~~ → ✅ **RESOLVED in Phase 1: manual DI**
`AppContainer`, built by `ScopeSmsApplication`, reached via
`AppContainer.from(context)` — which is how the `BroadcastReceiver` (constructed
by the system, handed nothing) gets the graph.

Why not Hilt: the graph is a handful of process-scoped singletons and will stay
that way; Hilt needs KSP, and CI is this project's only compiler, so every
annotation processor is a per-push cost and one more failure mode nobody can
reproduce locally (Room forces KSP in Phase 3 — that one is unavoidable, this
one wasn't); and `@AndroidEntryPoint` would have hidden the process-scope
lookup, not removed it.

**Settled — don't relitigate per phase.** Two rules for anything added to the
container: everything stays `by lazy` (it is constructed on every headless
process start an incoming SMS causes), and nothing holds an Activity `Context`.

### 4. Default state of the two notification toggles (owned by Phase 6)
BUILD-PLAN explicitly says confirm with the agent, don't assume. Starting
recommendation in the plan: unmatched=ON (the original pain point),
matched=OFF (higher volume, sender-ID ban risk). **Still unconfirmed.**

### 5. Real M-Pesa sample messages — 🔴 STILL OPEN, and Phase 2 shipped anyway
We have **exactly one** real till-confirmation sample (in CLAUDE.md).
BUILD-PLAN Phase 2 requires 5–10 more real redacted samples from the agent
before the regex is finalised. One sample cannot validate variant handling.
**Someone still needs to ask the client.**

**Status after Phase 2 (be honest about this):** the parser is written and its
84-test suite is green, but every case beyond the single CLAUDE.md sample is a
*constructed* variant — a hypothesis about M-Pesa's wording, not an observed
message. They are labelled as such at the top of `MpesaParserTest`. So Phase 2's
exit criterion ("tests pass against all collected real sample messages") is met
only in the vacuous sense that we collected one.

What this means concretely for whoever gets the samples:
- `MpesaParser.PATTERNS` is an **ordered list** precisely so a new real variant
  is a new entry + a test, not a rewrite. Add, don't restructure.
- A `Rejection.NOT_A_RECEIVED_MESSAGE` seen in the wild means *the regex is
  wrong*, not *the message was junk*. It's logged at WARN for that reason.
  `WRONG_TRANSACTION_TYPE` is the boring one and logs at DEBUG.
- The riskiest untested assumption is **the sender rule** (see gotchas below).

---

## Decisions made in Phases 1–2 (and why)

### 🔴 Money is **integer cents** everywhere — Phase 3 must match
`MpesaPayment.amountCents: Long`. `Money.parseCents()` / `Money.format()` in
`domain/parser/MpesaPayment.kt` are the only conversions.

**Phase 3: `PricingRule.amount` must be stored in cents too.** BUILD-PLAN's
schema just says `amount`, so this is the concrete choice. A rule table in
shillings against a parser in cents matches *nothing at all* — the app would go
live and silently reply to every single payment as "unmatched".

Why not `Double`: the app's core operation is `payment.amount == rule.amount`.
`20.10` as a double is `20.099999999999998`, so a bundle priced at 20.10 could
fail to match a payment of exactly 20.10 — and the customer gets a "you paid the
wrong amount" price list for a payment that was right. Integer cents make
equality exact. `Money.parseCents` also refuses `> Long.MAX` rather than
overflowing to a negative that could match an unrelated rule.

### 🔴 SIM choice is keyed on **physical slot**, not subscription ID
`SimSelection` stores slot indices (`ALL` or `SLOTS:0,1`), never subscription
IDs.

Subscription IDs are **not stable**: re-seat a SIM, factory reset, or on some
OEMs just reboot, and the same card returns with a different ID — BUILD-PLAN
Phase 9 already flags the reordering. Persisting one as the agent's choice means
their setting silently starts pointing at the *other* SIM, i.e. their personal
one. That is CLAUDE.md constraint 4's worst case, arriving with no error and no
warning. A physical slot ("the SIM in tray 1") survives all of it and is also
what the agent actually reasons about.

The cost, and it's real: the SMS intent carries a *subscription ID*, so the
receiver resolves subId → slot at delivery time via
`SimReader.slotForSubscriptionId()`. That's the price of a setting that doesn't
rot. **Phase 9's "re-validate saved subscription IDs after reboot" task is
therefore mostly already handled** — there is no saved subscription ID to
re-validate. What Phase 9 should still check is that the *slot* the agent picked
still holds a SIM.

### Unresolvable SIM slot → drop, except when unambiguous
`SimFilter` returns `Drop(UNRESOLVED_SLOT)` when the slot can't be determined
*and* several SIMs are active. Constraint 4 ranks a misdirected reply above a
missed one, and that is exactly the trade: process it and we might text the
agent's private contact; drop it and one customer misses an automated price
list.

The exception: with a **single active SIM** there is only one place the message
can have come from, so a missing extra is still unambiguous and we process it.
This matters because missing/renamed SMS_RECEIVED extras are precisely what
low-end OEM builds get wrong.

### Sender must be the M-Pesa shortcode — ⚠️ needs a real-device check
`MpesaParser.isMpesaSender()` requires the originating address to match
`^M-?PESA$`. This is a **security control**: without it, anyone who knows the
agent's number can text a fake "Ksh20.00 received from …" and make the app send
a stranger an SMS at the agent's expense — and, once Phase 8 lands, poison their
books with a payment that never happened. The originating address is set by the
network, so an ordinary sender can't forge it.

**Verified from documentation, not from the agent's handset.** If real payments
are ever dropped, this is the first place to look. The offending address is
logged (`"Ignoring SMS from non-M-Pesa sender 'X'"`) for exactly that reason.

### Battery-exemption status is read live, never persisted
CLAUDE.md's architecture section lists it among the DataStore settings.
`BatteryOptimizationManager.isExempt()` reads `PowerManager` on every call
instead, and `SettingsRepository` carries a comment saying why.

The agent can revoke the exemption in system settings at any moment, and an OEM
battery manager can revoke it *for* them with no signal to us. A persisted copy
goes stale silently — and then Settings shows a confident green "protected"
badge for an app the system is actively killing, which is the exact failure the
indicator exists to reveal.

### `READ_PHONE_NUMBERS` added beyond the plan's permission set
BUILD-PLAN Phase 1 lists the permissions to request and this isn't among them,
but it also asks for "display number if available" — and from API 33,
`SubscriptionInfo.getNumber()` requires this specific permission;
READ_PHONE_STATE alone returns blank. Both of the agent's SIMs may well be
Safaricom, in which case "Safaricom / Safaricom" disambiguates nothing and the
number is the only thing that does. Marked `isOptional`: deny it and the picker
degrades to slot + carrier.

### The hot path does no disk I/O
`SettingsRepository` keeps a `@Volatile` snapshot of the SIM selection;
`ScopeSmsApplication.onCreate` starts collecting the flow so the snapshot is
warm by the time the receiver asks (constraint 5). DataStore caches in memory
after its first read anyway, so this is belt-and-braces — but the SIM filter is
the one place where "cheap in practice" wasn't good enough.

Corollary for Phase 3/4: do the same for rules and templates. `AppContainer` is
where the cache goes.

### Everything that reads settings falls back rather than throws
`SimSelection.decode()` returns the default for null/blank/corrupt/unknown
input, and `SettingsRepository` swallows `IOException` into `emptyPreferences()`.
Both run on the SMS path, where an exception would take out ingestion entirely —
turning a bad *setting* into a total outage. A wrong SIM filter the agent can
see and fix; an app that stopped receiving they cannot.

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

### 🔴 Parallel sessions share ONE working directory — this bit us
All sessions are working in the same checkout, not separate worktrees. Observed
during Phase 1/2, not theorised:
- A `git add -A` swept another session's `Design.md` into a Phase 1 commit (had
  to be untracked in a follow-up).
- The Phase 9 session edited `AndroidManifest.xml` and `AppContainer.kt` *while
  Phase 2 was committing them*. Phase 2's commit was clean only by luck of
  timing.
- Untracked files follow you across `git checkout`, so another session's
  in-progress work is visible on — and committable to — your branch.

**Rules until this changes:** `git add` explicit paths, never `-A`/`.`. Check
`git status` before every commit and confirm every file listed is yours. Don't
switch branches while another session is mid-edit. Consider `git worktree` per
session — that would remove the whole class of problem.

### Kotlin enum entries can't read their own companion's constants
`minSdkInclusive = SDK_TIRAMISU` inside an enum entry fails with *"Companion
object of enum class 'AppPermission' is uninitialized here"* — entries are
constructed before the companion initialises. Cost a red CI run. Fix: file-level
`private const`, re-exposed by the companion. See the top of `AppPermission.kt`.

### Reading intent extras: never pass a default
`intent.getIntExtra("slot", 0)` turns every device that doesn't publish the key
into a confident **"slot 0"** — a wrong answer wearing a right one's clothes, on
the highest-severity decision in the app. `SmsReceiver.readInt()` returns null
instead, and also accepts Long/String because some OEMs write the value with the
wrong type (a `ClassCastException` there would crash the receiver).

There is no single reliable extra for "which SIM": AOSP uses `"subscription"`,
`SubscriptionManager` documents `EXTRA_SUBSCRIPTION_INDEX`, and OEMs invented
their own. `SubscriptionExtras` tries them in a defined precedence — some builds
ship several keys with *different values*, so the order is load-bearing, not
cosmetic.

### DataStore 1.2.1 resolves and works — first catalog "later phases" pin proven
Phase 0 flagged every unused catalog entry as researched-but-never-resolved.
`androidx.datastore:datastore-preferences:1.2.1` is now exercised by a green CI
build. The rest (Room, WorkManager, Retrofit, OkHttp, Moshi, Robolectric, Truth)
are still unproven.

### `SubscriptionInfo.getNumber()` is mostly useless — don't key logic on it
Returns empty far more often than not (Kenyan SIMs commonly have no number
provisioned on the card), needs READ_PHONE_NUMBERS from API 33, and is
deprecated from 33 in favour of `SubscriptionManager.getPhoneNumber(int)`. We
deliberately do **not** branch to the new API: same permission, same failure
modes, and the value is only ever a label on a radio button. `SimInfo.phoneNumber`
is nullable and every caller treats it as decoration.

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

0. **Phases 1 and 2 were merged to `main` with their exit criteria unmet.**
   The most important entry here — read it before treating either as done.
   - Phase 1's criterion is a **real dual-SIM device** listing both SIMs and
     persisting the filter across restart and reboot. There is no device in this
     workflow; only the agent can run it.
   - Phase 2's criterion is the suite passing against **all collected real
     samples**, and we have one (open decision 5).

   They were shipped anyway because Phases 3/4/5 are being built in parallel and
   all of them need `AppContainer`, `SettingsRepository` and `Money` — blocking
   on a device test nobody here can run would have stalled every other session.
   The trade is deliberate and reversible; both are **code-complete, not
   verified-complete**, and neither should be called done in a client update
   until the agent has run the checks in `README.md`.

1. **`targetSdk 36`, not "latest stable" (37)** — reasoned above, flagged for
   Phase 10. This is the only deviation from a stated constraint.
2. **Phase 0's test step exceeds the plan.** The plan permits a trivially
   passing test; we ship real architecture guards instead. Strictly more than
   asked for, but justified by the parallel-session risk.
3. **`READ_PHONE_NUMBERS` and `ACCESS_NETWORK_STATE` added** beyond BUILD-PLAN
   Phase 1's listed permission set — the first to satisfy the plan's own
   "display number if available" (impossible without it from API 33), the second
   for Phase 5b's network-constrained queue worker. Both reasoned above.

4. **Phase 1 ships a UI the plan didn't ask for.** `SetupScreen` is deliberately
   plain — no design language, no motion, no attempt at the Stitch layouts.
   Phase 1's exit criteria can only be proven by tapping through on a real
   device, and that needs something installable. **Phase 7 should replace it
   outright**, not extend it.

5. **Doc filenames don't match the docs' own references.** `CLAUDE.md` and
   `BUILD-PLAN.md` both refer to **`02-BUILD-PLAN.md`** (actual file:
   `BUILD-PLAN.md`) and **`01-UI-DESIGN-PROMPT.md`**, which **does not exist in
   the repo at all**. The UI spec Phase 7 is told to implement is therefore
   missing — the only UI reference is the local `bingwa-auto-reply/` folder,
   which is itself out of date vs. the pivot. **Phase 7 will need this
   resolved.** Files were left un-renamed deliberately: parallel sessions were
   given the current names, and renaming mid-flight would break them.
