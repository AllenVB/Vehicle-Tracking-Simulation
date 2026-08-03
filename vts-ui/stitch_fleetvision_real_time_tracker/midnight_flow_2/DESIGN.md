---
name: Midnight Flow
colors:
  surface: '#131313'
  surface-dim: '#131313'
  surface-bright: '#393939'
  surface-container-lowest: '#0e0e0e'
  surface-container-low: '#1c1b1b'
  surface-container: '#201f1f'
  surface-container-high: '#2a2a2a'
  surface-container-highest: '#353534'
  on-surface: '#e5e2e1'
  on-surface-variant: '#bbcabf'
  inverse-surface: '#e5e2e1'
  inverse-on-surface: '#313030'
  outline: '#86948a'
  outline-variant: '#3c4a42'
  surface-tint: '#4edea3'
  primary: '#4edea3'
  on-primary: '#003824'
  primary-container: '#10b981'
  on-primary-container: '#00422b'
  inverse-primary: '#006c49'
  secondary: '#45dfa4'
  on-secondary: '#003825'
  secondary-container: '#00bd85'
  on-secondary-container: '#00452e'
  tertiary: '#ffb3af'
  on-tertiary: '#650911'
  tertiary-container: '#fc7c78'
  on-tertiary-container: '#711419'
  error: '#ffb4ab'
  on-error: '#690005'
  error-container: '#93000a'
  on-error-container: '#ffdad6'
  primary-fixed: '#6ffbbe'
  primary-fixed-dim: '#4edea3'
  on-primary-fixed: '#002113'
  on-primary-fixed-variant: '#005236'
  secondary-fixed: '#68fcbf'
  secondary-fixed-dim: '#45dfa4'
  on-secondary-fixed: '#002114'
  on-secondary-fixed-variant: '#005137'
  tertiary-fixed: '#ffdad7'
  tertiary-fixed-dim: '#ffb3af'
  on-tertiary-fixed: '#410005'
  on-tertiary-fixed-variant: '#842225'
  background: '#131313'
  on-background: '#e5e2e1'
  surface-variant: '#353534'
typography:
  headline-xl:
    fontFamily: Hanken Grotesk
    fontSize: 48px
    fontWeight: '700'
    lineHeight: 56px
    letterSpacing: -0.02em
  headline-lg:
    fontFamily: Hanken Grotesk
    fontSize: 32px
    fontWeight: '600'
    lineHeight: 40px
    letterSpacing: -0.01em
  headline-lg-mobile:
    fontFamily: Hanken Grotesk
    fontSize: 28px
    fontWeight: '600'
    lineHeight: 36px
  body-md:
    fontFamily: Inter
    fontSize: 16px
    fontWeight: '400'
    lineHeight: 24px
  body-sm:
    fontFamily: Inter
    fontSize: 14px
    fontWeight: '400'
    lineHeight: 20px
  label-md:
    fontFamily: Geist
    fontSize: 14px
    fontWeight: '500'
    lineHeight: 16px
    letterSpacing: 0.05em
  label-sm:
    fontFamily: Geist
    fontSize: 12px
    fontWeight: '500'
    lineHeight: 14px
    letterSpacing: 0.05em
rounded:
  sm: 0.25rem
  DEFAULT: 0.5rem
  md: 0.75rem
  lg: 1rem
  xl: 1.5rem
  full: 9999px
spacing:
  base: 4px
  xs: 8px
  sm: 16px
  md: 24px
  lg: 40px
  xl: 64px
  gutter: 24px
  margin-mobile: 16px
  margin-desktop: 48px
---

## Brand & Style
The design system embodies a "Premium Dark" aesthetic, blending the precision of high-end SaaS with the immersive quality of glassmorphism. The brand personality is professional, sophisticated, and fluid. It targets power users and professionals who require long-session endurance, hence the transition from harsh pure blacks to a refined charcoal-based architecture. 

The visual style utilizes **Glassmorphism** as its core functional metaphor—using blurred transparency to indicate hierarchy and depth. This is paired with **Minimalism** in layout to ensure the vibrant emerald accents remain the focal point without overwhelming the user. The emotional response should be one of calm focus, high-tech reliability, and effortless navigation.

