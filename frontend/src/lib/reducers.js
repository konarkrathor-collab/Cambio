// Pure reducers: (state, input) -> new state. Every live update goes through here, from the
// event stream (contract §4) or from a snapshot fetched on (re)connect.

export const MAX_TRADES = 100;
export const MAX_ALERTS = 20;
/** Recent fills kept so an order placed just before its own TRADE events is accounted. */
export const MAX_RECENT_FILLS = 500;

export function initialState(pair = 'EUR/USD') {
  return {
    pair,
    book: null, // the selected pair's snapshot (contract §3.3 shape)
    trades: [], // the selected pair's trades, newest first, at most MAX_TRADES
    seq: null, // the highest engine seq seen
    scan: null, // the latest scan (contract §3.5 shape)
    alerts: [], // LOOPHOLE_ALERT events, newest first, at most MAX_ALERTS
    rates: {}, // pair -> {rate, ts, source}, from RATE_TICK
    myOrders: [], // orders this session placed that still rest
    recentFills: [], // the last MAX_RECENT_FILLS trades of any pair, oldest first
    bookVersion: 0, // bumps on every BOOK_UPDATE of the selected pair
  };
}

function maxSeq(a, b) {
  if (a === null || a === undefined) return b ?? null;
  if (b === null || b === undefined) return a;
  return Math.max(a, b);
}

// ---------------------------------------------------------------- the four events

export function applyBookUpdate(state, book) {
  const seq = maxSeq(state.seq, book.seq);
  if (book.pair !== state.pair) return seq === state.seq ? state : { ...state, seq };
  if (state.book && state.book.seq > book.seq) return { ...state, seq }; // out of date
  return { ...state, book, seq, bookVersion: state.bookVersion + 1 };
}

export function applyTrade(state, trade) {
  const seq = maxSeq(state.seq, trade.seq);
  let recentFills = state.recentFills.concat([trade]);
  if (recentFills.length > MAX_RECENT_FILLS) recentFills = recentFills.slice(-MAX_RECENT_FILLS);
  let trades = state.trades;
  if (trade.pair === state.pair && !trades.some((t) => t.tradeId === trade.tradeId)) {
    trades = mergeTrades(trades, [trade]);
  }
  return { ...state, seq, recentFills, trades, myOrders: fillMyOrders(state.myOrders, [trade]) };
}

export function applyLoopholeAlert(state, alert) {
  const { active, ...scan } = alert;
  const entry = {
    active: Boolean(active),
    cycle: scan.cycle || [],
    profitPercent: scan.profitPercent,
    multiplier: scan.multiplier,
    at: scan.scannedAt,
    scanCount: scan.scanCount,
  };
  return { ...state, scan, alerts: [entry, ...state.alerts].slice(0, MAX_ALERTS) };
}

export function applyRateTick(state, tick) {
  return { ...state, rates: { ...state.rates, [tick.pair]: { rate: tick.rate, ts: tick.ts, source: tick.source } } };
}

const HANDLERS = {
  BOOK_UPDATE: applyBookUpdate,
  TRADE: applyTrade,
  LOOPHOLE_ALERT: applyLoopholeAlert,
  RATE_TICK: applyRateTick,
};

export const EVENT_TYPES = Object.keys(HANDLERS);

/** One stream event {type, data}; unknown types leave the state unchanged. */
export function applyEvent(state, event) {
  const h = HANDLERS[event.type];
  return h ? h(state, event.data) : state;
}

/** A batch of events in arrival order (one render per batch). */
export function applyEvents(state, events) {
  let s = state;
  for (const e of events) s = applyEvent(s, e);
  return s;
}

// ---------------------------------------------------------------- snapshots

export function selectPair(state, pair) {
  if (pair === state.pair) return state;
  return { ...state, pair, book: null, trades: [], bookVersion: state.bookVersion + 1 };
}

export function setBook(state, book) {
  return applyBookUpdate(state, book);
}

/** GET /api/trades for the selected pair, merged with any TRADE events already applied. */
export function setTrades(state, response) {
  if (response.pair !== null && response.pair !== state.pair) return state;
  const seq = response.trades.reduce((s, t) => maxSeq(s, t.seq), state.seq);
  return { ...state, seq, trades: mergeTrades(state.trades, response.trades) };
}

export function setScan(state, scan) {
  return { ...state, scan };
}

export function setSeq(state, seq) {
  return { ...state, seq: maxSeq(state.seq, seq) };
}

/** Newest first by tradeId, no duplicates, at most MAX_TRADES. */
export function mergeTrades(a, b) {
  const byId = new Map();
  for (const t of a) byId.set(t.tradeId, t);
  for (const t of b) byId.set(t.tradeId, t);
  return [...byId.values()].sort((x, y) => y.tradeId - x.tradeId).slice(0, MAX_TRADES);
}

// ---------------------------------------------------------------- orders this session placed

/**
 * A POST /api/orders response. An order with restingQuantity > 0 is tracked; its remaining
 * quantity is exact as of the response's seq, and any fill of it with a later seq that
 * already arrived on the stream is applied now.
 */
export function orderPlaced(state, response) {
  const seq = maxSeq(state.seq, response.seq);
  if (!(response.restingQuantity > 0)) return { ...state, seq };
  if (state.myOrders.some((o) => o.orderId === response.orderId)) return { ...state, seq };
  const order = {
    orderId: response.orderId,
    pair: response.pair,
    side: response.side,
    price: response.price,
    quantity: response.quantity,
    remaining: response.restingQuantity,
    asOfSeq: response.seq,
  };
  const myOrders = fillMyOrders([order, ...state.myOrders], state.recentFills);
  return { ...state, seq, myOrders };
}

/** A successful cancel, or a 404 for it: either way the order no longer rests. */
export function orderGone(state, orderId) {
  return { ...state, myOrders: state.myOrders.filter((o) => o.orderId !== orderId) };
}

/** Applies fills to tracked orders: only fills after the order's snapshot seq count. */
export function fillMyOrders(orders, trades) {
  if (orders.length === 0 || trades.length === 0) return orders;
  let changed = false;
  const next = [];
  for (const o of orders) {
    let remaining = o.remaining;
    let asOfSeq = o.asOfSeq;
    for (const t of trades) {
      if (t.seq <= o.asOfSeq) continue;
      if (t.buyOrderId === o.orderId || t.sellOrderId === o.orderId) {
        remaining -= t.quantity;
        asOfSeq = Math.max(asOfSeq, t.seq);
      }
    }
    if (remaining !== o.remaining) changed = true;
    if (remaining > 0) next.push(remaining === o.remaining ? o : { ...o, remaining, asOfSeq });
  }
  return changed ? next : orders;
}
