// The app's single runtime: one store, one API client, one EventSource. Started once from
// main.jsx (outside React, so StrictMode's double effects cannot open a second stream).

import { useSyncExternalStore } from 'react';
import { api, isNetworkError } from '../lib/api.js';
import { createStore } from '../lib/store.js';
import { createStream } from '../lib/stream.js';
import {
  initialState, orderGone, orderPlaced, selectPair as selectPairReducer, setBook, setScan, setSeq, setTrades,
} from '../lib/reducers.js';

export const BOOK_DEPTH = 10; // the depth of every BOOK_UPDATE (contract §4)
export const HEALTH_MS = 2000;
export const STATUS_MS = 2000;
export const GRAPH_MS = 5000;
const HEALTH_BACKOFF_MS = [1000, 2000, 4000, 8000, 10000];

const patch = (s, p) => ({ ...s, ...p });

export const store = createStore({
  ...initialState('EUR/USD'),
  streamState: 'connecting',
  health: { up: null, data: null, failures: 0, retryAt: null },
  status: null,
  graph: null,
  accounts: null,
  tickers: null, // [{pair, base, quote, tickSize}]
  errors: {}, // panel -> message of its last failed fetch
});

export function setPatch(p) {
  store.update(patch, p);
}

function setError(panel, error) {
  const errors = { ...store.getState().errors };
  if (error) errors[panel] = describeError(error);
  else delete errors[panel];
  setPatch({ errors });
}

export function describeError(e) {
  if (isNetworkError(e)) return 'The backend is not reachable.';
  if (e && e.code) return `${e.status} ${e.code}: ${e.message}`;
  return String(e && e.message ? e.message : e);
}

/** useStore(s => s.book): re-renders when the selected value changes. */
export function useStore(selector) {
  return useSyncExternalStore(store.subscribe, () => selector(store.getState()));
}

// ---------------------------------------------------------------- snapshots

async function load(panel, fetcher, apply) {
  try {
    const v = await fetcher();
    apply(v);
    setError(panel, null);
    return v;
  } catch (e) {
    setError(panel, e);
    return null;
  }
}

export function refreshBook() {
  const pair = store.getState().pair;
  return load('book', () => api.orderbook(pair, BOOK_DEPTH), (b) => {
    if (store.getState().pair === pair) store.update(setBook, b);
  });
}

export function refreshTrades() {
  const pair = store.getState().pair;
  return load('trades', () => api.trades(pair, 100), (t) => {
    if (store.getState().pair === pair) store.update(setTrades, t);
  });
}

export const refreshStatus = () => load('status', api.status, (status) => setPatch({ status }));
export const refreshScan = () => load('scan', api.scan, (scan) => store.update(setScan, scan));
export const refreshGraph = () => load('graph', api.graph, (graph) => setPatch({ graph }));
export const refreshAccounts = () => load('accounts', api.accounts, (a) => setPatch({ accounts: a.accounts }));
export const refreshTickers = () => load('tickers', () => api.tickers(''), (t) => setPatch({ tickers: t.tickers }));

/** Everything a (re)connect must refetch: missed events are not replayed (contract §4). */
export function refetchAll() {
  const s = store.getState();
  const jobs = [refreshBook(), refreshTrades(), refreshStatus(), refreshScan(), refreshGraph(), refreshAccounts()];
  if (!s.tickers) jobs.push(refreshTickers());
  return Promise.all(jobs);
}

export function selectPair(pair) {
  if (pair === store.getState().pair) return;
  store.update(selectPairReducer, pair);
  refreshBook();
  refreshTrades();
}

// ---------------------------------------------------------------- commands

/** POST /api/orders; resolves with the response, rejects with ApiError. */
export async function placeOrder(body) {
  const r = await api.placeOrder(body);
  store.update(orderPlaced, r);
  scheduleAccounts();
  return r;
}

/** DELETE /api/orders/{id}; a 404 also forgets the order (it no longer rests). */
export async function cancelOrder(orderId) {
  try {
    const r = await api.cancelOrder(orderId);
    store.update(orderGone, orderId);
    store.update(setSeq, r.seq);
    return r;
  } catch (e) {
    if (e && e.code === 'ORDER_NOT_FOUND') store.update(orderGone, orderId);
    throw e;
  }
}

// ---------------------------------------------------------------- polling and side effects

let accountsTimer = null;
function scheduleAccounts() {
  if (accountsTimer !== null) return;
  accountsTimer = setTimeout(() => {
    accountsTimer = null;
    refreshAccounts();
  }, 300);
}

let graphTimer = null;
function scheduleGraph() {
  if (graphTimer !== null) return;
  graphTimer = setTimeout(() => {
    graphTimer = null;
    refreshGraph();
  }, 500);
}

function onEvent(e) {
  store.dispatchEvent(e);
  if (e.type === 'TRADE') scheduleAccounts();
  else if (e.type === 'LOOPHOLE_ALERT' || e.type === 'RATE_TICK') scheduleGraph();
}

async function healthLoop() {
  let delay = HEALTH_MS;
  try {
    const data = await api.health();
    const wasDown = store.getState().health.up === false;
    setPatch({ health: { up: true, data, failures: 0, retryAt: null } });
    store.update(setSeq, data.seq);
    if (wasDown) refetchAll();
  } catch (e) {
    const failures = store.getState().health.failures + 1;
    delay = HEALTH_BACKOFF_MS[Math.min(failures - 1, HEALTH_BACKOFF_MS.length - 1)];
    setPatch({
      health: { up: false, data: null, failures, retryAt: Date.now() + delay, error: describeError(e) },
    });
  }
  setTimeout(healthLoop, delay);
}

async function statusLoop() {
  if (store.getState().health.up !== false) await Promise.all([refreshStatus(), refreshScan()]);
  setTimeout(statusLoop, STATUS_MS);
}

async function graphLoop() {
  if (store.getState().health.up !== false) await refreshGraph();
  setTimeout(graphLoop, GRAPH_MS);
}

let started = false;

export function start() {
  if (started) return;
  started = true;
  createStream({
    onEvent,
    onState: (streamState) => setPatch({ streamState }),
    onOpen: () => {
      refetchAll();
    },
  });
  healthLoop();
  statusLoop();
  graphLoop();
  refreshTickers();
}
