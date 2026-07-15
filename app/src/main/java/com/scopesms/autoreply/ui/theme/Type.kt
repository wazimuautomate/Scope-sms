package com.scopesms.autoreply.ui.theme

import androidx.compose.material3.Typography

/**
 * Stock Material 3 type scale for now.
 *
 * The AI Studio reference pulled a font from Google Fonts at runtime via
 * `ui-text-google-fonts`, which is a network fetch on first render — a poor
 * fit for an app whose only reason to touch the network is the SMS gateway,
 * and a visible font-swap on the low-end devices this ships to. If Phase 7
 * wants a brand typeface, bundle it as a local resource instead.
 */
val ScopeSmsTypography = Typography()
