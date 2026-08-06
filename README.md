# Scope SMS

An Android utility for a single Bingwa Sokoni (Safaricom reseller) agent.

It watches incoming M-Pesa till-confirmation SMS on a chosen SIM and, for each
payment, can independently:

- **Unmatched amount** → text the customer the correct price list (replaces a
  manual phone call).
- **Matched amount** → text the customer a purchase confirmation.
- **Matched, but outside the bundle's purchase window** → text the customer
  the correct window and reassure them the bundle is still coming, instead of
  an instant confirmation. Only relevant for bundles the agent has explicitly
  restricted to certain hours (Prices screen) — every bundle is buyable any
  time by default.

All three flows toggle on and off independently. That is an operational
control, not a nicety: on a busy day, sending a confirmation for *every*
matched purchase on top of the unmatched replies raises the volume under one
sender ID, which is a real deliverability/ban risk with SMS gateways.

Reading happens **on the phone, offline**. Sending goes through one of two
gateways, agent-selectable in Settings — **BlazeTech** or **HostPinnacle** —
each with its own API key and sender ID, not the phone's SIM.

---

## Status

**1.0.0 — first permanent release, under the app identity
`com.tricreta.scopesms`.**

CI green on the JVM suite. Distribution is a **signed release APK** cut from a
version tag; there are no "debug" downloads. An in-app updater
(**Settings → Check for updates**) reads a published `update.json`, downloads the
next APK, verifies it (SHA-256 + package name + signing certificate) and installs
it over the top — no data loss, no uninstall.

What that green tick does and doesn't mean is worth being precise about:

| Proven | Not proven |
| --- | --- |
| Parser, rules, templates, gateway failure handling, queue behaviour | The parser against **real** M-Pesa messages — we have **one** sample |
| ~10 payments in a burst → exactly one correctly-templated job each, no drops, no duplicates | Real `SMS_RECEIVED` delivery, including OEM redelivery |
| The Keystore really encrypts the API key on API 30 and 36 | Dual-SIM filtering |
| The app builds, the graph starts, Room opens | The screens look right; the app survives a day in a pocket |

**The parser is the one to watch.** It is green against ~30 variants, but every
case beyond the single real sample in `CLAUDE.md` is a *guess* at how M-Pesa
words things. See "What we still need from you".

---

## Installing

No Play Store — this installs directly from the GitHub Release.

