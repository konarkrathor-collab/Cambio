# Cambio: design system (tokens)

All values live in `src/consumer/tokens.css`. Components use tokens only.

## Colour
| Token | Value | Job | Contrast |
| :--- | :--- | :--- | :--- |
| `--ink` | #141414 | Text, primary buttons | 18.4 on white |
| `--ink-2` | #5F5F5C | Secondary text | 6.4 on white, 5.9 on canvas |
| `--ink-3` | #6A6A64 | Captions, axis labels | at least 4.5 on white and canvas (verify when built) |
| `--white` | #FFFFFF | Cards, page | |
| `--canvas` | #F5F5F2 | Page behind cards, input fill | |
| `--line` | #E4E4DE | Hairlines | |
| `--gain` | #0A7D45 | Positive change, bid side | 5.2 on white |
| `--loss` | #C5281F | Negative change, ask side | 5.7 on white |
Gain and loss never appear without a sign or a word.

## Type
- **Display:** Hanken Grotesk, weight 500 to 600, tracking -0.02 em. Headings, big numerals, brand.
- **Text and numbers:** Geist, with `font-variant-numeric: tabular-nums` on every figure.
- Both are OFL and will be self-hosted as woff2; nothing loads from the network at run time. If Geist's tabular figures prove unsuitable in testing, swap in another OFL face and record it here.
- Scale (px): 12 caption, 14 small, 16 body (the minimum for body copy), 20, 28, 40, 64 hero numeral.

## Space, shape, elevation
- 8-point grid: 4 (hairline gaps only), 8, 16, 24, 32, 48, 64.
- Radius: 12 inputs and small chips, 24 cards, 9999 pills and buttons.
- Elevation: one soft shadow (`0 1px 2px rgba(20,20,20,.06), 0 8px 24px rgba(20,20,20,.06)`) for floating layers only. Cards on canvas use no shadow, only the contrast between white and canvas.

## Motion
| Name | Duration, easing | Use |
| :--- | :--- | :--- |
| `tick` | 240 ms, ease-out | Price change, row flash |
| `settle` | 420 ms, cubic-bezier(.2,.8,.2,1) | Chart and depth morph, bars |
| `enter` | 320 ms, ease-out | New row, card, route change |
| `draw` | 900 ms, ease-in-out | Loophole path drawing across the map |
All off (instant) under `prefers-reduced-motion: reduce`.

## Components
- **Pill button:** primary is ink with white text, 48 px tall; on an ink card the primary button inverts to white with ink text; quiet is canvas. Visible 2 px ink focus ring with a 2 px white gap. Disabled is a clear grey with a reason shown.
- **Nav pill:** the active one is filled ink with white text.
- **Card:** white, radius 24, padding 24.
- **Input:** canvas fill, radius 12, 48 px tall, label above, inline error text under it in loss colour with a word, never colour alone.
- **Figure:** `Figure` component renders any number with tabular numerals, a sign for change and a unit label.
- **Stale notice:** one quiet line "Prices paused" when the data is stale; nothing otherwise.
