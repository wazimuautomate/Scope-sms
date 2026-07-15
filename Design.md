---
name: Quiet Confidence
colors:
  surface: '#fdf8f9'
  surface-dim: '#ddd9da'
  surface-bright: '#fdf8f9'
  surface-container-lowest: '#ffffff'
  surface-container-low: '#f7f2f3'
  surface-container: '#f2edee'
  surface-container-high: '#ece7e8'
  surface-container-highest: '#e6e1e2'
  on-surface: '#1c1b1c'
  on-surface-variant: '#3e4a3d'
  inverse-surface: '#313031'
  inverse-on-surface: '#f4eff0'
  outline: '#6e7a6c'
  outline-variant: '#bdcab9'
  surface-tint: '#006e28'
  primary: '#006b27'
  on-primary: '#ffffff'
  primary-container: '#008733'
  on-primary-container: '#f7fff2'
  inverse-primary: '#6cde7c'
  secondary: '#bc000e'
  on-secondary: '#ffffff'
  secondary-container: '#e7151a'
  on-secondary-container: '#fffbff'
  tertiary: '#695f00'
  on-tertiary: '#ffffff'
  tertiary-container: '#bdad00'
  on-tertiary-container: '#474000'
  error: '#ba1a1a'
  on-error: '#ffffff'
  error-container: '#ffdad6'
  on-error-container: '#93000a'
  primary-fixed: '#89fb95'
  primary-fixed-dim: '#6cde7c'
  on-primary-fixed: '#002107'
  on-primary-fixed-variant: '#00531c'
  secondary-fixed: '#ffdad5'
  secondary-fixed-dim: '#ffb4aa'
  on-secondary-fixed: '#410001'
  on-secondary-fixed-variant: '#930008'
  tertiary-fixed: '#f9e526'
  tertiary-fixed-dim: '#dbc900'
  on-tertiary-fixed: '#201c00'
  on-tertiary-fixed-variant: '#4f4800'
  background: '#fdf8f9'
  on-background: '#1c1b1c'
  surface-variant: '#e6e1e2'
typography:
  display-lg:
    fontFamily: Outfit
    fontSize: 48px
    fontWeight: '700'
    lineHeight: 56px
    letterSpacing: -0.02em
  headline-lg:
    fontFamily: Outfit
    fontSize: 32px
    fontWeight: '600'
    lineHeight: 40px
    letterSpacing: -0.01em
  headline-lg-mobile:
    fontFamily: Outfit
    fontSize: 28px
    fontWeight: '600'
    lineHeight: 36px
  headline-md:
    fontFamily: Outfit
    fontSize: 24px
    fontWeight: '600'
    lineHeight: 32px
  body-lg:
    fontFamily: Plus Jakarta Sans
    fontSize: 18px
    fontWeight: '400'
    lineHeight: 28px
  body-md:
    fontFamily: Plus Jakarta Sans
    fontSize: 16px
    fontWeight: '400'
    lineHeight: 24px
  label-md:
    fontFamily: Plus Jakarta Sans
    fontSize: 14px
    fontWeight: '600'
    lineHeight: 20px
    letterSpacing: 0.01em
  label-sm:
    fontFamily: Plus Jakarta Sans
    fontSize: 12px
    fontWeight: '500'
    lineHeight: 16px
rounded:
  sm: 0.25rem
  DEFAULT: 0.5rem
  md: 0.75rem
  lg: 1rem
  xl: 1.5rem
  full: 9999px
spacing:
  base: 8px
  xs: 4px
  sm: 12px
  md: 16px
  lg: 24px
  xl: 32px
  gutter: 16px
  margin-mobile: 16px
  margin-desktop: 40px
---

## Brand & Style

This design system is built on the principle of **Trustworthy Utility**. It prioritizes clarity and efficiency, evoking a sense of reliability and local familiarity. The aesthetic leans toward **Modern Minimalism** with a focus on high legibility and structured information density. 

