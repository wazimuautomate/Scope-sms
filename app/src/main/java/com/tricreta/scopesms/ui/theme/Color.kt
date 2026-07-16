package com.tricreta.scopesms.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Brand palette, carried over from the Google AI Studio UI reference so the
 * scaffold already looks like Scope SMS rather than stock purple Material.
 *
 * Phase 7 owns the real design pass — treat these as the seed, not the spec.
 */

// Safaricom green — the primary brand accent, matched to the app icon.
val ScopeGreen = Color(0xFF006B27)
val ScopeGreenBright = Color(0xFF008733)
val ScopeGreenDim = Color(0xFF6CDE7C)
val ScopeGreenLight = Color(0xFF89FB95)
val ScopeGreenDeep = Color(0xFF002107)

// Red — used by the design for urgent/failure states (e.g. a failed gateway
// send, which is money-adjacent and must be noticeable per BUILD-PLAN Phase 8).
val ScopeRed = Color(0xFFBC000E)
val ScopeRedBright = Color(0xFFE7151A)

val ScopeYellow = Color(0xFFEFDB16)

// Light scheme neutrals.
val LightBackground = Color(0xFFFDF8F9)
val LightSurface = Color(0xFFFDF8F9)
val LightSurfaceVariant = Color(0xFFE6E1E2)
val LightOnSurface = Color(0xFF1C1B1C)
val LightOnSurfaceVariant = Color(0xFF3E4A3D)
val LightOutline = Color(0xFF6E7A6C)
val LightOutlineVariant = Color(0xFFBDCAB9)

// Dark scheme neutrals.
//
// These are genuinely dark. The AI Studio reference defined a `darkColorScheme`
// whose background/surface were the *light* neutrals (0xFFFDF8F9), so its dark
// mode rendered as light — a real bug that Phase 7 must not copy forward.
val DarkBackground = Color(0xFF121212)
val DarkSurface = Color(0xFF1C1B1C)
val DarkSurfaceVariant = Color(0xFF3E4A3D)
val DarkOnSurface = Color(0xFFE6E1E2)
val DarkOnSurfaceVariant = Color(0xFFBDCAB9)
val DarkOutline = Color(0xFF8A9688)
val DarkOutlineVariant = Color(0xFF3E4A3D)

// Error role.
val ErrorLight = Color(0xFFBA1A1A)
val ErrorDark = Color(0xFFFFB4AB)
val ErrorContainerLight = Color(0xFFFFDAD6)
val ErrorContainerDark = Color(0xFF93000A)
val OnErrorContainerLight = Color(0xFF93000A)
val OnErrorContainerDark = Color(0xFFFFDAD6)
