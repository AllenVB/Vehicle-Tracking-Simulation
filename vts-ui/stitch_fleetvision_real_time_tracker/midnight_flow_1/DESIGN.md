---
name: Midnight Flow
colors:
  surface: '#0d1513'
  surface-dim: '#0d1513'
  surface-bright: '#323b38'
  surface-container-lowest: '#08100e'
  surface-container-low: '#151d1b'
  surface-container: '#19211f'
  surface-container-high: '#232c29'
  surface-container-highest: '#2e3734'
  on-surface: '#dbe5e0'
  on-surface-variant: '#b9cac4'
  inverse-surface: '#dbe5e0'
  inverse-on-surface: '#2a3230'
  outline: '#83948f'
  outline-variant: '#3a4a46'
  surface-tint: '#00dfc1'
  primary: '#d7fff3'
  on-primary: '#00382f'
  primary-container: '#00f5d4'
  on-primary-container: '#006c5c'
  inverse-primary: '#006b5b'
  secondary: '#adc6ff'
  on-secondary: '#002e69'
  secondary-container: '#006be3'
  on-secondary-container: '#f2f4ff'
  tertiary: '#fff5e4'
  on-tertiary: '#3c2f00'
  tertiary-container: '#ffd651'
  on-tertiary-container: '#745c00'
  error: '#ffb4ab'
  on-error: '#690005'
  error-container: '#93000a'
  on-error-container: '#ffdad6'
  primary-fixed: '#26fedc'
  primary-fixed-dim: '#00dfc1'
  on-primary-fixed: '#00201a'
  on-primary-fixed-variant: '#005144'
  secondary-fixed: '#d8e2ff'
  secondary-fixed-dim: '#adc6ff'
  on-secondary-fixed: '#001a41'
  on-secondary-fixed-variant: '#004494'
  tertiary-fixed: '#ffe086'
  tertiary-fixed-dim: '#eac33f'
  on-tertiary-fixed: '#231b00'
  on-tertiary-fixed-variant: '#574500'
  background: '#0d1513'
  on-background: '#dbe5e0'
  surface-variant: '#2e3734'
typography:
  display-lg:
    fontFamily: Inter
    fontSize: 48px
    fontWeight: '300'
    lineHeight: 56px
    letterSpacing: -0.02em
  headline-lg:
    fontFamily: Inter
    fontSize: 32px
    fontWeight: '400'
    lineHeight: 40px
    letterSpacing: -0.01em
  headline-lg-mobile:
    fontFamily: Inter
    fontSize: 24px
    fontWeight: '500'
    lineHeight: 32px
  title-md:
    fontFamily: Inter
    fontSize: 20px
    fontWeight: '500'
    lineHeight: 28px
  body-md:
    fontFamily: Inter
    fontSize: 16px
    fontWeight: '400'
    lineHeight: 24px
  label-sm:
    fontFamily: Geist
    fontSize: 12px
    fontWeight: '500'
    lineHeight: 16px
    letterSpacing: 0.05em
rounded:
  sm: 0.25rem
  DEFAULT: 0.5rem
  md: 0.75rem
  lg: 1rem
  xl: 1.5rem
  full: 9999px
spacing:
  unit: 4px
  container-padding: 24px
  gutter: 16px
  panel-gap: 12px
---

## Brand & Style
The design system is engineered for high-performance, real-time vehicle monitoring. It evokes a sense of "Mission Control" through a deep, immersive dark mode that reduces eye strain during long shifts. The aesthetic is a fusion of **Modern Minimalism** and **Glassmorphism**, emphasizing depth through translucency rather than heavy shadows. The emotional response is one of precision, futuristic efficiency, and calm authority. Every element is designed to feel like it is floating over a dark, infinite tactical map.

## Colors
The palette is anchored by a true black background (#0a0a0a) to provide maximum contrast for neon functional colors. 

- **Neon Teal** is the primary action color, reserved for critical interaction points and active tracking states.
- **Route Blue** defines standard paths.
- **Violation Red** and **Stop Green** provide immediate semantic recognition of vehicle status.
- **Surfaces** utilize varying levels of white alpha-transparency to create the glass effect, ensuring the map or background remains partially visible beneath UI panels.

## Typography
The system uses **Inter** for its neutral, highly legible qualities at all sizes, particularly for data-heavy tables and telemetry. **Geist** is introduced for labels and monospaced data points (like coordinates or speed) to enhance the technical, developer-centric feel of the interface. 

Headlines should maintain a light weight (300-400) to preserve the "Midnight" elegance. Use `label-sm` for all telemetry data and map annotations to ensure a crisp, tactical appearance.

## Layout & Spacing
This design system utilizes a **Fixed Grid** for control panels and a **Fluid Overlay** for map-based content. 

- **Desktop:** Sidebars and floating panels follow a 12-column logic, but are detached from the screen edges by a 24px safety margin.
- **Floating Panels:** Panels should have a consistent 12px gap between them.
- **Telemetry Densities:** Use a tight 4px or 8px rhythm for data lists to maximize information density without sacrificing legibility.

## Elevation & Depth
Depth is expressed through **Glassmorphism**. Rather than traditional shadows, the hierarchy is defined by:

1.  **Backdrop Blur:** All floating panels must use a 12px to 20px blur radius on the layer behind them.
2.  **Stroke Borders:** A 1px solid border at 10% white opacity (`rgba(255, 255, 255, 0.1)`) must define the edge of every container.
3.  **Z-Axis Layers:**
    *   **Level 0:** The Map (Base).
    *   **Level 1:** Floating sidebars and bottom sheets (Backdrop blur: 12px).
    *   **Level 2:** Modals and Pop-overs (Backdrop blur: 24px + subtle glow from Primary Teal).

## Shapes
The shape language is primarily **Rounded**, moving toward **Pill-shaped** for interactive controls.

- **Panels/Cards:** Use `rounded-lg` (16px) for all main containers.
- **Controls:** Buttons, toggle switches, and status badges must use `rounded-xl` or full "pill" radii to contrast against the structured map grid.
- **Indicators:** Vehicle markers on the map should be circular or directional arrow-pills.

## Components

### Buttons & Inputs
- **Primary Action:** Pill-shaped, Neon Teal (#00f5d4) background with black text for maximum punch.
- **Secondary/Ghost:** 1px white (10%) border, transparent background, white text.
- **Inputs:** Dark translucent fills (`rgba(0,0,0,0.3)`) with the standard 1px border. On focus, the border transitions to Neon Teal with a subtle outer glow.

### Vehicle Cards
- Use the standard 16px rounded glass container.
- **Header:** Title-md for vehicle ID, with a functional color dot (e.g., Green for "Moving", Red for "Violation").
- **Content:** Use Geist (label-sm) for coordinates and speed telemetry.

### Status Chips
- Height: 24px. 
- Appearance: Pill-shaped, low-opacity fill of the functional color (e.g., 20% Red) with a solid functional color text and border.

### Map Controls
- Floating vertical stack of square buttons with 8px rounded corners.
- Backdrop blur is essential here to keep map details visible through the controls.

### Lists
- Use thin 1px horizontal separators at 5% white opacity.
- Hover states should use a subtle 5% white background tint without removing the backdrop blur.