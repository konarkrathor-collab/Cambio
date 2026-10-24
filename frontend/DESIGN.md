# Dashboard design system (Stage 8)

This file is the brief for the dashboard's look and motion. It comes **before** the code. Every colour, size, radius, shadow and timing below lives exactly once, as a CSS custom property in `src/styles/tokens.css`, and components use only those names. If a value is not in the tables below, it should not be in the code.

This stage is a visual and interaction redesign, not a new feature set. The API, the data layer (`lib/api.js`, `stream.js`, `reducers.js`, `store.js`) and every label the tests and `documentation/DEMO.md` rely on stay as they are, unless a change is listed in the Stage 8 report.

---

## 1. Visual direction

**One sentence:** a night-shift desk terminal. It is near-black graphite, dense and quiet, with one amber signal colour that marks where attention belongs, and green and red that mean only market direction.

**The first three seconds.** A presenter opens the page on a projector. The audience should read three things before anything moves:
1. what is being traded (the pair and the book);
2. whether the data is real (the source badge, REPLAY or LIVE, never ambiguous);
3. that this is an instrument, not a marketing page.

The short intro (§6) shows these in that order.

**Reference points.** These are a blend; no single one is copied.
- **Bloomberg Terminal.** One loud signal colour (amber) on black, so anything amber means "look here". Information sits in labelled blocks, with no decoration between them.
- **Exchange DOM ladders** (the depth-of-market views in professional futures front ends). Rows of a fixed height, prices in a tabular monospace, a depth bar behind each row, and a short flash on the row that changed, not on the whole table.
- **Precision instrument panels** (oscilloscopes, avionics). Hairline rules, small uppercase labels in a condensed face, values set larger in monospace, and a live clock. The chrome is technical but calm.

**What is preserved from Stage 5:**
- the information architecture: four tabs, the DEMO drawer and the panel set;
- every visible label and state the tests and DEMO.md read;
- the honest labelling rules (REPLAY never looks LIVE; a miss is shown as a miss);
- the keyboard model and the 1024 px floor.

**What is overhauled:**
- type, colour, spacing, panel chrome and the top bar;
- the layout, which now fills the viewport like a terminal instead of leaving a gap below short panels;
- motion;
- empty, loading and error states.

---

## 2. Colour tokens

The neutral ramp is cool graphite. Contrast is measured against `--surface-panel`, where most text sits.

| Token | Value | Job | Contrast on panel |
| :--- | :--- | :--- | --: |
| `--surface-base` | `#08090b` | page background, gutters between panels | — |
| `--surface-panel` | `#0e1013` | panel bodies | — |
| `--surface-raised` | `#14171b` | panel headers, top bar, drawer, table headers | — |
| `--surface-input` | `#1a1e23` | inputs, segmented controls, buttons at rest | — |
| `--surface-hover` | `#20252b` | hover and selected rows | — |
| `--line` | `#262b32` | hairline rules between rows and panels | — |
| `--line-strong` | `#353c46` | panel borders, separators that structure the grid | — |
| `--line-input` | `#5b6370` | input and button outlines (non-text 3:1) | 3.14:1 |
| `--text` | `#eef1f4` | primary text and values | 16.81:1 |
| `--text-2` | `#c9d0d8` | secondary values | 12.25:1 |
| `--text-muted` | `#9aa3ae` | labels, column headers | 7.46:1 |
| `--text-faint` | `#848d99` | axis ticks, timestamps, meta (never on `--line`) | 5.67:1 (4.99:1 on input) |
| `--accent` | `#ffad33` | **the one accent**: active tab, focus ring, primary action, the loophole cycle, REPLAY/FALLBACK/DEMO caution, brand mark | 10.23:1 |
| `--accent-ink` | `#141006` | text on an accent fill | 10.19:1 on accent |
| `--bid` | `#3dd68c` | bids, BUY, profit, MET | 10.16:1 |
| `--ask` | `#ff6b74` | asks, SELL, loss, MISSED, faults | 6.90:1 |
| tints | `color-mix()` of the above | depth bars, flashes, cycle glow | — |

**Rules.**
1. **Green and red mean market direction:** bid/ask, buy/sell, profit/loss. Their only other use is a target that was met or missed, and a fault (backend down, a rejected order, MISMATCH), which always shows the word as well. Healthy system states ("backend ok", "stream open") are **neutral**, not green: a terminal that is working should be quiet.
2. **Amber means "look here":**
   - the selection and focus;
   - the detected loophole;
   - data that is not live: REPLAY, FALLBACK, stale, and everything in the DEMO drawer.

   LIVE is drawn in solid neutral, so the two can never be confused, and each badge carries its word.
