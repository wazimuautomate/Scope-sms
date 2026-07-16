# Scope SMS

An Android utility for a single Bingwa Sokoni (Safaricom reseller) agent.

It watches incoming M-Pesa till-confirmation SMS on a chosen SIM and, for each
payment, can independently:

- **Unmatched amount** → text the customer the correct price list (replaces a
  manual phone call).
- **Matched amount** → text the customer a purchase confirmation.

Both flows toggle on and off independently. That is an operational control, not
a nicety: on a busy day, sending a confirmation for *every* matched purchase on
top of the unmatched replies raises the volume under one sender ID, which is a
real deliverability/ban risk with SMS gateways.

Reading happens **on the phone, offline**. Sending goes through the client's
**SCOPE SMS gateway** using a registered sender ID — not the phone's SIM.

---

## Status

**v1.0.0 — feature-complete, ready for real-device testing.**

CI green: **276 unit tests + 6 instrumented tests on API 30 and API 36**, 0
failures. Phases 0–11 are all on `main`.

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

## Installing the test build

No Play Store — this installs directly.

1. Open the [Actions tab](https://github.com/wazimuautomate/Scope-sms/actions),
   click the newest green **Build** run on `main`.
2. Download the **`scope-sms-debug-…`** artifact (it's a zip; the `.apk` is
   inside).
3. Copy the `.apk` to the phone and tap it.
4. Android will warn about installing from an unknown source — allow it for
   whichever app you're installing from (usually Files or Chrome). You can turn
   that back off afterwards.

> This build is **debug-signed** — fine for testing, not for real distribution.
> The first properly signed release will need a one-time uninstall/reinstall.
> See "Cutting a release".

### Setting it up on the phone

1. **Permissions** — needed to read incoming SMS at all.
2. **SIM** — pick the SIM your till confirmations arrive on. The other SIM's
   messages are never even read.
3. **Gateway** — your SCOPE API key and sender ID, then **send a test message to
   your own number**. Do not skip this: an unregistered sender ID is an account
   problem on SCOPE's side that the app cannot fix, and this is where you find
   out.
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

### Cutting a release

**Not done yet, and it needs a decision from you first.** The signing key is the
app's permanent identity: whatever signs v1.0.0 must sign every update forever.
Lose it and the agent has to uninstall — losing their prices, templates and
history — to install the next version. So it is yours to generate and keep, not
CI's and not ours.

```bash
# 1. Generate it. Keep the .jks somewhere backed up and private.
keytool -genkeypair -v -keystore scope-sms-release.jks \
  -alias scope-sms -keyalg RSA -keysize 4096 -validity 10950 \
  -dname "CN=Scope SMS, O=<your org>, L=Nairobi, C=KE"

# 2. Base64 it for GitHub.
base64 -w0 scope-sms-release.jks > keystore.b64
```

Add four repository secrets (Settings → Secrets and variables → Actions):

| Secret | Value |
| --- | --- |
| `SIGNING_KEYSTORE_BASE64` | contents of `keystore.b64` |
| `SIGNING_STORE_PASSWORD` | the keystore password |
| `SIGNING_KEY_ALIAS` | `scope-sms` |
| `SIGNING_KEY_PASSWORD` | the key password |

Then:

```bash
git tag v1.0.0 && git push origin v1.0.0
```

`release.yml` runs the tests, signs, verifies the signature with `apksigner`, and
attaches the APK to a GitHub Release with install instructions. It **fails** if
the tag doesn't match `versionName` in `app/build.gradle.kts` — a Release labelled
v1.1.0 whose APK reports 1.0.0 would make the in-app update prompt reappear
forever.

Bump `versionCode` **and** `versionName` together for each release.
