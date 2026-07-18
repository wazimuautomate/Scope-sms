# memory.md — running technical memory

> Read with `CLAUDE.md` and `changelog.md` at the start of every session.
> Decisions and rationale, gotchas, per-phase state, open questions.
> Prune stale "in progress" notes once superseded.

---

## Phase status

All phases are on `main` as of 2026-07-18. Released: **v1.3.0 / versionCode
8** (trusted-senders whitelist), published via a manual `workflow_dispatch`
of `release.yml` against `main` for the existing `v1.3.0` tag — see "🔴 A
release can fail after the tag is pushed" below; the tag's own automatic
push-triggered run did not complete the release. PR #12 was fully CI-green;
PR #14 merged with the artifact-upload steps red (see "🔴 CI artifact storage
quota" below) — both are fine, read the entries before assuming red CI means
broken code.

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

## 🔴 A release can fail *after* the tag is pushed — v1.3.0's first attempt silently skipped signing/publishing (2026-07-18)

Direct continuation of the artifact-quota incident in the entry below this
one. Tagging `v1.3.0` triggered `release.yml` as normal, but the run
**failed outright** — not the cosmetic red the quota caused on `build.yml`
runs. Here, `Upload test + lint reports` failing (same storage quota) caused
**every subsequent step to be skipped**: `Materialise the signing keystore`,
`Build signed release APK`, `Verify the APK is signed`, `Publish the GitHub
Release`, `Generate update.json`, `Attach update.json to the Release`,
`Commit update.json to main` all show `-` (skipped), not `✓`. **No release
went out.** GitHub Actions' default behaviour — a failed step skips
everything after it in the same job unless that step sets its own
`continue-on-error` — had never mattered before because nothing this
consequential had followed an upload step.

### Fix: `continue-on-error: true` on every `upload-artifact` step
PR #17, `ci/decouple-artifact-uploads-from-pipeline`, merged straight to
`main` (small enough to skip a feature branch's usual scope, still went
through PR+CI per the workflow rule). Touches every `upload-artifact` step in
both `build.yml` and `release.yml` — five steps total. These are convenience/
audit uploads for a human, never gates; the steps that actually decide
whether code is good (`Run unit tests`, `Run Android lint`, `Run instrumented
tests`) and whether a release is trustworthy (`Build signed release APK`,
`Verify the APK is signed`, `Publish the GitHub Release`) are untouched and
still hard-fail normally. **Self-verifying**: this PR's own CI run went fully
green immediately, with the storage quota still exhausted — proof the fix
does what it says rather than just silencing a symptom.

### Then: publish v1.3.0 for real via `workflow_dispatch`
`release.yml` already accepted a manual `tag` input for exactly this kind of
retry (`workflow_dispatch: inputs: tag`). Once the fix was on `main`:
```
gh workflow run release.yml --ref main -f tag=v1.3.0
```
This runs the **current `main` workflow definition** (the fix) against the
**existing tag's source** (`ref: github.event.inputs.tag` in the checkout
step) — no need to delete and re-push the tag. Confirmed working:
`gh release view v1.3.0` shows the APK + `update.json` assets, and
`update.json` on `main` reads `versionCode: 8` / `versionName: "1.3.0"`.

**Lesson for next time a release run fails**: check *which* step failed and
what came after it before assuming a retry (of the same tag-push trigger)
will do anything different. If the failure is upstream of signing/
publishing and those steps show `-` rather than `✓`/`X`, the release did not
happen regardless of what the tag or `update.json` might already suggest —
verify with `gh release view <tag>` and the actual `update.json` content on
`main`, not just "the workflow ran."

---

## Trusted M-Pesa senders whitelist (2026-07-18)

Branch `feature/trusted-sms-senders` → PR #14, squash-merged to `main`
(`638405d`). Not yet released as its own version — merged to main but no
tag cut this session; next release should bump versionCode/versionName to
include it.

### The feature
The client runs a second service under his own registered sender ID
(`SKYSCOPE_`) that texts the **same till-confirmation format** M-Pesa uses.
Before this, `MpesaParser.isMpesaSender()` accepted only the official
`^M-?PESA$` shortcode (a deliberate security control — see the 2026-07-16
gotcha entry), so those messages were silently dropped as untrusted.

