# Dashboard (Stage 5, redesigned in Stage 8)

A React dashboard for the backend: order book, order ticket, trades and accounts; the
currency graph with the detected cycle; the simulator; the structure inspector; and
clearly labelled demo tools. It is built against the frozen contract in
`documentation/09_api_contract.md` and talks only to the backend. There is no CDN and no
other network request: the two typefaces (Archivo and IBM Plex Mono, SIL Open Font
License) are self-hosted woff2 files in `src/assets/fonts/`, bundled by Vite.

**Look and motion** are specified in [DESIGN.md](./DESIGN.md): the direction, the token
tables, the type scale, motion rules and the ban list. Every value lives once, in
`src/styles/tokens.css`. The stylesheets are split by area: `base.css` (shell, controls,
tables, states, intro), then `market.css`, `arbitrage.css`, `simulator.css`,
`inspector.css` and `demo.css`. With `prefers-reduced-motion: reduce` no animation or
transition runs. Before and after screenshots of every tab are in
`documentation/results/screens/`.

## Requirements

- Node.js **22.12 or newer** (Vitest 5 needs it) and npm 10.
- The backend: a JDK 17 or newer (see `backend/`).

## Run it

```sh
# 1. the backend, with the demo's twelve traders (terminal 1)
backend/run.sh serve --seed-demo-accounts          # http://127.0.0.1:8080

# 2. the dashboard (terminal 2)
cd frontend
npm ci                                              # exact versions from package-lock.json
npm run dev                                         # http://127.0.0.1:5173
```

On macOS, `npm ci` prints a warning that the optional `fsevents` package's install
scripts were not run (or were skipped). It is harmless: `fsevents` is an optional file-watching
helper pulled in by the build tools, and nothing in this project needs its install script.

Open <http://127.0.0.1:5173>. The window must be at least 1024 px wide: from 1024 to 1279 px
the columns reflow to two (classroom projector), and below 1024 px the page shows a notice
instead of a broken layout. The dev server proxies `/api` to `http://127.0.0.1:8080`,
including the event stream, unbuffered. The Python feed (`feed/`) is optional. Without it
the data-source badge says WAITING, and DEMO › Inject loophole drives the arbitrage view.

Other scripts:

```sh
npm test            # Vitest, once (no watch)
npm run build       # production build into frontend/dist
npm run preview     # serve the production build on 127.0.0.1:4173, with the same /api proxy
```

## Views

| Tab | What it shows |
| :--- | :--- |
| Top bar | Data-source badge: LIVE (green), or amber for FALLBACK, REPLAY or stale live data. WAITING and DISCONNECTED. Also backend health, retried with back-off; the event stream's state; and the engine's `seq`. |
| Top bar | Brand, the four tabs, the pair picker (Trie search, 150 ms debounce, arrow keys and Enter; the pair applies to Market and Inspector), the data-source badge, backend and stream state, the engine's seq, and a UTC clock. |
| Market | The order book ladder with depth bars and the spread; the depth chart; the order ticket; this session's resting orders with cancel; the trades feed (history plus live, newest first, 100 rows); accounts in simulation units. |
| Arbitrage | The 12-currency graph: the layout is settled once, so it never jumps. Dimmed nodes have no rate; faint dashed edges are stale; the cycle glows and pulses. Also the cycle's legs, multiplier and profit; the scanner's threshold, counts and source; and the last 20 loophole alerts. |
| Simulator | The `POST /api/simulate` form (presets, seed, baseline, profile, scan interval, volatility), checked against the contract's ranges, including the 200,000-order cap for deep runs with the baseline. Engine versus baseline table and bars, with `checksumMatch`. The doc 04 targets marked MET or MISSED; a miss is never hidden. The last result loads on mount. |
| Inspector | Per side: levels with order counts and the FIFO queue head, and the ladder's B+ tree shape. Refreshed on every book update for the pair and every 2 s. |
| DEMO drawer | Seed flow: N seeded crossing orders from traders 1–11. Inject loophole: a +0.4% USD/SGD, USD/HKD, SGD/HKD tick set sent with `"replay": true`, so the badge shows REPLAY. The feed never streams these pairs, so the loophole is not overwritten while a feed runs (backend/BUGS.md F3). It clears by itself when the quotes expire after 30 s. Disabled while the badge says LIVE. Every action is logged. |

## How it works

- `src/lib/api.js`: `fetch` wrapper. It returns parsed JSON, or throws
  `ApiError{status, code, message}` with the codes of contract §2. `NETWORK` (status 0, or a
  proxy 502/504) means the backend could not be reached.