3. **No decorative gradients.** Every gradient in the code draws something specific:
   - the depth bar behind each book row, a hard-stop fill proportional to cumulative volume;
   - the diagonal amber hatch that marks data which is not live, on the REPLAY/FALLBACK badge and the DEMO drawer's top edge;
   - the select chevron, two hard-stop triangles.

   There are no colour fades.
4. **Colour is never the only signal.** Every coloured thing also has a word or a shape: ASK/BID, BUY/SELL, MET/MISSED, ✓/✗, FOUND/CLEARED, the badge text.

---

## 3. Type

Both families are self-hosted woff2 files under `src/assets/fonts/` (SIL Open Font License; the license texts are next to the files). Nothing is fetched at runtime.

| Role | Family | Settings |
| :--- | :--- | :--- |
| Display: brand, panel titles, tab labels, field labels | **Archivo** (variable: weight 100–900, width 62–125) | Brand: width 125, weight 700, letter-spacing 0.02em. Panel titles and labels: width 75 (condensed), weight 600, uppercase, letter-spacing 0.1em |
| UI text: sentences, notes, empty states | Archivo, width 100 | weight 400/500 |
| Numbers: every price, volume, id, time and count | **IBM Plex Mono** 400/500/600 | `font-variant-numeric: tabular-nums slashed-zero` |

**Type scale** (px, line-height in brackets):

| Token | Size | Use |
| :--- | --: | :--- |
| `--fs-micro` | 10 (14) | axis ticks |
| `--fs-label` | 11 (16) | uppercase labels, column headers, panel titles |
| `--fs-data` | 13 (20) | table data, form values |
| `--fs-ui` | 14 (20) | sentences, buttons, tabs |
| `--fs-value` | 16 (24) | key values (account cash, profit) |
| `--fs-headline` | 22 (28) | the loophole cycle, the simulator speedup |
| `--fs-brand` | 15 (20) | brand name in the top bar |

**A deliberate exception to "body ≥ 16 px".** This is a dense desk terminal, viewed at 1280–1920 px and on a projector, where the value of a screen is how much of the book fits. Table data is 13 px in a monospace with a large x-height, and sentences are 14 px. Key figures are 16 px or larger.

---

## 4. Space, radius, elevation

- **Spacing: a 4 px base**, with every layout gap a multiple of 8. A terminal row grid needs the 4 px step: book rows are 20 px, and table cells are padded 4 × 8. Tokens `--sp-1` to `--sp-8` = 4, 8, 12, 16, 20, 24, 28, 32.
- **Radius**, by role, never one value for everything:

  | Token | Value | Applies to |
  | :--- | --: | :--- |
  | `--r-xs` | 2 px | chips, status tags, tree nodes |
  | `--r-sm` | 4 px | inputs, buttons, panels |
  | `--r-md` | 8 px | popovers (picker list), the outage notice, the intro card |

- **Elevation.** Panels are flat: a 1 px `--line-strong` border on `--surface-base`. Shadow is only for things that float:

  | Token | Applies to |
  | :--- | :--- |
  | `--shadow-pop` | picker list, tooltips |
  | `--shadow-overlay` | outage notice |

- **Layout.**
  - The view fills the viewport between the top bar and the DEMO drawer. Panels scroll inside, and the page does not scroll at ≥ 1280 × 800.
  - Columns use `minmax(0, …)` so nothing overflows.
  - Gutters are 8 px.
  - Behaviour by width:
    - **1920:** the extra width goes to the book and the graph.
    - **1440:** the reference.
    - **1280:** three columns, tighter.
    - **1024–1279:** two columns, with the page scrolling vertically.
    - **Below 1024:** the existing plain notice.

---

## 5. Components