Deliberately **not** hardcoded to `SKYSCOPE_` in source, even though that's
the one sender the client asked for by name — that string is coincidentally
also `ui/settings/DEFAULT_SENDER_ID`, the app's *outbound* gateway sender-ID
prefill, but the two are unrelated settings (one is "who we send AS", this
one is "which inbound addresses we also trust as M-Pesa"). Baking in an
always-trusted address would silently change ingestion behavior for every
install the moment this ships, with no agent action and no way to see it was
on. Instead: a new **Settings → "Trusted M-Pesa senders"** section, empty by
default, where the agent adds/removes sender IDs themselves. Empty means
"official shortcode only" — identical to every install before this feature
existed.

### Shape of the change
- `SettingsRepository.trustedSenders: Flow<Set<String>>` — a native
  `stringSetPreferencesKey`, not a custom encode/decode like
  `SimSelection.encode/decode`; a flat set of strings needs no codec.
  `currentTrustedSenders()` is **not** `suspend` — mirrors
  `cachedSimSelection`'s pattern (a `@Volatile` cache warmed by
  `AppContainer.start()` via `settings.trustedSenders.launchIn(...)`) because
  `SmsReceiver` calls it synchronously, before `goAsync()`, same constraint-5
  requirement as the SIM filter.
- `MpesaParser.isMpesaSender(address, extraTrustedSenders = emptySet())` —
  the default keeps every existing caller (and all existing tests)
  unchanged. Match is case-insensitive and trimmed against the *exact*
  address, no `-?`-style pattern flexibility — a registered sender ID
  doesn't have M-Pesa's carrier-display quirks, so exact match is the
  correct level of tolerance, not a gap.
- `SmsReceiver.onReceive` now fetches `AppContainer.from(context)` **before**
  the sender check rather than after — the only reordering. `AppContainer` is
  a warmed process-singleton lookup, not I/O, so this doesn't reintroduce
  anything constraint 5 forbids; it's simply needed one statement earlier so
  the whitelist can be consulted before the accept/reject decision.

### 🔴 CI artifact storage quota — hit mid-session, fixed by deleting old artifacts
PR #14's CI runs failed, but **every substantive step passed** (unit tests,
lint, both API 30/36 instrumented test suites) — only the `Upload *`
artifact steps failed, with `Failed to CreateArtifact: Artifact storage
quota has been hit`. `gh api .../actions/artifacts` showed **239 artifacts,
~709MB** — three days of debug APKs (~14MB each), Room schemas, and test
reports, all still inside the workflows' 14-day `retention-days` (too young
to have organically expired). Deleted the 229 oldest via the API
(`DELETE /repos/.../actions/artifacts/{id}`), keeping the newest 10 (~14MB
total) — none of these are load-bearing; the release process pulls the APK
from a GitHub **Release** asset, a completely separate storage bucket from
Actions artifacts.

**The quota check did not clear after deletion.** A rerun still failed
identically. GitHub's own error text says *"Usage is recalculated every
6-12 hours"* — the upload-gate reads a periodic usage snapshot, not a live
count, so deleting artifacts doesn't unblock uploads until that next
recalculation. Confirmed this isn't fixable faster from here (no `user`
scope on this token to check account-level billing directly, and it
wouldn't change the recalculation cadence anyway).

