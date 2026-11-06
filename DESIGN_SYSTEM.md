# Profile Scouter design system

Material 3 is the foundation. Jetpack Compose renders the application controls;
the spatial profile cards use a native Canvas renderer supplied with the same
theme colors and typography sizes.

## Tokens

- `ui/theme/Theme.kt`: semantic color roles and shapes. A fixed light theme uses
  white and cool neutral surfaces, dark readable text and a restrained blue accent.
  Camera controls and profile panels use the same light palette.
- Use matching `on…` colors for text on a surface. Errors use `errorContainer` /
  `onErrorContainer`. Do not hardcode black text or neon diagnostic colors.
- `ui/theme/Type.kt`: Material typography. Screen headings use `headlineSmall`,
  section headings `labelLarge`, row titles `titleMedium`, explanations `bodyMedium`
  or `bodySmall`. Use the system sans-serif font and scalable Compose text.
- `ui/designsystem/ScouterComponents.kt`: `ScouterSpace` is the 4/8/16/24/32 dp
  spacing scale. Screen edges use 24 dp; control rows use 16 dp padding.
- Shapes: 8/12/20/28/32 dp from extraSmall to extraLarge. Use MaterialTheme.shapes.

## Shared components and interaction

- `ScouterSheet`: standard heading, explanation, close action and scrollable
  content, including keyboard and navigation-bar insets.
- `ScouterSection` / `ScouterAction`: grouped settings with a title and explanation.
- `ScouterSwitch`: the whole row toggles one accessible switch.
- `ScouterModeOption`: radio selection. Selecting a mode does not start sending
  images; the primary start button commits the choice.
- `ScouterNotice`: neutral feedback or an explicit error state.
- One primary action per task. Secondary actions use outlined or text buttons.
  Destructive resets require confirmation. Network transmission requires an
  explicit start action; demos are labelled as simulated data.
- Settings, connection and recognition use these components. Diagnostic fields,
  manual image tests and raw status live under explicit details controls.
- Native registration migrates the website's three-photo flow into Material 3
  app bars, fields, sections and a confirmation step. It sends to a separately
  configured PC registration endpoint only after explicit confirmation. Success
  requires a JSON receipt; HTML is never interpreted as a successful registration.

## Spatial profile cards

`ProfileCardStyle` bridges MaterialTheme into the Canvas renderer. Use an 88%-opaque
light surface with dark text and no dark text glow, keeping name / affiliation / bio
hierarchy. Place cards alongside people in world space; do not clamp them into
the viewport. Diagnostic distance labels are shown only with face-box debugging.

## Validation

Build and unit tests check code and behavior, not visual quality. Review on the
Pixel 8a with the keyboard open, long Japanese text, multiple profiles, light and
dark camera scenes, and larger system font settings before calling the UI final.