The visual narrative moves away from aggressive branding, instead using a generous white base to create a "breathing" interface. By balancing a vibrant green primary accent against a clean, neutral background, the UI feels professional and approachable. Red is utilized as a precision tool—highlighting critical actions or status indicators—rather than a dominant structural color, ensuring the user is never overwhelmed.

## Colors

The palette is anchored by a crisp **Surface White (#FCFCFC)**, providing a high-contrast foundation for text and data.

- **Primary Green (#2FA44A):** The signature color for section titles, primary buttons, and successful states. It represents growth and reliability.
- **Secondary Red (#E51219):** Applied with restraint. Use for thin headers, component outlines, or notification badges. It should occupy less than 5% of the total screen real estate.
- **Accent Yellow (#EFDB16):** Reserved for highlights, warning-level tags, and promotional chips to draw the eye without the urgency of red.
- **Text Black (#100F10):** Used for all body copy, pricing, and high-hierarchy labels to ensure maximum accessibility.
- **Muted Gray (#DFD6D0):** Used for subtle background containers, disabled states, and soft shading to separate content blocks without using harsh borders.

## Typography

The typographic scale uses **Outfit** for headlines to provide a modern, geometric structure that feels confident. **Plus Jakarta Sans** (substituted for Poppins for a more contemporary, cleaner aesthetic within the same classification) is used for all functional UI text, ensuring high legibility at small sizes.

- Use **Outfit Bold** for large display prices and section headers.
- Use **Plus Jakarta Sans Regular** for all body text to maintain a friendly, approachable tone.
- Capitalization should be used sparingly for labels to maintain the "quiet" nature of the design.

## Layout & Spacing

The system follows an **8px grid** (base unit). This ensures mathematical consistency across all margins and paddings. 

The layout utilizes a **Fluid Grid** for mobile and a **Fixed Grid** (max-width 1200px) for desktop environments.
- **Mobile:** 4-column layout with 16px margins.
- **Tablet:** 8-column layout with 24px margins.
- **Desktop:** 12-column layout with 24px gutters.

Whitespace is intentional; use the `xl` (32px) spacing to separate distinct logical sections, and `md` (16px) for internal component spacing.

## Elevation & Depth

To maintain the "Utility" feel, depth is created primarily through **Tonal Layers** rather than heavy shadows.

- **Level 0 (Base):** #FCFCFC background.
- **Level 1 (Cards/Containers):** White background with a 1px border of #DFD6D0 or a very soft, high-diffusion shadow (0px 4px 20px rgba(0,0,0,0.04)).
- **Level 2 (Modals/Overlays):** White background with a more pronounced shadow to indicate focus and separation.

Avoid using shadows on buttons; use solid color fills to indicate interactivity.

## Shapes

The shape language is defined by **rounded-lg (8px)** corners. This radius is applied to:
- Primary and Secondary Cards.
- Input Fields.
- Buttons and Interactive surfaces.
- Images and Thumbnails.

Tags and Badges may use a fully rounded (pill) shape to distinguish them from actionable buttons. Consistent corner rounding across all elements reinforces the approachable and trustworthy brand personality.

## Components

### Buttons
- **Primary:** Solid #2FA44A fill with White text. 8px border radius.
- **Secondary:** White background with a #E51219 1px border and Red text. Use for "Cancel" or secondary actions.
- **Tertiary:** Transparent background with #100F10 text and underline.

### Input Fields
- 1px #DFD6D0 border with 8px radius. 
- Focus state: 1px #2FA44A border with a subtle green glow (2px).

### Cards
- White background (#FCFCFC).
- 1px border (#DFD6D0).
- Padding should be consistently 16px or 24px depending on content density.

### Chips & Tags
- **Informational:** Muted Gray (#DFD6D0) background with Black text.
- **Promotion/Warning:** Yellow (#EFDB16) background with Black text.
- **Success:** Soft Green background (10% opacity of #2FA44A) with Primary Green text.

### Lists
- Use horizontal dividers with #DFD6D0 at 0.5px thickness.
- Provide ample vertical padding (16px) between list items for touch-friendly targets.