**Client decision: merge anyway.** Given the actual tests/lint/instrumented
suites all passed and the only red steps were artifact uploads for a known,
external, time-boxed reason, merged PR #14 with those steps still failing.
**If this recurs:** don't re-delete blindly — check `gh pr checks <n>` for
which *specific* steps failed first. If it's `Run unit tests` / `Run
Android lint` / `Run instrumented tests` themselves, that's a real
regression; if it's only the `Upload ...` steps, it's this same quota issue
and the fix is the same (delete old artifacts, expect a multi-hour lag
before uploads work again, and treat the substantive step results — not the
overall run conclusion — as the pass/fail signal in the meantime).

**Retention is 14 days** (`build.yml`, `release.yml`) and this repo is only
days old, so nothing will organically expire for a while yet. Worth
revisiting if this recurs: either shorten `retention-days` (fewer artifacts
alive at once) or stop uploading the debug APK artifact on every single push
(it's rebuilt on every run and only useful for the one that produced it).
Neither was changed this session — scope was fixing the immediate block, not
redesigning the workflow.

---

## Name variables, bundle purchase-limit, softer failure styling, log copy menu (2026-07-18) — v1.2.0 / versionCode 7

Branch `feature/name-vars-purchase-limit-ui-polish` → PR #12, squash-merged
to `main` (`6c36188`). Six items the client reported after live use of
v1.1.1. No local JDK was available this session (memory's "local toolchain"
note from 2026-07-16 doesn't hold in every environment) — CI was the only
compiler, per CLAUDE.md constraint 8's baseline.

### `{first_name}` / `{last_name}` — long M-Pesa names were costing a segment
Some customer names run up to 50 characters, pushing a template past one GSM-7
segment. `TemplateEngine` gained `firstNameOf`/`lastNameOf` (split on the
first space; a single-word name leaves `{last_name}` as a clean gap, same
handling as a missing `{name}`) and `TemplateVariable.FIRST_NAME`/
`LAST_NAME`, allowed in **both** flows (general identity fields, not
flow-specific like `{package}`). No UI changes needed — the variable chips on
the Templates screen already render from `TemplateVariable.allowedFor(type)`.

### Bundle purchase-limit (once/day vs multiple/day) — the bundle-category pattern, repeated
Safaricom caps some offers to one purchase per number per day. Implemented as
a byte-for-byte repeat of the `BundleCategory` pattern (see "Bundle
categories" in the 2026-07-16 section below):
- `domain/rules/PurchaseLimit.kt` (new): `ONCE_PER_DAY` / `MULTIPLE_PER_DAY`,
  `DEFAULT = MULTIPLE_PER_DAY` — deliberately the *unrestricted* default, so
  every bundle the client already had entered keeps behaving exactly as
  before until they edit one to say otherwise.
- `PricingRuleEntity.purchaseLimit: String?` — nullable, no `@ColumnInfo`
  default, migrated via a plain `ALTER TABLE pricing_rules ADD COLUMN
  purchaseLimit TEXT` (`MIGRATION_2_3`, `DB_VERSION` 2→3). Confirmed via the
  CI `room-schemas` artifact that the resulting column
  (`` `purchaseLimit` TEXT ``, no default) is byte-identical whether reached
  by migration or by a fresh install at v3 — same as the category migration,
  and for the same reason (nullable-no-default dodges Room's default-value
  quoting validation entirely). `app/schemas/.../3.json` committed from the
  artifact, same workflow as `2.json`.
- `PriceListCodec`: optional `"purchase_limit"` key, version stays `1`
  (optional field — an older app ignores it, this app defaults an absent/
  unknown value to `DEFAULT`).
- Rules editor: a second `SingleChoiceSegmentedButtonRow` under Category.
  `RuleCard` only shows a badge when `ONCE_PER_DAY` — the common case
  (unrestricted) stays visually quiet, same convention as `rules_paused`.
- `TemplateVariable.PURCHASE_LIMIT("{purchase_limit}")`, **matched flow
  only** (parallels `{package}`). Always renders a non-blank phrase — "once a
  day" / "as many times as you like" — so the agent builds their own sentence
  around it exactly like they would around `{package}`, rather than the app
  baking in conditional copy. This was an explicit choice over a
  blank-unless-restricted design (asked, user picked always-a-phrase).

### Softer failure styling — red border + red text, not a red fill
Client: the solid `errorContainer` fill on a failed send was "too harsh".
`ActivityLogScreen.LogRow` and `HomeScreen.RecentReplyCard` both changed to
default card colors + a `BorderStroke(1.5.dp, colorScheme.error)`, with only
the status text (and `LogRow`'s failure-reason line) colored
`colorScheme.error`. Selection highlight in `LogRow` still wins outright — a
selected row is never also drawn with the failure border. Deliberately did
**not** touch the several other `errorContainer` usages in the app (Home's
setup-warning cards, Templates' validation card, Settings/OEM-guidance error
cards) — the client's complaint was specifically about the failed-*send*
card, not every red surface in the app.

### Bundle name added to Home's recent-replies card
`RecentReplyCard` already had `ActivityRecord.bundleDescription` available
(it flows in via `PaymentPlanner`) but never rendered it — `LogRow` on the
Activity log already did. Now both show it.

### Per-row copy menu on the Activity log
`LogRow` gained a 3-dot (`MoreVert`) `IconButton` + `DropdownMenu`: copy the
M-Pesa code, the phone number, or the full outbound message
(`replyBody` — disabled when null, i.e. `SILENT` rows where nothing was ever
rendered). Reuses the same `LocalClipboardManager` + `Toast` pattern the
existing multi-select "Copy" already used (see the 2026-07-16 gateway-fix
session).

### Version: 1.1.1 (6) → 1.2.0 (7)
Backward-compatible feature release per the project's own stated convention
in `app/build.gradle.kts`. Tagged and released in the same session — see the
release note below this section (or its own dated entry if this one has
scrolled past by the time you're reading it).

---

## 🔴 Release identity & in-app updates (2026-07-16) — supersedes earlier release notes

A client-driven pivot to a permanent, self-updating private distribution. This
section overrides the pre-pivot notes below about `com.scopesms.autoreply`,
`0.9.0`, the `SIGNING_*` secrets, and the rolling "testing" pre-release.

### 🔴 UPDATE (round 3) — the in-app updater now works on the PRIVATE repo (token + API)
The whole updater below was **broken in v1.0.0**: it read `update.json` from
`raw.githubusercontent.com` **unauthenticated**, and a private repo returns **404**
there (raw does not accept a PAT at all). Every check surfaced as
`ManifestUnreadable` → "Update information is not available right now" — the exact
error the agent reported. Fixed per the client's choice, "embed a read-only token":

- **Manifest** now via the GitHub **contents API**
  (`api.github.com/repos/.../contents/update.json?ref=main`,
  `Accept: application/vnd.github.raw` so the body is the file). `BuildConfig
  .UPDATE_MANIFEST_URL` changed accordingly.
- **Token** is host-scoped by an OkHttp **network interceptor** in
  `AppUpdater.create` — attaches `Authorization: Bearer` only when
  `request.url.host == api.github.com`. As a *network* (not application)
  interceptor it re-runs per redirect, and GitHub 302s an asset download to a
  pre-signed storage host: sending the token there both leaks it and trips "only
  one auth mechanism allowed" (400). Host-scoping drops it on that hop. The
  redirect follow-up is built from the original (token-less) request, so nothing
  leaks. Load-bearing; don't "simplify" it to a header on the request.
- **APK download** must use the asset's **api.github.com asset URL** +
  `Accept: application/octet-stream`. The browser `releases/download/...` URL is a
  web endpoint a PAT can't authenticate → 404 on a private repo. `release.yml`
  resolves the asset id post-upload (`gh api .../releases/tags/<tag>`) and writes
  that URL into update.json — traded the old deterministic-URL simplicity for a
  private repo that downloads.
- **Token source:** `UPDATE_READ_TOKEN` secret → `buildConfigField` (env/Gradle
  property), **never committed** (constraint 7). Empty in a build without the
  secret → `AppUpdater.isConfigured()` false → new `UpdateError.NotConfigured`
  ("automatic updates aren't set up… install manually"), not a scary error. CI:
  the secret is wired into both `release.yml` (assembleRelease) and `build.yml`
  (assembleDebug). Secret added by the agent 14:39.
- **`UpdateResolver` reordered:** "nothing newer → UpToDate" is decided **before**
  validating url/sha/name, so the seeded placeholder (versionCode 0, blank apkUrl)
  and any same-version manifest read as "you have the latest version", not an
  error. Only a genuinely *newer* versionCode with a bad/blank install field is
  `Unknown`.

**Token caveat the client accepted:** a fine-grained PAT scoped to Contents:Read
still reads the *whole* repo (GitHub can't scope to one file), and it ships inside
the APK (extractable). Cleaner long-term option if that ever bites: a separate
**public** repo holding only APK + update.json (no source), no token at all.

### 🔴 v1.0.0 is PUBLISHED — this fix is v1.0.1 / versionCode 2
A `v1.0.0` release (versionCode 1) was cut earlier on 2026-07-16 (release run
29501071679); its `update.json` sits on `main` (commit 49d9787) with the old
browser-download apkUrl. `versionCode` only ever increases and `release.yml` fails
a tag whose versionCode isn't strictly greater than `main:update.json`'s — so this
crash+updater fix is **1.0.1 / versionCode 2**. Because v1.0.0's own updater is
broken, the agent gets 1.0.1 by a **manual** install (from the Release page while
logged in, or the CI debug artifact); from 1.0.1 onward, in-app updates work.

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

### 🔴🔴 The SCOPE gateway's REAL success response is NOT what the docs say
Verified by calling the **live** endpoint (2026-07). A successful `POST /sendsms`
returns, under HTTP 200 with `Content-Type: text/html` (the body is JSON anyway):
```
{"status":"success","statusCode":"200","reason":"success","mobile":"254…",
 "invalidMobile":"","transactionId":"…","msgId":"","requestTime":"…"}
