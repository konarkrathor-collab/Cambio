// A thin fetch wrapper: parsed JSON on success, ApiError{status, code, message} otherwise.
// Error codes mirror contract §2; two are client-side: NETWORK (no response at all, status 0)
// and BAD_RESPONSE (a response that is not the JSON the contract promises).

import { pairPath } from './format.js';

export class ApiError extends Error {
  constructor(status, code, message) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
  }
}

/** True for an ApiError that means the backend could not be reached. */
export function isNetworkError(e) {
  return e instanceof ApiError && e.code === 'NETWORK';
}

/**
 * One request. {@code fetchImpl} is injectable for tests. A JSON body is sent with
 * Content-Type application/json; an error response's {error: {code, message}} becomes the
 * ApiError; anything else maps to HTTP_<status> or BAD_RESPONSE.
 */
export async function request(method, path, body, { fetchImpl = globalThis.fetch, signal } = {}) {
  const init = { method, headers: { Accept: 'application/json' }, signal };
  if (body !== undefined) {
    init.headers['Content-Type'] = 'application/json';
    init.body = typeof body === 'string' ? body : JSON.stringify(body);
  }
  let res;
  try {
    res = await fetchImpl(path, init);
  } catch (e) {
    if (e && e.name === 'AbortError') throw e;
    throw new ApiError(0, 'NETWORK', 'The backend is not reachable.');
  }
  let text;
  try {
    text = await res.text();
  } catch {
    throw new ApiError(res.status, 'NETWORK', 'The connection closed before the response arrived.');
  }
  let parsed;
  let parseFailed = false;
  if (text.length > 0) {
    try {
      parsed = JSON.parse(text);
    } catch {
      parseFailed = true;
    }
  }
  if (!res.ok) {
    const err = parsed && typeof parsed === 'object' ? parsed.error : null;
    if (err && typeof err.code === 'string') {
      throw new ApiError(res.status, err.code, typeof err.message === 'string' ? err.message : err.code);
    }
    // e.g. the dev proxy's 502/504 while the backend is down
    if (res.status === 502 || res.status === 503 || res.status === 504) {
      throw new ApiError(res.status, 'NETWORK', 'The backend is not reachable.');
    }
    throw new ApiError(res.status, `HTTP_${res.status}`, res.statusText || `HTTP ${res.status}`);
  }
  if (parseFailed || parsed === undefined) {
    throw new ApiError(res.status, 'BAD_RESPONSE', 'The backend sent a response that is not JSON.');
  }
  return parsed;
}

const q = encodeURIComponent;

/** The endpoints of contract §3, one function each. */
export function createApi(options = {}) {
  const call = (method, path, body) => request(method, path, body, options);
  return {
    health: () => call('GET', '/api/health'),
    tickers: (prefix = '') => call('GET', `/api/tickers?prefix=${q(prefix)}`),
    orderbook: (pair, depth = 10) => call('GET', `/api/orderbook/${pairPath(pair)}?depth=${depth}`),
    inspector: (pair) => call('GET', `/api/inspector/${pairPath(pair)}`),
    trades: (pair, limit = 100) =>
      call('GET', `/api/trades?${pair ? `pair=${q(pairPath(pair))}&` : ''}limit=${limit}`),
    placeOrder: (order) => call('POST', '/api/orders', order),
    cancelOrder: (orderId) => call('DELETE', `/api/orders/${q(orderId)}`),
    accounts: () => call('GET', '/api/accounts'),
    scan: () => call('GET', '/api/arbitrage/scan'),
    graph: () => call('GET', '/api/arbitrage/graph'),
    status: () => call('GET', '/api/market/rates/status'),
    postRates: (payload) => call('POST', '/api/market/rates', payload),
    simulate: (params) => call('POST', '/api/simulate', params),
    simulateLast: () => call('GET', '/api/simulate/last'),
  };
}

export const api = createApi();
