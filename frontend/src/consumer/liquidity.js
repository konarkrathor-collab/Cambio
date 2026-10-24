// Practice liquidity: the exchange has no real counterparties, so a book can start empty. This
// posts a seeded set of ordinary limit orders from the demo traders around the live rate, through
// the normal order endpoint, and the screen says plainly that it did so.

import { store } from '../app/runtime.js';
import { api } from '../lib/api.js';
import { numberToTicks, fromTicks } from '../lib/decimal.js';
import { tickDecimals } from '../lib/format.js';
import { seedFlow } from '../lib/demo.js';

let round = 1;

/** Posts n orders around the pair's live rate (or last trade). Resolves with a count summary. */
export async function addPracticeLiquidity(pair, tickSize, n = 24) {
  const s = store.getState();
  const d = tickDecimals(tickSize);
  const live = s.rates[pair]?.rate ?? s.book?.lastTradePrice;
  if (!(live > 0)) throw new Error('There is no live price for this pair yet.');
  const mid = numberToTicks(Number(live.toFixed(d)), d);
  const orders = seedFlow({ pair, midTicks: mid, decimals: d, n, seed: round++ });
  let rested = 0;
  let filled = 0;
  for (const o of orders) {
    // the practice account is trader 10: keep it out of the seeded flow. These go straight to the
    // API, not through the store, so they never show up as the user's own open orders.
    const r = await api.placeOrder({ ...o, traderId: o.traderId === 10 ? 11 : o.traderId });
    if (r.restingQuantity > 0) rested += 1;
    if (r.filledQuantity > 0) filled += 1;
  }
  return { posted: orders.length, rested, filled, mid: fromTicks(mid, d) };
}