| Component | Rest | Hover | Focus (keyboard) | Active / selected | Disabled |
| :--- | :--- | :--- | :--- | :--- | :--- |
| Button | `--surface-input`, `--line-input` outline | `--surface-hover`, text `--text` | 2 px `--accent` ring, offset 2 | pressed: inset | 50% opacity, no pointer |
| Primary (Run simulation, the BUY/SELL submit) | solid fill: BUY in `--bid`, SELL in `--ask`, Run in `--accent`, ink text | brighter by 8% | ring | — | as above |
| Input / select | `--surface-input`, `--line-input` | border `--text-faint` | ring + border `--accent` | — | text `--text-faint` |
| Segmented (BUY/SELL, LIMIT/MARKET) | as input | — | ring on the group item | filled with its meaning: BUY green, SELL red, others neutral-bright | — |
| Tab | `--text-muted` | `--text` | ring | `--text` plus a 2 px amber underline that slides between tabs | — |
| Panel | flat, title bar 32 px in `--surface-raised` | — | — | — | — |

**Panel states** (one `PanelState` component, four kinds):
- **loading:** a thin animated scan line plus the text;
- **empty:** a quiet glyph plus the sentence plus, where there is one, the next action;
- **error:** a `--ask` left rule, the word "Unavailable" and the server's message;
- **content.**

---

## 6. Motion

| Token | Value | Use |
| :--- | :--- | :--- |
| `--dur-instant` | 80 ms | hover, press |
| `--dur-quick` | 160 ms | tab underline, focus, toggles |
| `--dur-base` | 240 ms | trade row slide-in, panel content swaps |
| `--dur-slow` | 420 ms | depth chart morph |
| `--dur-flash` | 700 ms | a book level that changed |
| `--dur-pulse` | 1600 ms | loophole cycle pulse (loops while a cycle exists) |
| `--ease-out` | `cubic-bezier(0.16, 1, 0.3, 1)` | things arriving |
| `--ease-in-out` | `cubic-bezier(0.65, 0, 0.35, 1)` | things moving between two states |

**Every animation has a meaning:**

| Motion | What it says |
| :--- | :--- |
| A book row flashes, tinted by its side, when its volume changes | "this level just changed" |
| The depth chart morphs to the new shape | depth moved; you see where it went instead of a jump |
| A new trade slides in at the top of the feed | "a trade just happened" |
| The loophole legs glow; a dash pattern flows along them in the direction of conversion; the ring on each node pulses | "here is the loop, and it goes this way" |
| The amber tab underline slides between tabs | where you are |

**The intro** plays on the first load of a browser tab only:
- Total 1.2 s. The brand mark draws (0–400 ms), then the top bar items settle (200–600 ms), then the panels rise 8 px and fade in, staggered by 60 ms (400–1200 ms).
- **It never blocks.** Everything is clickable from the first frame, and the intro animates only opacity and transform.
- **It is skippable.** Any key, click or wheel input finishes it at once.
- It is skipped entirely on later loads in the same tab (`sessionStorage`) and under reduced motion.

**Never animated:** order entry. The ticket, its result and the cancel path appear at once. Prices also never tween: a number on screen is always a real value.

**`prefers-reduced-motion: reduce`.** Every non-essential animation and transition is off: intro, slides, morphs, pulses and flowing dashes. The changed book row is still marked, with a static outline, as before. The loophole is still shown, in a solid amber stroke.

---

## 7. Ban list

Checked after every panel, before it is committed.

| Banned | Instead |
| :--- | :--- |
| Purple/indigo/violet gradients, any decorative gradient | flat fills; gradient only for data (depth bars) |
| Centred hero over a mesh background; three identical cards in a row | a terminal grid of unequal panels, sized by what they hold |
| Default Inter, Roboto or system sans with slate greys | Archivo (variable width) + IBM Plex Mono, on a graphite ramp |
| Glassmorphism, frosted blur on surfaces | solid surfaces; shadow only on floating elements |
| Neon or cyan glow; pulse or glow keyframes on cards | the single glow in the app is on the loophole cycle, because it *is* the alert |
| One radius on everything | the 2 / 4 / 8 scale by role |
| Green for "ok" chrome, red/green as decoration | green/red only for market direction, met/missed and faults, always with a word |
| Emoji as icons | text labels, ✓ / ✗ as the only symbols |
| Motion for its own sake, or anything that delays order entry | the motion table above |
| Network fonts, CDNs, a UI kit, Tailwind | self-hosted woff2, hand-written CSS on these tokens |
| A second token source | `src/styles/tokens.css` is the only one |

**Not done, by decision.**
- **A light theme.** The brief asks for dark by default, and a desk terminal is used dark. The tokens are the single place a light theme would go.
- **Scroll-linked motion.** This is a fixed-viewport application with no long page to scroll, so scroll-scrubbed effects have nothing to attach to.