- `src/lib/stream.js`: the one shared `EventSource('/api/stream')`, opened at start-up. On every
  (re)open the runtime refetches the book, trades, status, scan, graph and accounts, because
  missed events are not replayed (contract §4). A source the browser closed for good
  (for example a 503) is replaced after 3 s.
- `src/lib/reducers.js`: pure reducers for `BOOK_UPDATE`, `TRADE`, `LOOPHOLE_ALERT`,
  `RATE_TICK` and the snapshots. They also track the session's own resting orders, by `seq`,
  so a fill is never counted twice. `src/lib/store.js` applies queued events once per
  animation frame, so a burst of events costs one render.
- Prices: always shown with the pair's tick decimals (`src/lib/format.js`). A typed price is
  checked as an exact decimal string and sent as that string (`src/lib/decimal.js`).
- Order ticket validation (`src/lib/validation.js`) mirrors the contract in two levels.
  Input the server would answer with a **400** (not a number, a missing price, a fractional
  quantity) is an error, and the order is not sent. A well-formed order the engine will
  **reject** is a warning naming the reason (UNKNOWN_PAIR, BAD_QUANTITY, BAD_PRICE,
  NO_LIQUIDITY, MARGIN). It can still be sent, so every rejection path can be shown.

## Tests

`npm test` runs 122 Vitest tests in Node, with no browser (the error-boundary test renders
into `happy-dom`):

- the API wrapper's error mapping
- every reducer, including fill accounting for the session's own orders
- the batching store
- the stream client (with a fake `EventSource`)
- price, number and exact-decimal formatting
- cumulative depth and spread maths
- order-ticket and simulator-form validation
- the picker's Enter rule
- the badge mapping
- the doc 04 target evaluation
- the demo generators
- the graph layout
- the per-view error boundary: a failing view shows a recoverable message, the others keep working
- a replay of the recorded `/api/stream` transcript from contract §7 (read from the
  contract itself) through the reducers

## Golden path in a real browser (regression tooling)

`e2e/golden-path.mjs` drives the dashboard in headless Chromium through about 50 checks:

- orders that cross, the cash they move, cancel, and every rejection;
- the picker, seed flow, and an injected loophole that glows and then clears;
- both simulator profiles and the 409;
- the inspector, keyboard tabs, and the 1024 × 768 layout;
- a clean console, `kill -9` of the backend and the recovery, and reduced motion.

It starts its own backend (port 8080, temporary data) and dashboard server, so stop yours
first. Playwright is deliberately **not** in `package.json`: install it once, anywhere Node
can find it.

```sh
npm install -g playwright && npx playwright install chromium   # once
node frontend/e2e/golden-path.mjs              # against npm run dev
node frontend/e2e/golden-path.mjs --preview    # against the production build
SHOTS=/tmp/shots node frontend/e2e/golden-path.mjs   # also save screenshots
```

`e2e/demo-script.mjs` follows [documentation/DEMO.md](../documentation/DEMO.md) literally:
- it runs `start-all.sh` with no Finnhub key, so the feed replays the capture;
- it clicks through demo steps 1–7, checking what the audience should see at each;
- it then stops `start-all.sh` with SIGTERM, its Ctrl-C path.

```sh
node frontend/e2e/demo-script.mjs              # port 8080 must be free
PORT=8091 node frontend/e2e/demo-script.mjs    # another port
```

`e2e/screens.mjs` saves a screenshot of every tab, and of the open DEMO drawer, from a
system that is already running. It seeds flow, injects the loophole and runs a deep
simulation first, and it exits non-zero if the page logged anything to the console.

```sh
./start-all.sh                                  # in another terminal
node frontend/e2e/screens.mjs OUT_DIR --width 1440 --height 900 --prefix after
```

## Known behaviour

- The application logs nothing to the browser console. On a fresh server the Simulator
  asks `GET /api/simulate/last` only once `GET /api/health` reports `hasSimulation: true`,
  so no 404 is logged. `npm run dev` adds three lines of its own: Vite's `[vite] connecting…` /
  `connected.` and React's development-build DevTools notice. The production build
  (`npm run preview`) adds none.
- Chrome still logs any HTTP error the page receives as "Failed to load resource": for
  example a 409 when another client is running a simulation, or the proxy's 502s while the
  backend is down.
- A view that throws while rendering shows "… view failed" with a **Try again** button in
  its place; the other views, the top bar and the stream keep working.
