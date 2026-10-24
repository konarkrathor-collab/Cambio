# Cambio: product requirements

**Audience:** one presenter on a MacBook Air (M4, 1440×932 effective), plus the faculty watching. They need to understand it in seconds.
**Out of scope:** phone layout, real sign-in, real money. Old terminal UI stays at `/demo`.
**Frozen:** the API contract and the SSE events. Any backend addition needs the user's approval first.

## Global
- Top bar: wordmark "Cambio", nav pills (Markets, Trade, Portfolio, Opportunities), right side a **Practice account** chip with the balance. No LIVE/REPLAY/backend/stream badges.
- A practice account is assigned automatically on first load from the preseeded traders and remembered in the browser.
- A footer line on every screen: "Practice account. Simulated money." The only data-state message is "Prices paused" when quotes are stale.

## Screens
**1. Markets (home).** Above the fold: the practice balance as a 64 px numeral with the account name, then a pill row of actions (Trade, See opportunities). Below: a list of pairs, each row with pair, price, change since opening the app, a small line of recent ticks. A lime banner appears only when a loophole exists: "Loop found: USD → SGD → HKD → USD, about +0.40%".

**2. Trade.** Header with the pair and one large price. Left: a line chart of the ticks and a depth chart toggle. Middle: order book as a ladder with best bid and ask emphasised. Right: order ticket, buy or sell, limit or market, price, quantity, with a plain sentence of what happens ("You buy 10 EUR/USD at 1.08350 and pay 10.8350") and clear errors. Below: recent trades.

**3. Portfolio.** Balances, then open orders each with a Cancel pill, then history of fills.

**4. Opportunities.** The currency map with the loop drawn in lime, a card for each loop with path, profit, and a **Take it** button. Taking it places the legs as ordinary orders and shows each fill. If the book lacks liquidity the card says so and the button is disabled with the reason. Empty state: "No loops right now. Real markets close these within milliseconds."

## Behaviours
Order entry validates before submitting. Cancelling is one click and final, and the button says so. Every interactive element is reachable by keyboard. Reduced motion is honoured.

## Acceptance
`npm test` and the production build pass; the old golden-path and demo checks pass against `/demo`; zero console errors on every screen; screenshots at 1440×932 and 1280×800 and 1920×1080 of every screen; WCAG AA contrast; design-review audit with findings fixed.
