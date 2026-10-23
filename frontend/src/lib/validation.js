// Order-ticket validation, mirroring contract §1-§3.1. Two kinds of finding:
//  - errors: the request would be a 400 (malformed), so it is not sent;
//  - warnings: a well-formed order the engine will REJECT with a reason (Appendix A). It can
//    still be sent, so every rejection path can be demonstrated from the ticket.

import { toTicks } from './decimal.js';
import { formatTickSize, normalizePair, tickDecimals } from './format.js';

export const MAX_QUANTITY = 1_000_000_000;
export const MAX_PRICE_TICK = 1_000_000_000;

const INTEGER = /^\s*\+?\d+\s*$/;

/**
 * form: {traderId, pair, side, type, price, quantity} as typed (strings).
 * ctx: {tickers: Map pair -> tickSize | undefined, account: {cash, marginLimit} | null,
 *       bestOppositeTicks: integer ticks of the best opposite price, null if that side is empty,
 *       undefined if unknown}.
 * Returns {errors, warnings, body}; body is null when there are errors.
 */
export function validateOrder(form, ctx = {}) {
  const errors = {};
  const warnings = {};
  const tickers = ctx.tickers || new Map();

  const traderText = String(form.traderId ?? '').trim();
  let traderId = null;
  if (!INTEGER.test(traderText)) errors.traderId = 'Choose a trader.';
  else {
    traderId = Number(traderText);
    if (!Number.isSafeInteger(traderId) || traderId <= 0) errors.traderId = 'The trader id must be a positive integer.';
  }

  const pair = normalizePair(form.pair ?? '');
  if (pair === '') errors.pair = 'Enter a pair such as EUR/USD.';
  const known = tickers.has(pair);
  if (pair !== '' && tickers.size > 0 && !known) {
    warnings.pair = `${pair} is not a listed pair: the engine will reject it (UNKNOWN_PAIR).`;
  }
  const tickSize = known ? tickers.get(pair) : 0.00001;
  const decimals = tickDecimals(tickSize);

  const side = String(form.side ?? '').toUpperCase();
  if (side !== 'BUY' && side !== 'SELL') errors.side = 'Choose BUY or SELL.';
  const type = String(form.type ?? '').toUpperCase();
  if (type !== 'LIMIT' && type !== 'MARKET') errors.type = 'Choose LIMIT or MARKET.';

  const qtyText = String(form.quantity ?? '').trim();
  let quantity = null;
  if (!INTEGER.test(qtyText)) errors.quantity = 'The quantity must be a whole number.';
  else {
    const digits = qtyText.replace(/^\+?0*(?=\d)/, '');
    quantity = digits.length > 15 ? Number.MAX_SAFE_INTEGER : Number(digits);
    if (quantity < 1 || quantity > MAX_QUANTITY) {
      warnings.quantity = `Outside 1 to 1,000,000,000: the engine will reject it (BAD_QUANTITY).`;
    }
  }

  let priceTicks = null;
  const priceText = String(form.price ?? '').trim();
  if (type === 'LIMIT') {
    if (priceText === '') errors.price = 'A LIMIT order needs a price.';
    else {
      const r = toTicks(priceText, decimals, MAX_PRICE_TICK);
      if (r.error === 'format') errors.price = 'The price must be a decimal number, such as 1.08505.';
      else if (r.error === 'zero') warnings.price = 'A price of zero: the engine will reject it (BAD_PRICE).';
      else if (r.error === 'fraction') {
        warnings.price = `Not a multiple of the ${formatTickSize(tickSize)} tick: the engine will reject it (BAD_PRICE).`;
      } else if (r.error === 'large') warnings.price = 'Above 1,000,000,000 ticks: the engine will reject it (BAD_PRICE).';
      else priceTicks = r.ticks;
    }
  }

  if (type === 'MARKET' && ctx.bestOppositeTicks === null) {
    warnings.type = 'Nothing on the other side: the engine will reject a MARKET order (NO_LIQUIDITY).';
  }

  // margin (Appendix A), only when everything before it would pass
  const account = ctx.account;
  if (Object.keys(errors).length === 0 && !warnings.pair && !warnings.quantity && !warnings.price) {
    if (account === null) warnings.traderId = 'No such account: the engine will reject the order (MARGIN).';
    else if (account) {
      const tick = type === 'LIMIT' ? priceTicks : ctx.bestOppositeTicks ?? null;
      if (tick !== null && quantity !== null) {
        const exposure = quantity * tick;
        const allowed = side === 'BUY' ? Math.min(account.cash, account.marginLimit) : account.marginLimit;
        if (exposure > allowed) {
          warnings.quantity = 'Exposure above the account\'s limit: the engine will reject it (MARGIN).';
        }
      }
    }
  }

  if (Object.keys(errors).length > 0) return { errors, warnings, body: null };
  const body = { traderId, pair, side, type, quantity };
  // the price goes as the string typed, so the server reads it exactly (contract §1)
  if (type === 'LIMIT') body.price = priceText;
  return { errors, warnings, body };
}

/** Appendix A reasons in plain words. */
export const REASON_TEXT = {
  UNKNOWN_PAIR: 'The pair is not listed.',
  BAD_QUANTITY: 'The quantity must be between 1 and 1,000,000,000.',
  BAD_PRICE: 'The price must be above zero, at most 1,000,000,000 ticks, and an exact multiple of the tick size.',
  NO_LIQUIDITY: 'A market order needs something on the other side of the book, and there was nothing.',
  MARGIN: 'The account cannot cover this order: no account, or the exposure (quantity × price in ticks) is above its limit.',
};

export const STATUS_TEXT = {
  FILLED: 'Filled in full.',
  PARTIAL: 'Partly filled.',
  QUEUED: 'Resting on the book.',
  REJECTED: 'Rejected by the engine.',
};
