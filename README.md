# Scope SMS

An Android utility for a single Bingwa Sokoni (Safaricom reseller) agent.

It watches incoming M-Pesa till-confirmation SMS on a chosen SIM and, for each
payment, can automatically reply to the customer — replacing the phone calls
the agent currently makes by hand to explain pricing.

Two flows, each independently switchable:

| Flow | Trigger | Reply |
| --- | --- | --- |
| **Unmatched** | Amount matches no bundle price | Sends the customer the correct price list |
| **Matched** | Amount matches a known bundle price | Sends a purchase confirmation |

Replies are sent through the agent's own **SCOPE SMS gateway** using their
registered sender ID, so messages arrive from "SCOPE SMS" rather than from the
agent's personal phone number.

Being able to run unmatched-only, matched-only, both, or neither is a
deliberate operational control, not a convenience toggle: on a busy day, high
volume under one sender ID is a real deliverability and ban risk with SMS
gateways.

---

## Status

| Phase | Scope | State |
| --- | --- | --- |
| **0** | Repo, scaffolding & CI pipeline | ✅ Done |
| 1 | Permissions & SIM identification | Not started |
| 2 | SMS ingestion & M-Pesa parser | Not started |
| 3 | Rules engine + in-memory cache | Not started |
| 4 | Two message template types | Not started |
| 5 | SCOPE SMS gateway client | Not started |
| 5b | Outbound queue & burst-speed architecture | Not started |
| 6 | Independent notification toggles | Not started |
| 7 | Compose UI | Not started |
| 8 | Activity log & dashboard stats | Not started |
| 9 | Reliability hardening | Not started |
| 10 | Cross-version testing | Not started |
| 11 | Release packaging & distribution | Not started |

The app currently builds, installs, and shows a placeholder screen. It does
not read or send anything yet.

---

## Building

**There is no local build.** This project is developed without Android Studio
installed — every build runs on GitHub Actions, and "it builds" means the CI
run is green. See `CLAUDE.md` constraint 8.

### Getting an APK

1. Push to any branch (or open a PR against `main`). CI starts automatically.
2. Open the **Actions** tab → pick the run for your commit.
3. Download the **`scope-sms-debug-<run>-<sha>`** artifact from the run summary.
4. Unzip it — inside is `app-debug.apk`.

The artifact name carries the commit SHA, so an APK on a phone can always be
traced back to the code that produced it. Debug artifacts are kept 30 days.

If the run is red, download the **`test-report-<run>`** artifact — that HTML
report is the only way to see which assertion failed without re-running CI.

### Installing on the agent's phone

The app is distributed as a direct-install APK, not through the Play Store
(Play's SMS/Call Log policy would require default-SMS-handler status, which is
out of scope). Installing therefore needs "install from unknown sources"
allowed for the browser or file manager doing the install.

Full end-user install instructions are written in Phase 11.

### Toolchain

Pinned in `gradle/libs.versions.toml`; CI provisions everything. JDK 17,
`minSdk 30` (Android 11), `compileSdk`/`targetSdk` 36 (Android 16).

`minSdk 30` is a hard floor, not a default — the target market is low-end
Android 11/12 devices (Tecno, Infinix, itel, Xiaomi are common among agents in
Kenya). Features get verified on the floor, not just the ceiling.

---

## Repository layout

```
app/src/main/java/com/scopesms/autoreply/
├── data/        Room + DataStore + encrypted settings
├── domain/      Parser, rule cache, matching, decision logic (pure Kotlin)
├── telephony/   SMS receiver + SIM identification — ingestion only
├── network/     SCOPE SMS gateway client
├── queue/       Outbound send queue + WorkManager worker
├── ui/          Compose screens + theme
└── di/          Dependency injection
```

Each package has its own `README.md` covering what belongs in it, which phase
owns it, and the constraints that apply. Read the one for the package you're
touching before you touch it.

### Project docs

| File | Purpose |
| --- | --- |
| `CLAUDE.md` | The operating contract. Non-negotiable constraints. Read first. |
| `BUILD-PLAN.md` | Phased implementation plan with per-phase exit criteria. |
| `memory.md` | Running technical memory: decisions, rationale, open questions, gotchas. |
| `changelog.md` | Dated log of session outcomes. |

### Local-only folders (not in git)

`bingwa-auto-reply/` (Google AI Studio UI reference) and `app-icons/` (icon
export) are reference material, ignored by git, and get deleted after Phase 8.
The launcher icons have already been copied into `app/src/main/res/mipmap-*/`.

`bingwa-auto-reply/` is a **separate, unrelated Gradle project** — don't build
in it, and don't copy from it without reading the traps listed in
`app/src/main/java/com/scopesms/autoreply/ui/README.md`.

---

## Architecture in one paragraph

An incoming SMS wakes a manifest-registered `BroadcastReceiver`. It checks the
SIM, parses the M-Pesa message, looks the amount up in an **in-memory** rule
cache, decides matched/unmatched, checks the relevant toggle, renders a
template, writes a job row, and returns — all synchronously, with no network
and no database read on that path. A separate WorkManager worker drains the job
queue over HTTP to the SMS gateway, retrying with backoff.

That split exists for one reason: the client's stated worst case is ~10
payments landing in 1–3 seconds, and nothing in the receive path is allowed to
block behind a network call.

---

## Privacy

SMS content is sensitive — customer names, phone numbers, transaction data.
Incoming parsing happens entirely on-device; nothing is uploaded. Outbound
reply text necessarily reaches the SCOPE gateway, since that's what sends it.

The gateway API key and sender ID are entered by the agent in-app and stored
encrypted on-device. They are never committed to this repo and never logged.
Cloud backup and device-transfer are disabled so this data can't leak off the
handset through a restore.
