# `reliability/` — owned by Phase 9

Keeping the app alive on hostile OEM builds, and telling the agent when it
isn't.

## Why this is a top-level package

Same reason `queue/` is (see memory.md): it's a reliability boundary, and
burying it under `data/` would hide it. BUILD-PLAN allowed either; be
consistent.

## What's here

| File | Role |
| --- | --- |
| `BootCompletedReceiver` | Runs the health check after a reboot. |
| `ReliabilityInspector` | Reads live device state into a snapshot. No logic. |
| `ReliabilityNotifier` | Tells the agent, out of band, that the app is broken. |
| `OemSettingsLauncher` | Best-effort deep link into the OEM's autostart screen. |

The **decisions** all live in `domain/reliability/` (`ReliabilityCheck`,
`OemAutostartGuide`) — pure Kotlin, JVM-tested, no Robolectric. This package is
only the Android boundary. Keep it that way: CI is the only compiler on this
project (CLAUDE.md constraint 8), so logic that needs a device to test is logic
that isn't tested.

## The two things to understand before changing anything here

**1. The OEM component names are guesses, and the instructions are the
contract.** Every autostart component in `OemAutostartGuide` is an undocumented
vendor internal that an OTA can rename. The Transsion ones — the market that
matters most — are *unconfirmed*, sourced from libraries that largely copy each
other. `OemSettingsLauncher` therefore probes and discards; nothing depends on a
link resolving. If you add a component, you **must** also add its package to
`<queries>` in the manifest, or Android 11+ package visibility makes it
unresolvable on every device, silently. `OemAutostartGuideTest` fails the build
if you forget.

**2. This code must never crash.** It runs in a boot receiver. A crash here is a
crash dialog every time the agent powers on their phone, produced by the code
whose job is reassuring them the app works. `OemSettingsLauncher.open()` catches
`Exception`, not `ActivityNotFoundException`, because HiOS throws
`SecurityException` on non-exported system activities.

## Not done — needs a real device

The 24-hour soak test, the reboot pass, and the airplane-mode queue test in
Phase 9's exit criteria are all real-device work and **none of them have been
run**. Which Transsion component actually resolves is also unknown. See
memory.md.