1. Open the [Releases page](https://github.com/wazimuautomate/Scope-sms/releases)
   and pick the newest version.
2. Tap **`scope-sms-release-vX.Y.Z.apk`** on the phone — it downloads straight, no
   zip, no login.
3. Android will warn about installing from an unknown source — allow it for
   whichever app you're installing from (usually Files or Chrome). You can turn
   that back off afterwards.

> **One-time uninstall, first install only.** Scope SMS now has a permanent
> identity, `com.tricreta.scopesms`. If you have an *older* build installed (the
> previous `com.scopesms.autoreply` one), uninstall it once before installing
> `1.0.0` — its data lives on the old package and cannot be carried across a
> package-name change. **After that, every future update installs straight over
> the top and keeps your prices, templates and history**, because every release
> is signed with the same permanent key. You never uninstall again.

Updating later is easier still: **Settings → Check for updates** inside the app,
or just install a newer `.apk` over the top.

### Setting it up on the phone

1. **Permissions** — needed to read incoming SMS at all.
2. **SIM** — pick the SIM your till confirmations arrive on. The other SIM's
   messages are never even read.
3. **Gateway** — pick BlazeTech or HostPinnacle from the dropdown, enter that
   provider's API key and sender ID, then **send a test message to your own
   number**. Do not skip this: an unregistered sender ID is an account problem
   on the gateway's side that the app cannot fix, and this is where you find
   out. Each provider remembers its own key — switching the dropdown later
   doesn't lose the other one's.
4. **Battery** — allow background activity, or the phone will close the app and
   payments will be missed while the screen is off.
5. **Add your bundle prices** under **Prices**. Until you do, Scope SMS stays
   deliberately silent — it will record payments but text nobody, because with
   no price list there is nothing truthful to send.

Prices are **whole shillings** — `50`, not `50.00`. The app rejects decimals on
purpose.

---

## What we still need from you

### 🔴 5–10 real M-Pesa messages (highest value thing on this project)
We have exactly one. Please send redacted till confirmations — change the
digits, keep the **wording, spacing and punctuation exactly as they arrive**,
because that's the part the parser matches on.

Useful variants: a long customer name, a large amount, a message with no balance
line, a non-zero transaction cost.

**If a real payment is ever ignored**, that's this. Send us the message text.

### 🔴 The toggle defaults
Ships with **price-list replies ON**, **purchase confirmations OFF** — the build
plan's recommendation, not your answer. Confirm or change it.

### Real-device checks only you can run
- **Dual-SIM**: both SIMs listed with the right carriers? Does your choice
  survive a restart *and* a reboot?
- **Both screens in light and dark mode** — does anything look wrong?
- **Leave it overnight** on a Tecno/Infinix with the screen off, then send a
  payment. Does it still reply?
- **Reboot the phone**, then send a payment without opening the app.
- **Airplane mode on** → send a payment → **airplane mode off**. The reply should
  arrive by itself. (Activity shows it as "Sending…" in the meantime.)
- **Settings → Staying awake**: does the OEM autostart button open the right
  screen? On Tecno/Infinix this is our best guess — tell us what actually opens.

---

## When something goes wrong

The **Activity** tab is the answer to "why didn't my customer get a text?".
Every payment is recorded, including the ones that deliberately sent nothing:

| Row says | Means |
| --- | --- |
| **Sent** | The gateway accepted it. |
| **Sending…** | Queued. Normal — arrives when there's internet. |
| **No reply sent** | Deliberate: that toggle is off, or you have no prices set. |
| **Failed** | It says why in plain words — bad key, unregistered sender ID, no balance. |

**Failed** rows are shown in red on the dashboard because they're money-adjacent.
"Send failed" alone is never shown — the gateway's actual reason always is.

---

## For developers

### Building

CI is the source of truth and builds the APK the agent installs. But — contrary
to what earlier notes in this repo say — **there is a working local build**. No
Android Studio needed, just a JDK:

```bash
export JAVA_HOME=/path/to/jdk-21          # Temurin 21, unzipped anywhere
echo 'sdk.dir=C:/Users/<you>/AppData/Local/Android/Sdk' > local.properties
./gradlew test            # ~6 min cold, seconds warm
./gradlew assembleDebug
```

Forward slashes in `local.properties` — a backslash is an escape character in a
`.properties` file, so `C:\Users` silently becomes `C:Users`.

### Reading the code

Start at `telephony/SmsReceiver` → `telephony/PaymentPipeline` →
`domain/PaymentProcessor`. That's the whole app in three files; everything else
supports it.

Split by design: `domain/` is pure Kotlin (no Android, no Room) so the decisions
that matter test on the JVM in seconds. `PaymentPlanner` takes *snapshots* rather
than caches specifically so it cannot do I/O even by accident — the receive path
must stay off the disk and off the network.

`CLAUDE.md` is the operating contract, `memory.md` is why things are the way they
are (read it before changing anything — several obvious-looking changes are
obvious-looking traps), `BUILD-PLAN.md` is the phase plan.

### App identity (permanent — do not change)

| | |
| --- | --- |
| `applicationId` / `namespace` | `com.tricreta.scopesms` |
| First release | `versionCode = 1`, `versionName = "1.0.0"` |
| Keystore | `scope-sms-release.jks`, alias `scope-sms` |

Changing `applicationId` or the signing key breaks seamless updates — the next
APK installs *beside* the app instead of over it, orphaning the agent's prices and
history. Both are fixed for the life of the app.

Debug builds are deliberately separate: `applicationId com.tricreta.scopesms.debug`,
label **Scope SMS Debug**, signed with the throwaway debug key. They coexist with
the real app and are never distributed or used as an update.

### The signing key

Permanent RSA keystore, alias `scope-sms`. **It is the app's signature: whatever
signs `1.0.0` must sign every future release, or updating means uninstalling and
losing data.** Never committed (`.gitignore` blocks `*.jks`/`*.keystore`).

- **You already have it** — the base64 blob + password handed over previously.
  Skip to "GitHub secrets".
- **If it was lost** — no signed release has shipped under `com.tricreta.scopesms`,
  so generating a fresh permanent key now is safe. Run once, back up the `.jks`
  and both passwords somewhere safe (a password manager), then never again:

  ```bash
  keytool -genkeypair -v -storetype PKCS12 \
    -keystore scope-sms-release.jks -alias scope-sms \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Scope SMS, O=Tricreta, C=KE"
  # convert to base64 for the GitHub secret:
  base64 -w0 scope-sms-release.jks > scope-sms-release.jks.base64   # Linux
  base64 -i scope-sms-release.jks | tr -d '\n' > scope-sms-release.jks.base64   # macOS
  # verify the certificate fingerprint (record the SHA-256):
  keytool -list -v -keystore scope-sms-release.jks -alias scope-sms
  ```

### GitHub secrets

Settings → Secrets and variables → Actions → New repository secret:

| Secret | Value |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | the base64 of `scope-sms-release.jks` |
| `ANDROID_KEYSTORE_PASSWORD` | the keystore password |
| `ANDROID_KEY_ALIAS` | `scope-sms` |
| `ANDROID_KEY_PASSWORD` | the key password (same as the store password if you took the default above) |
| `UPDATE_READ_TOKEN` | a fine-grained PAT so the in-app updater can read this **private** repo (see below) |

CI decodes the keystore to the runner's temp dir, signs with it, and deletes the
runner afterwards; secrets never appear in logs. (CI also accepts the legacy
`SIGNING_KEYSTORE_BASE64` / `SIGNING_STORE_PASSWORD` / `SIGNING_KEY_ALIAS` /
`SIGNING_KEY_PASSWORD` names as fallbacks.)

#### The `UPDATE_READ_TOKEN` (in-app updates on a private repo)

Because the repo is **private**, an unauthenticated request to
`raw.githubusercontent.com` or a browser release-download URL returns **404** — so
the in-app updater must authenticate. It carries a read-only token, baked in from
this secret at build time (never committed; absent → the app just says "automatic
updates aren't set up" instead of erroring).

Create it at **GitHub → Settings → Developer settings → Fine-grained tokens →
Generate new token**:

- **Resource owner:** your account · **Repository access:** *Only select
  repositories* → `Scope-sms`
- **Permissions → Contents: Read-only** (this one covers both `update.json` and the
  release APK asset). Everything else "No access". *Metadata: Read-only* is added
  automatically.
- **Expiration:** pick a date you'll rotate by. When it expires, in-app update
  *checks* stop until you ship a new build with a fresh token — the app itself
  keeps running.

Copy the `github_pat_…` value and add it as the `UPDATE_READ_TOKEN` secret.
Trade-off: a Contents:Read token can read the whole repo and is extractable from
the APK. If that ever matters more than convenience, host APK + `update.json` in a
separate **public** repo instead and drop the token.

### Cutting a release

1. Bump **both** `versionCode` (strictly greater — CI enforces it) and
   `versionName` in `app/build.gradle.kts`. Semantic versioning: bug fix
   `1.0.0 → 1.0.1`, feature `1.0.1 → 1.1.0`, breaking `1.1.0 → 2.0.0`.
2. Tag it and push:

   ```bash
   git tag v1.0.0 && git push origin v1.0.0
   ```

`.github/workflows/release.yml` then, from that tag: runs tests + lint → checks
the tag matches `versionName` → checks `versionCode` is strictly greater than the
published one → builds and **signs** the release APK → verifies it with
`apksigner` → computes its SHA-256 → publishes a GitHub Release with
`scope-sms-release-vX.Y.Z.apk` → generates `update.json` and commits it to `main`.

- **Signed APK path (in CI):** `app/build/outputs/apk/release/*.apk`, published as
  the Release asset `scope-sms-release-vX.Y.Z.apk`.
- **Manual re-run:** Actions → Release → *Run workflow* with an existing tag.

### In-app updates & `update.json`

The updater reads a single manifest via the GitHub **contents API** (not
`raw.githubusercontent.com`, which 404s on a private repo) — set once in
`app/build.gradle.kts` as `BuildConfig.UPDATE_MANIFEST_URL`:

```
https://api.github.com/repos/wazimuautomate/Scope-sms/contents/update.json?ref=main
```

It sends `Accept: application/vnd.github.raw` (so the body is the file itself) and
an `Authorization: Bearer <UPDATE_READ_TOKEN>` header, attached only on the
`api.github.com` host so the token is never carried onto the download redirect.

The release workflow writes `update.json` (the placeholder at `main` is replaced by
the first real release). Note `apkUrl` is the **API asset URL**, not the browser
download URL — the only form the token can authenticate on a private repo:

```json
{
  "versionCode": 2,
  "versionName": "1.0.1",
  "apkUrl": "https://api.github.com/repos/wazimuautomate/Scope-sms/releases/assets/<id>",
  "sha256": "<APK SHA-256>",
  "releaseNotes": "…",
  "required": false,
  "minimumSupportedVersionCode": 1
}
```

**Settings → Check for updates** compares the installed `versionCode` to the
manifest, and if newer downloads the APK (with progress), verifies its SHA-256,
package name (`com.tricreta.scopesms`) and signing certificate, rejects+deletes
anything that fails, then hands it to the system installer via a `FileProvider`
`content://` URI. The install is never silent — Android always shows its
confirmation. `required: true` (or an install below `minimumSupportedVersionCode`)
makes the update non-dismissible.

### Seamless-update checklist

Every future update installs over the top with no uninstall and no data loss as
long as all of these hold — none of them change after `1.0.0`:

- [ ] `applicationId` stays `com.tricreta.scopesms`
- [ ] release APK is signed with the same `scope-sms` keystore
- [ ] `versionCode` strictly increases each release (CI enforces)
- [ ] the tag `vX.Y.Z` matches `versionName` (CI enforces)
- [ ] `update.json` on `main` points at the real Release asset with its real SHA-256
