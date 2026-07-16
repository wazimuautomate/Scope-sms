# `ui/` — Compose screens

**Owned by:** Phase 7.

## What belongs here
Compose screens, their ViewModels, navigation, and theme. Screens:
Onboarding → Home/Dashboard → Rules → Templates → Activity Log → Settings.

`theme/` already exists with the brand palette (Safaricom green, matched to the
app icon) and a real light/dark scheme. Phase 7 refines it.

## What Phase 7 must not miss
The UI spec in `01-UI-DESIGN-PROMPT.md` **predates the architecture pivot** and
is out of date on two screens. Per CLAUDE.md, don't silently redesign — flag to
the user if a refreshed design prompt is wanted. The known deltas:

- **Home** shows the two independent toggles (Unmatched auto-reply / Matched
  purchase-confirmation) at a glance, not buried in Settings. They're an
  operational control the agent uses to manage sender-ID ban risk on busy days.
- **Settings** needs an SMS Gateway section: API key (masked), sender ID, and a
  test-send button.
- **Templates** needs to distinguish the two template types.
- **Onboarding** gains a gateway-credentials step with a test send.
- **Activity Log** statuses are now `MATCHED_NOTIFIED`, `MATCHED_SILENT`,
  `UNMATCHED_REPLIED`, `UNMATCHED_SILENT`, `SEND_FAILED` (with the gateway's
  reason shown).

## Two traps in the AI Studio reference (`bingwa-auto-reply/`, local only)
1. **Its dark theme isn't dark.** `darkColorScheme` there is built from the
   light neutrals (`background = 0xFFFDF8F9`), so dark mode renders light.
   `ui/theme/Color.kt` here already fixes this — don't copy the reference back
   over it.
2. **It fetches fonts from Google Fonts at runtime.** That's a network call on
   first render and a visible font swap on the low-end devices this ships to.
   Bundle a font as a local resource if Phase 7 wants a brand typeface.

## Offline is a UI state, not an error
Sending needs connectivity; the app has no offline send path. So the UI has to
say something honest when there's no data — a queued reply is pending, not
lost. Design for it rather than letting it surface as a failure.
