// Order book maths: cumulative depth, spread and the depth chart's step series. Prices are
// handled in integer ticks where they are compared or subtracted.

import { numberToTicks, fromTicks } from './decimal.js';
import { tickDecimals } from './format.js';

/** Running volume totals from the best level outward (what `cumulative` must equal). */
export function cumulate(levels) {
  let total = 0;
  return levels.map((l) => {
    total += l.volume;
    return { ...l, cumulative: total };
  });
}

/** True if every level's `cumulative` is the running total of the volumes before it. */
export function cumulativeConsistent(levels) {
  let total = 0;
  for (const l of levels) {
    total += l.volume;
    if (l.cumulative !== total) return false;
  }
  return true;
}

/** The largest cumulative volume on either side (the depth bars' 100%). */
export function maxCumulative(book) {
  if (!book) return 0;
  const last = (side) => (side.length ? side[side.length - 1].cumulative : 0);
  return Math.max(last(book.bids), last(book.asks));
}

/** Best bid/ask, spread in ticks and as a decimal string, and the mid; null parts if a side is empty. */
export function spread(book) {
  if (!book) return null;
  const d = tickDecimals(book.tickSize);
  const bid = book.bids.length ? numberToTicks(book.bids[0].price, d) : null;
  const ask = book.asks.length ? numberToTicks(book.asks[0].price, d) : null;
  if (bid === null || ask === null) return { bidTicks: bid, askTicks: ask, ticks: null, text: null, mid: null };
  const ticks = ask - bid;
  const twiceMid = ask + bid; // the mid in half-ticks, kept exact
  const mid = twiceMid % 2 === 0 ? fromTicks(twiceMid / 2, d) : fromTicks(twiceMid * 5, d + 1);
  return { bidTicks: bid, askTicks: ask, ticks, text: fromTicks(ticks, d), mid };
}

/**
 * The depth chart's step series, both in ascending price order: bids as (price, cumulative
 * at or above this price), asks as (price, cumulative at or below this price).
 */
export function depthSeries(book) {
  if (!book) return { bids: [], asks: [] };
  const bids = cumulate(book.bids).map((l) => ({ price: l.price, cumulative: l.cumulative })).reverse();
  const asks = cumulate(book.asks).map((l) => ({ price: l.price, cumulative: l.cumulative }));
  return { bids, asks };
}
