// Demo tools (doc 08 Stage 5, the DEMO drawer). Everything here is labelled as a demo, and an
// injected loophole is sent with "replay": true so it can never be shown as live data.

import { fromTicks, numberToTicks } from './decimal.js';
import { tickDecimals } from './format.js';

/** A small seeded generator (mulberry32): the same seed gives the same flow. */
export function seededRandom(seed) {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

/** Mid prices for the demo's usual pairs, as exact decimal strings. */
export const DEFAULT_MIDS = {
  'EUR/USD': '1.08500',
  'GBP/USD': '1.30000',
  'USD/JPY': '156.870',
  'EUR/GBP': '0.83400',
  'AUD/USD': '0.66000',
  'USD/CHF': '0.88000',
};

/**
 * The mid, in ticks, to seed around: the book's last trade, else the middle of its best
 * prices, else a default for the pair, else 1 (or 100 when the quote is JPY).
 */
export function seedMidTicks(pair, book) {
  const tickSize = book?.tickSize ?? (pair.endsWith('/JPY') ? 0.001 : 0.00001);
  const d = tickDecimals(tickSize);
  if (book?.lastTradePrice != null) return numberToTicks(book.lastTradePrice, d);
  const bid = book?.bids?.[0]?.price;
  const ask = book?.asks?.[0]?.price;
  if (bid != null && ask != null) return Math.round((numberToTicks(bid, d) + numberToTicks(ask, d)) / 2);
  if (bid != null) return numberToTicks(bid, d);
  if (ask != null) return numberToTicks(ask, d);
  const def = DEFAULT_MIDS[pair];
  if (def) return numberToTicks(Number(def), d);
  return pair.endsWith('/JPY') ? 100 * 10 ** d : 10 ** d;
}

/**
 * N seeded orders from demo traders 1 to 11 around midTicks: sells from 2 ticks below to
 * 4 above, buys from 4 below to 2 above, so they overlap and cross while some rest.
 */
export function seedFlow({ pair, midTicks, decimals, n = 20, seed = 1 }) {
  const rnd = seededRandom(seed);
  const int = (lo, hi) => lo + Math.floor(rnd() * (hi - lo + 1));
  const orders = [];
  for (let i = 0; i < n; i++) {
    const buy = rnd() < 0.5;
    const offset = buy ? int(-4, 2) : int(-2, 4);
    orders.push({
      traderId: int(1, 11),
      pair,
      side: buy ? 'BUY' : 'SELL',
      type: 'LIMIT',
      price: fromTicks(midTicks + offset, decimals),
      quantity: int(1, 25),
    });
  }
  return orders;
}

/**
 * The +0.4% EUR -> GBP -> USD -> EUR loophole of the contract's example: EUR/USD 1.08,
 * GBP/USD 1.30 and EUR/GBP 0.83409 (fair value 0.830769...). Marked replay, so the badge
 * shows REPLAY; live quotes expire after 30 s, so the alert clears by itself.
 */
export function loopholePayload(nowMs) {
  return {
    source: 'finnhub-ws',
    replay: true,
    ticks: [
      { symbol: 'OANDA:EUR_USD', price: 1.08, ts: nowMs },
      { symbol: 'OANDA:GBP_USD', price: 1.3, ts: nowMs },
      { symbol: 'OANDA:EUR_GBP', price: 0.83409, ts: nowMs },
    ],
  };
}