## Colors
The palette is anchored by **Emerald Green (#10b981)**, a vibrant yet professional hue that signals growth and stability. This replaces the previous cyan to provide a more grounded, high-trust feel.

To improve readability and reduce ocular fatigue, the system moves away from `#000000`. The **Canvas** (lowest layer) uses a deep navy-charcoal (`#0f172a`), while **Surfaces** (cards, panels) use a softer charcoal (`#1e1e1e`). 

- **Primary:** Emerald Green for actions, progress, and success states.
- **Secondary:** Mint Green for subtle highlights and secondary interactive elements.
- **Surface Tiers:** Layered greys provide soft contrast without the "vibration" of high-contrast black/white interfaces.
- **Text:** High-grade off-white (`#f8fafc`) for primary content, and muted slate (`#94a3b8`) for secondary information.

## Typography
The typography system uses a tri-font strategy to balance character with utility. **Hanken Grotesk** provides a sharp, contemporary look for headlines. **Inter** is used for body copy to ensure maximum legibility across all display types. **Geist** is employed for labels and technical data, providing a developer-friendly, precise feel that aligns with the professional emerald palette.

Headlines should utilize tighter letter spacing to maintain a "locked-in" editorial look. Body text remains generous in line height to prevent "smearing" on dark backgrounds. Labels are always uppercase when using Geist to emphasize their functional role.

## Layout & Spacing
The system follows an **8px grid** (4px sub-grid for icons and small padding). The layout is a **12-column fluid grid** for desktop and a **4-column grid** for mobile. 

The philosophy focuses on "Negative Space as Luxury." Large margins (`lg` and `xl`) should be used to separate major content sections, while `md` (24px) is the standard for internal component padding. On mobile, gutters shrink to 16px to maximize horizontal real estate. Containers should always be center-aligned with a max-width of 1440px to ensure readability on ultrawide monitors.

## Elevation & Depth
Depth is created through **Tonal Layering** and **Glassmorphism** rather than traditional black shadows. 

1.  **Level 0 (Canvas):** Dark Navy-Charcoal background.
2.  **Level 1 (Cards):** Semi-transparent Charcoal (`#1e1e1e` at 80% opacity) with a 16px Backdrop Blur.
3.  **Level 2 (Modals/Popovers):** Higher opacity Surface color with a subtle 1px inner border (Emerald at 10% opacity) to simulate a light-catching glass edge.

Shadows, when used, are **Ambient Emerald Shadows**: highly diffused (30px-60px blur), low opacity (10-15%), and tinted with the primary Emerald Green to create a subtle "glow" rather than a dark void.

## Shapes
The design system utilizes a **Rounded** shape language to soften the "technical" feel of the dark theme and emerald accents. 

- **Standard Elements:** Buttons, inputs, and small cards use a 0.5rem (8px) radius.
- **Large Containers:** Main content areas and large feature cards use a 1rem (16px) radius.
- **Interactive Triggers:** Floating action buttons and specific chips may use the `rounded-xl` (24px) setting to differentiate them from static containers. 
- **Consistency:** Borders should be consistent—always use a 1.5px width for card outlines to maintain a premium, delicate appearance.

## Components
- **Buttons:** Primary buttons are solid Emerald Green with white text. Secondary buttons use a "Ghost" style: transparent background with a 1.5px Emerald border. Hover states should trigger a subtle emerald outer glow.
- **Glass Cards:** Cards must feature a 1px border using a light grey or emerald tint at 15% opacity to define the silhouette against the charcoal background.
- **Inputs:** Field backgrounds should be slightly darker than the surface they sit on. The focus state replaces the border with a 2px solid Emerald Green.
- **Chips:** Small, pill-shaped tags using a 10% Emerald fill and 100% Emerald text for high legibility and a "live" feel.
- **Lists:** Use subtle 1px dividers in a slate-grey at 10% opacity. Interactive list items should have a soft emerald tint on hover.
- **Navigation:** Top navigation should be a persistent glass bar with a `saturate` filter and `backdrop-blur` to keep content readable as it scrolls beneath.