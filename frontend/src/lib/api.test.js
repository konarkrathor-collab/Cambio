import { describe, expect, it } from 'vitest';
import { ApiError, createApi, isNetworkError, request } from './api.js';

function fakeFetch(status, body, { statusText = '', throws } = {}) {
  const calls = [];
  const impl = async (url, init) => {
    calls.push({ url, init });
    if (throws) throw throws;
    return {
      ok: status >= 200 && status < 300,
      status,
      statusText,
      text: async () => (typeof body === 'string' ? body : JSON.stringify(body)),
    };
  };
  impl.calls = calls;
  return impl;
}

async function caught(promise) {
  try {
    await promise;
  } catch (e) {
    return e;
  }
  throw new Error('expected a rejection');
}

describe('api wrapper', () => {
  it('returns parsed JSON on success and sends JSON bodies', async () => {
    const f = fakeFetch(201, { traderId: 901 });
    const out = await request('POST', '/api/accounts', { traderId: 901 }, { fetchImpl: f });
    expect(out).toEqual({ traderId: 901 });
    expect(f.calls[0].init.method).toBe('POST');
    expect(f.calls[0].init.headers['Content-Type']).toBe('application/json');
    expect(JSON.parse(f.calls[0].init.body)).toEqual({ traderId: 901 });
  });

  it.each([
    [400, 'BAD_REQUEST', 'unknown field "qty"'],
    [400, 'BAD_JSON', 'expected a value at 13'],
    [404, 'ORDER_NOT_FOUND', 'ORD-1 is not resting'],
    [404, 'UNKNOWN_PAIR', 'unknown pair XXX/YYY'],
    [405, 'METHOD_NOT_ALLOWED', 'use POST'],
    [409, 'ACCOUNT_EXISTS', 'account 901 already exists'],
    [409, 'SIMULATION_RUNNING', 'a simulation is already running'],
    [404, 'NO_SIMULATION', 'no simulation has finished'],
    [413, 'BODY_TOO_LARGE', 'too large'],
    [500, 'INTERNAL', 'unexpected'],
    [503, 'TOO_MANY_STREAMS', 'too many'],
  ])('maps a %i %s error body (contract §2) to ApiError', async (status, code, message) => {
    const e = await caught(request('GET', '/x', undefined, { fetchImpl: fakeFetch(status, { error: { code, message } }) }));
    expect(e).toBeInstanceOf(ApiError);
    expect(e.status).toBe(status);
    expect(e.code).toBe(code);
    expect(e.message).toBe(message);
  });

  it('maps a failed fetch to NETWORK (status 0)', async () => {
    const e = await caught(request('GET', '/x', undefined, { fetchImpl: fakeFetch(0, '', { throws: new TypeError('Failed to fetch') }) }));
    expect(e.code).toBe('NETWORK');
    expect(e.status).toBe(0);
    expect(isNetworkError(e)).toBe(true);
  });

  it('maps a proxy error page while the backend is down to NETWORK', async () => {
    const e = await caught(request('GET', '/x', undefined, { fetchImpl: fakeFetch(502, '<html>Bad Gateway</html>') }));
    expect(e.code).toBe('NETWORK');
    expect(e.status).toBe(502);
  });

  it('maps other non-JSON errors to HTTP_<status> and a non-JSON success to BAD_RESPONSE', async () => {
    const e1 = await caught(request('GET', '/x', undefined, { fetchImpl: fakeFetch(418, 'teapot', { statusText: "I'm a teapot" }) }));
    expect(e1.code).toBe('HTTP_418');
    expect(e1.message).toBe("I'm a teapot");
    const e2 = await caught(request('GET', '/x', undefined, { fetchImpl: fakeFetch(200, 'not json') }));
    expect(e2.code).toBe('BAD_RESPONSE');
    const e3 = await caught(request('GET', '/x', undefined, { fetchImpl: fakeFetch(200, '') }));
    expect(e3.code).toBe('BAD_RESPONSE');
  });

  it('lets an abort through unchanged', async () => {
    const abort = Object.assign(new Error('aborted'), { name: 'AbortError' });
    const e = await caught(request('GET', '/x', undefined, { fetchImpl: fakeFetch(0, '', { throws: abort }) }));
    expect(e).toBe(abort);
  });

  it('builds the contract\'s paths', async () => {
    const f = fakeFetch(200, {});
    const api = createApi({ fetchImpl: f });
    await api.orderbook('EUR/USD', 20);
    await api.inspector('usd/jpy');
    await api.trades('EUR/USD', 100);
    await api.trades(null, 50);
    await api.cancelOrder('ORD-7');
    await api.tickers('eur/');
    expect(f.calls.map((c) => c.url)).toEqual([
      '/api/orderbook/EUR-USD?depth=20',
      '/api/inspector/USD-JPY',
      '/api/trades?pair=EUR-USD&limit=100',
      '/api/trades?limit=50',
      '/api/orders/ORD-7',
      '/api/tickers?prefix=eur%2F',
    ]);
    expect(f.calls[4].init.method).toBe('DELETE');
  });
});
