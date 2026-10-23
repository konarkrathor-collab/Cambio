import { describe, expect, it } from 'vitest';
import {
  MAX_ALERTS, MAX_TRADES, applyBookUpdate, applyEvent, applyEvents, applyLoopholeAlert, applyRateTick,
  applyTrade, fillMyOrders, initialState, mergeTrades, orderGone, orderPlaced, selectPair, setBook,
  setScan, setSeq, setTrades,
} from './reducers.js';

const book = (pair, seq, bids = [], asks = []) => ({ pair, tickSize: 0.00001, seq, bids, asks, lastTradePrice: null });
const trade = (tradeId, seq, extra = {}) => ({
  tradeId, pair: 'EUR/USD', price: 1.0851, quantity: 10, buyOrderId: 'ORD-90', sellOrderId: 'ORD-91',
  buyerId: 1, sellerId: 2, takerSide: 'BUY', seq, ...extra,
});

describe('BOOK_UPDATE', () => {
  it('replaces the selected pair\'s book and tracks seq', () => {
    const s = applyBookUpdate(initialState(), book('EUR/USD', 3, [{ price: 1.085, volume: 5, orders: 1, cumulative: 5 }]));
    expect(s.book.seq).toBe(3);
    expect(s.seq).toBe(3);
    expect(s.bookVersion).toBe(1);
  });
  it('ignores other pairs\' books but still advances seq', () => {
    const s0 = initialState();
    const s = applyBookUpdate(s0, book('USD/JPY', 9));
    expect(s.book).toBeNull();
    expect(s.seq).toBe(9);
  });
  it('never goes back to an older snapshot', () => {
    let s = applyBookUpdate(initialState(), book('EUR/USD', 8));
    s = applyBookUpdate(s, book('EUR/USD', 5));
    expect(s.book.seq).toBe(8);
    expect(setBook(s, book('EUR/USD', 12)).book.seq).toBe(12);
  });
});

describe('TRADE', () => {
  it('prepends trades of the selected pair, newest first, without duplicates', () => {
    let s = applyTrade(initialState(), trade(1, 3));
    s = applyTrade(s, trade(2, 4));
    s = applyTrade(s, trade(2, 4));
    s = applyTrade(s, trade(3, 5, { pair: 'GBP/USD' }));
    expect(s.trades.map((t) => t.tradeId)).toEqual([2, 1]);
    expect(s.seq).toBe(5);
    expect(s.recentFills).toHaveLength(4);
  });
  it('keeps at most MAX_TRADES', () => {
    let s = initialState();
    for (let i = 1; i <= MAX_TRADES + 30; i++) s = applyTrade(s, trade(i, i));
    expect(s.trades).toHaveLength(MAX_TRADES);
    expect(s.trades[0].tradeId).toBe(MAX_TRADES + 30);
  });
  it('merges history and live trades by tradeId', () => {
    expect(mergeTrades([trade(5, 5), trade(3, 3)], [trade(4, 4), trade(5, 5)]).map((t) => t.tradeId)).toEqual([5, 4, 3]);
    let s = applyTrade(initialState(), trade(7, 7));
    s = setTrades(s, { pair: 'EUR/USD', limit: 100, count: 2, trades: [trade(6, 6), trade(5, 5)] });
    expect(s.trades.map((t) => t.tradeId)).toEqual([7, 6, 5]);
    expect(setTrades(s, { pair: 'GBP/USD', trades: [trade(9, 9)] })).toBe(s);
  });
});

describe('LOOPHOLE_ALERT and RATE_TICK', () => {
  const alert = (active, n) => ({
    hasArbitrage: active, cycle: active ? ['USD', 'EUR', 'GBP', 'USD'] : [], multiplier: active ? 1.004 : 1,
    profitPercent: active ? 0.4 : 0, minProfitPercent: 0.02, scanCount: n, scannedAt: `2026-10-23T21:00:0${n % 10}.000Z`, active,
  });
  it('records found and cleared alerts, newest first, and keeps the scan without `active`', () => {
    let s = applyLoopholeAlert(initialState(), alert(true, 1));
    s = applyLoopholeAlert(s, alert(false, 2));
    expect(s.alerts.map((a) => a.active)).toEqual([false, true]);
    expect(s.alerts[1].cycle).toEqual(['USD', 'EUR', 'GBP', 'USD']);
    expect(s.scan.hasArbitrage).toBe(false);
    expect('active' in s.scan).toBe(false);
  });
  it('keeps the last MAX_ALERTS alerts', () => {
    let s = initialState();
    for (let i = 0; i < MAX_ALERTS + 5; i++) s = applyLoopholeAlert(s, alert(i % 2 === 0, i));
    expect(s.alerts).toHaveLength(MAX_ALERTS);
    expect(s.alerts[0].scanCount).toBe(MAX_ALERTS + 4);
  });
  it('stores the latest rate per pair', () => {
    let s = applyRateTick(initialState(), { pair: 'EUR/USD', rate: 1.08, ts: 1, source: 'finnhub-ws' });
    s = applyRateTick(s, { pair: 'EUR/USD', rate: 1.081, ts: 2, source: 'finnhub-ws' });
    expect(s.rates['EUR/USD']).toEqual({ rate: 1.081, ts: 2, source: 'finnhub-ws' });
  });
});