```
**Not** the documented `response-code`(int)/`messageid`. Key traps:
- `statusCode` is a **string** `"200"`, not an int.
- the trackable id is **`transactionId`** — `msgId` is **empty** on the immediate
  response (filled later; the dashboard's "Messageid" is a different, later value).
- success words are `status`/`reason` = `"success"`.

The app modelled only the documented fields, so a real send parsed to all-null →
`SendFailure.Unexpected` (**retryable**) → the queue resent it **5×** (per-job
retries don't dedupe — the transactionCode guard only stops *redelivered SMS*, not
retries of one job) and then logged FAILED. Every retry was a charged, delivered
SMS. `ScopeSmsGateway.interpret` now treats status=success / statusCode="200" /
any non-blank id as delivered→Sent, records `msgId ?: transactionId ?: messageid`,
and maps a non-blank `invalidMobile` to terminal InvalidPhone. Documented shape
kept as a fallback. **If the gateway wording changes again, re-capture with a live
curl before editing — do not trust the docs.**

### 🔴 gh multi-account silently breaks `git push` ("Repository not found")
Two accounts are logged into gh (`wazimuautomate` owns the repo; `Wazimu90` also
present). When the **active** account is `Wazimu90`, `git push` fails "Repository
not found" — the Windows `manager` credential helper serves the wrong account's
token, independent of gh's active account. Fix: `gh auth switch --user
wazimuautomate`, and this repo is now pinned with a **repo-local**
`credential.helper = !gh auth git-credential` (clears the inherited `manager`
first). If pushes fail again, check `gh auth status --active` first.

### 🔴🔴 Android's ICU regex rejects a lone `}` — the JVM does NOT (THE Messages crash)
**This was the Messages-tab crash all along** (round 4 found it; rounds 1–3 below
were chasing the wrong thing). The token regex was `\{[a-zA-Z_][a-zA-Z0-9_]*}` —
opening `{` escaped, closing `}` **bare**. On the desktop JVM (Robolectric, CI, all
300 unit tests) a lone `}` is a harmless literal. On Android's ICU-backed
`java.util.regex` (Samsung/Android 14 confirmed; `com.android.icu.util.regex`) it
throws `PatternSyntaxException` — at **class-init**, so it surfaced as
`ExceptionInInitializerError` the instant a screen touched `TemplateVariable`/
`TemplateEngine`, force-closing on open.

Why it hid: it is invisible to every test we can run, because our test JVM is not
Android's regex engine. **Rule: in any `Regex`, escape literal braces `\{ \}` and
brackets `\[ \]` even where the JVM lets you omit them — Android may not.** Grep
`Regex(` before shipping. Fixed in v1.0.3 (both TOKEN_PATTERNs) and kept escaped.

**The on-device `CrashReporter` (v1.0.2) is what cracked it** — it wrote the real
stack trace to `filesDir/last_crash.txt`, surfaced in Settings with Share. Keep it;
it is the only way to see a device-specific crash our tests can't reproduce.

### 🔴 A release can leave `main` behind — verify the fix is on main
`v1.0.3` was tagged on a branch commit whose regex fix **was never merged to main**
(PR #8 squashed an earlier state; the fix landed only on the tag). The released APK
was correct but `main` regressed to the buggy code with a v1.0.3 `update.json`.
`release.yml` only commits `update.json` to main, never the source — so cutting a
tag from an unmerged branch silently desyncs them. **Cut releases from main after
the PR merges, or re-verify the fix is on `origin/main` (git show).**

### 🔴 Room migration for a new column — nullable dodges the default-quoting trap
First real migration (v1→v2, the bundle `category` column). A `NOT NULL DEFAULT
'X'` string column makes the entity's `@ColumnInfo(defaultValue=…)` and the
migration's `DEFAULT` have to agree byte-for-byte (Room validates defaults, and
string quoting is easy to get wrong and impossible to verify without a device).
**Sidestep it: declare the field nullable (`String?`, no `@ColumnInfo` default),
migrate with a plain `ALTER TABLE … ADD COLUMN category TEXT`, and map NULL → the
domain default in `toDomain`.** Nullable-no-default is exactly what the ALTER
produces, so Room's runtime schema check passes. `2.json` is generated by CI
(room-schemas artifact) — commit it from there; it is not needed at runtime.

### 🔴 The Messages tab crash — rounds 1–3 (superseded by the ICU finding above)
Reported crashing "since build 1"; "fixed" twice by changing *layout* (round-1
`weight(1f)`, round-2 nested `Scaffold`) — both still crashed on the agent's phone.
**Two different layouts failing identically is the tell: the cause was never
layout.** Round 3 stopped guessing and got evidence.

A `TemplatesScreenTest` (Robolectric Compose, testDebug source set, `@Config sdk=30`)
now drives Compose's **real measure/layout pass off-device** over every data path
the crash was suspected to need — 5-rule `{bundle_list}`, non-default bodies, an
invalid-token error card, a multi-segment body, tab switching, live typing, and the
**faithful nested-Scaffold-inside-Scaffold** arrangement `MainScaffold` uses. **6
tests green: the current code does not crash.** This is the regression test the
screen never had. Static analysis agrees — the ViewModel flow, `TemplateEngine`,
`SmsSegments`, the `tpl_segments` format and the render path are all total, and the
nested Scaffold's content slot is measured with **finite** constraints, so there is
no infinity-max-height.

Shipped instead of a third layout rewrite:
- `TemplatesScreen` = thin ViewModel wrapper over a stateless `TemplatesContent`
  (identical behaviour), so the screen is testable with fabricated state.
- **Hardening:** `selectedTab.coerceIn(0, TemplateType.entries.lastIndex)` before
  `entries[...]` — a stale/corrupt `rememberSaveable` index can't
  `IndexOutOfBounds` and force-close on open. Plausible-but-unconfirmed as the real
  cause (saved instance state does not survive a reinstall, so it doesn't cleanly
  explain "every build"); kept as cheap insurance.

**CORRECTION to a prior claim in this file:** the round-2 note said Templates was
rebuilt to "the *exact* shape of Home and Settings". **False** — Home and Settings
are a plain `Column` + `verticalScroll` on the passed modifier with **no** inner
Scaffold; Templates is the lone screen with a nested Scaffold. That wrong note
probably misdirected rounds 1–2. Don't trust it; trust `TemplatesScreenTest`.

**Most likely the user is on an older APK** — the one-time uninstall for the
`com.scopesms.autoreply → com.tricreta.scopesms` package switch may never have been
done, so they run pre-fix code. The only alternative the evidence allows is an
**OEM-runtime** crash Robolectric can't see (Transsion/Tecno, Android 11). Needs a
device to settle. If 1.0.1 (post-uninstall) still crashes → add an on-device crash
catcher / pull a logcat; 1.0.1's working updater makes that iteration one-tap.

Activity still uses `LazyColumn(weight(1f))` — opens fine, still no on-device proof
of the with-data path (empty log early-returns before it composes).

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
