# UI revamp research

The current work fixes integration and QR import. A full visual redesign is a
separate change, so that VPN state and routing changes remain reviewable.

## Skills to use

- [UI/UX Pro Max](https://github.com/nextlevelbuilder/ui-ux-pro-max-skill):
  visual direction, design tokens, hierarchy, contrast, interaction and
  glass-style guidance. It supports a `jetpack-compose` stack; select that
  explicitly rather than its default web stack.
- [Android Mobile Design](https://github.com/wshobson/agents/tree/main/plugins/ui-design/skills/mobile-android-design):
  native Compose/Material 3 patterns, adaptive layouts, touch targets and
  accessibility.

These are candidate skills inspected on GitHub, not dependencies bundled in
the app or installed automatically into the developer's global environment.

## Glass-inspired Android direction

Use a restrained glass treatment on navigation and elevated cards, with
legible opaque surfaces for credentials and connection health. Establish a
coherent typography, spacing and color-token system first; blur alone will
not fix information hierarchy.

The home screen should make connection state, selected gateway and the
connect/disconnect action immediately clear. Keep secondary telemetry compact
and expandable. Preserve text labels for DEGRADED and failures, independent
of color. Use native Android touch targets (at least 48 dp), font scaling,
TalkBack labels, and reduced-motion behavior.

“Liquid Glass” is a visual reference here, not a promise to use Apple's native
platform material. Any Android blur implementation needs an inexpensive
fallback on the project's API 26 baseline and contrast/performance validation.
Secure dialog/window behavior must survive all visual changes.

QR pairing should offer paste and **Import QR image**, with clear local-only
decoding feedback. The user explicitly chose no camera permission.