describe('dispatch, snapshots and pair selection', () => {
  it('routes events by type and ignores unknown ones', () => {
    const s0 = initialState();
    expect(applyEvent(s0, { type: 'HEARTBEAT', data: {} })).toBe(s0);
    const s = applyEvents(s0, [
      { type: 'BOOK_UPDATE', data: book('EUR/USD', 1) },
      { type: 'TRADE', data: trade(1, 2) },
      { type: 'RATE_TICK', data: { pair: 'GBP/USD', rate: 1.3, ts: 1, source: 'x' } },
    ]);
    expect(s.book.seq).toBe(1);
    expect(s.trades).toHaveLength(1);
    expect(s.rates['GBP/USD'].rate).toBe(1.3);
  });
  it('clears the pair-specific views when the pair changes', () => {
    let s = applyTrade(applyBookUpdate(initialState(), book('EUR/USD', 1)), trade(1, 2));
    expect(selectPair(s, 'EUR/USD')).toBe(s);
    s = selectPair(s, 'USD/JPY');
    expect(s.pair).toBe('USD/JPY');
    expect(s.book).toBeNull();
    expect(s.trades).toEqual([]);
  });
  it('sets the scan and seq', () => {
    expect(setScan(initialState(), { hasArbitrage: false }).scan.hasArbitrage).toBe(false);
    expect(setSeq(setSeq(initialState(), 10), 4).seq).toBe(10);
  });
});

describe('orders this session placed', () => {
  const placed = (orderId, seq, resting) => ({
    orderId, status: resting > 0 ? 'QUEUED' : 'FILLED', pair: 'EUR/USD', side: 'SELL', type: 'LIMIT',
    price: 1.0851, quantity: 100, filledQuantity: 100 - resting, restingQuantity: resting, trades: [], seq,
  });
  it('tracks a resting order and drops a filled or rejected one', () => {
    let s = orderPlaced(initialState(), placed('ORD-1', 1, 100));
    s = orderPlaced(s, placed('ORD-2', 2, 0));
    expect(s.myOrders.map((o) => o.orderId)).toEqual(['ORD-1']);
    expect(s.seq).toBe(2);
  });
  it('applies later fills to the remaining quantity and removes a fully filled order', () => {
    let s = orderPlaced(initialState(), placed('ORD-1', 1, 100));
    s = applyTrade(s, trade(1, 3, { sellOrderId: 'ORD-1', quantity: 60 }));
    expect(s.myOrders[0].remaining).toBe(40);
    s = applyTrade(s, trade(2, 4, { sellOrderId: 'ORD-1', quantity: 40 }));
    expect(s.myOrders).toEqual([]);
  });
  it('does not count the order\'s own fills twice (same seq as the response)', () => {
    // a buy that filled 30 and rests 70: its TRADE events carry the response's seq
    let s = applyTrade(initialState(), trade(1, 5, { buyOrderId: 'ORD-5', quantity: 30 }));
    s = orderPlaced(s, { ...placed('ORD-5', 5, 70), side: 'BUY' });
    expect(s.myOrders[0].remaining).toBe(70);
    s = applyTrade(s, trade(2, 5, { buyOrderId: 'ORD-5', quantity: 30 })); // late duplicate seq
    expect(s.myOrders[0].remaining).toBe(70);
  });
  it('applies a fill that reached the stream before the POST response', () => {
    let s = applyTrade(initialState(), trade(3, 9, { sellOrderId: 'ORD-8', quantity: 25 }));
    s = orderPlaced(s, placed('ORD-8', 8, 100));
    expect(s.myOrders[0].remaining).toBe(75);
  });
  it('forgets a cancelled order', () => {
    let s = orderPlaced(initialState(), placed('ORD-1', 1, 100));
    s = orderGone(s, 'ORD-1');
    expect(s.myOrders).toEqual([]);
  });
  it('fillMyOrders returns the same array when nothing changes', () => {
    const orders = [{ orderId: 'ORD-1', remaining: 5, asOfSeq: 1 }];
    expect(fillMyOrders(orders, [trade(1, 2)])).toBe(orders);
  });
});
