import { describe, expect, it } from 'vitest';
import { REASON_TEXT, validateOrder } from './validation.js';

const tickers = new Map([['EUR/USD', 0.00001], ['USD/JPY', 0.001]]);
const rich = { cash: 5_000_000_000, marginLimit: 3_000_000_000 };
const form = (over = {}) => ({ traderId: '1', pair: 'EUR/USD', side: 'BUY', type: 'LIMIT', price: '1.08505', quantity: '10', ...over });
const v = (over, ctx = {}) => validateOrder(form(over), { tickers, account: rich, ...ctx });

describe('order ticket validation', () => {
  it('accepts a valid limit order and sends the price as the typed string', () => {
    const r = v({});
    expect(r.errors).toEqual({});
    expect(r.warnings).toEqual({});
    expect(r.body).toEqual({ traderId: 1, pair: 'EUR/USD', side: 'BUY', type: 'LIMIT', quantity: 10, price: '1.08505' });
  });
  it('normalizes the pair and omits the price of a market order', () => {
    const r = v({ pair: 'eur-usd', type: 'MARKET', price: '' }, { bestOppositeTicks: 108510 });
    expect(r.body).toEqual({ traderId: 1, pair: 'EUR/USD', side: 'BUY', type: 'MARKET', quantity: 10 });
  });

  describe('400s are blocked (errors, no body)', () => {
    it.each([
      ['no trader', { traderId: '' }, 'traderId'],
      ['trader zero', { traderId: '0' }, 'traderId'],
      ['no pair', { pair: '' }, 'pair'],
      ['bad side', { side: 'HOLD' }, 'side'],
      ['bad type', { type: 'STOP' }, 'type'],
      ['limit without price', { price: '' }, 'price'],
      ['price not a number', { price: '1.0x' }, 'price'],
      ['price with exponent', { price: '1e5' }, 'price'],
      ['negative price', { price: '-1' }, 'price'],
      ['fractional quantity', { quantity: '1.5' }, 'quantity'],
      ['negative quantity', { quantity: '-1' }, 'quantity'],
      ['empty quantity', { quantity: '' }, 'quantity'],
    ])('%s', (_name, over, field) => {
      const r = v(over);
      expect(r.body).toBeNull();
      expect(r.errors[field]).toBeTruthy();
    });
  });

  describe('engine rejections are warned about but can be sent (Appendix A)', () => {
    it.each([
      ['UNKNOWN_PAIR', { pair: 'EUR/XXX' }, 'pair', {}],
      ['BAD_QUANTITY (0)', { quantity: '0' }, 'quantity', {}],
      ['BAD_QUANTITY (above 10^9)', { quantity: '1000000001' }, 'quantity', {}],
      ['BAD_PRICE (not a tick multiple)', { price: '1.085051' }, 'price', {}],
      ['BAD_PRICE (JPY tick is 0.001)', { pair: 'USD/JPY', price: '156.8705' }, 'price', {}],
      ['BAD_PRICE (zero)', { price: '0.00000' }, 'price', {}],
      ['BAD_PRICE (above 10^9 ticks)', { price: '10000.00001' }, 'price', {}],
      ['NO_LIQUIDITY', { type: 'MARKET', price: '' }, 'type', { bestOppositeTicks: null }],
      ['MARGIN (no account)', {}, 'traderId', { account: null }],
      ['MARGIN (exposure)', { traderId: '12', quantity: '1000', price: '1.08' }, 'quantity', { account: { cash: 2_000_000, marginLimit: 2_000_000 } }],
    ])('%s', (name, over, field, ctx) => {
      const r = v(over, ctx);
      expect(r.errors).toEqual({});
      expect(r.body).not.toBeNull();
      expect(r.warnings[field]).toContain(name.split(' ')[0]);
    });
  });

  it('applies the margin rule per side', () => {
    const acct = { cash: 100, marginLimit: 1_000_000 };
    // BUY: qty x tick <= min(cash, margin) = 100; SELL: <= margin
    expect(v({ side: 'BUY', price: '0.00010', quantity: '10' }, { account: acct }).warnings.quantity).toBeUndefined();
    expect(v({ side: 'BUY', price: '0.00010', quantity: '11' }, { account: acct }).warnings.quantity).toContain('MARGIN');
    expect(v({ side: 'SELL', price: '0.00010', quantity: '11' }, { account: acct }).warnings.quantity).toBeUndefined();
    // a market order is checked at the best opposite price
    expect(v({ type: 'MARKET', price: '', quantity: '2' }, { account: acct, bestOppositeTicks: 60 }).warnings.quantity).toContain('MARGIN');
  });

  it('explains every reason in plain words', () => {
    for (const r of ['UNKNOWN_PAIR', 'BAD_QUANTITY', 'BAD_PRICE', 'NO_LIQUIDITY', 'MARGIN']) expect(REASON_TEXT[r]).toBeTruthy();
  });
});
