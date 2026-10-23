import { describe, expect, it } from 'vitest';
import { createStore } from './store.js';
import { initialState, setScan } from './reducers.js';
import { RECONNECT_MS, createStream, parseSseTranscript } from './stream.js';

const tradeEvent = (id) => ({
  type: 'TRADE',
  data: { tradeId: id, pair: 'EUR/USD', price: 1.085, quantity: 1, buyOrderId: 'ORD-1', sellOrderId: 'ORD-2', buyerId: 1, sellerId: 2, takerSide: 'BUY', seq: id },
});

describe('store: one render per frame', () => {
  it('applies a burst of 5,000 events in one batch and notifies once', () => {
    const frames = [];
    const store = createStore(initialState(), { schedule: (fn) => frames.push(fn) });
    let renders = 0;
    store.subscribe(() => renders++);
    for (let i = 1; i <= 5000; i++) store.dispatchEvent(tradeEvent(i));
    expect(frames).toHaveLength(1);
    expect(renders).toBe(0);
    frames[0]();
    expect(renders).toBe(1);
    expect(store.getState().trades[0].tradeId).toBe(5000);
    expect(store.getState().seq).toBe(5000);
  });
  it('update() applies queued events first, then the reducer, and notifies', () => {
    const frames = [];
    const store = createStore(initialState(), { schedule: (fn) => frames.push(fn) });
    let renders = 0;
    store.subscribe(() => renders++);
    store.dispatchEvent(tradeEvent(1));
    store.update(setScan, { hasArbitrage: false });
    expect(store.getState().trades).toHaveLength(1);
    expect(store.getState().scan).toEqual({ hasArbitrage: false });
    expect(renders).toBe(1);
    frames[0](); // the scheduled flush finds nothing left
    expect(renders).toBe(1);
  });
  it('unsubscribes', () => {
    const store = createStore(initialState(), { schedule: () => {} });
    let n = 0;
    const off = store.subscribe(() => n++);
    off();
    store.update(setScan, {});
    expect(n).toBe(0);
  });
});

class FakeEventSource {
  static instances = [];
  constructor(url) {
    this.url = url;
    this.readyState = 0;
    this.listeners = {};
    this.closed = false;
    FakeEventSource.instances.push(this);
  }
  addEventListener(type, fn) {
    (this.listeners[type] ||= []).push(fn);
  }
  close() {
    this.closed = true;
    this.readyState = 2;
  }
  open() {
    this.readyState = 1;
    this.onopen();
  }
  emit(type, data, id) {
    for (const fn of this.listeners[type] || []) fn({ data, lastEventId: id });
  }
  fail(readyState) {
    this.readyState = readyState;
    this.onerror();
  }
}

describe('stream: one shared EventSource', () => {
  function setup() {
    FakeEventSource.instances = [];
    const log = { states: [], events: [], opens: 0, errors: 0, timers: [] };
    const stream = createStream({
      EventSourceImpl: FakeEventSource,
      onState: (s) => log.states.push(s),
      onEvent: (e) => log.events.push(e),
      onOpen: () => log.opens++,
      onError: () => log.errors++,
      setTimer: (fn, ms) => {
        log.timers.push({ fn, ms });
        return log.timers.length;
      },
      clearTimer: () => {},
    });
    return { stream, log };
  }

  it('reports connecting then open, and refetches on every open', () => {
    const { log } = setup();
    const es = FakeEventSource.instances[0];
    expect(es.url).toBe('/api/stream');
    expect(log.states).toEqual(['connecting']);
    es.open();
    expect(log.states).toEqual(['connecting', 'open']);
    expect(log.opens).toBe(1);
    es.fail(0); // the browser's own retry
    expect(log.states.at(-1)).toBe('connecting');
    es.open();
    expect(log.opens).toBe(2);
  });

  it('parses the four named events and reports unparseable data', () => {
    const { log } = setup();
    const es = FakeEventSource.instances[0];
    es.open();
    es.emit('TRADE', '{"tradeId":1}', '7');
    es.emit('RATE_TICK', 'not json', '8');
    expect(log.events).toEqual([{ type: 'TRADE', id: '7', data: { tradeId: 1 } }]);
    expect(log.errors).toBe(1);
    expect(Object.keys(es.listeners).sort()).toEqual(['BOOK_UPDATE', 'LOOPHOLE_ALERT', 'RATE_TICK', 'TRADE']);
  });

  it('replaces a CLOSED source after a delay (the browser would not retry it)', () => {
    const { log } = setup();
    const es = FakeEventSource.instances[0];
    es.fail(2);
    expect(es.closed).toBe(true);
    expect(log.states.at(-1)).toBe('closed');
    expect(log.timers).toHaveLength(1);
    expect(log.timers[0].ms).toBe(RECONNECT_MS);
    log.timers[0].fn();
    expect(FakeEventSource.instances).toHaveLength(2);
    expect(log.states.at(-1)).toBe('connecting');
    FakeEventSource.instances[1].open();
    expect(log.opens).toBe(1);
  });

  it('close() stops for good', () => {
    const { stream, log } = setup();
    stream.close();
    expect(FakeEventSource.instances[0].closed).toBe(true);
    expect(log.states.at(-1)).toBe('closed');
    FakeEventSource.instances[0].onerror();
    expect(log.timers).toHaveLength(0);
  });
});

describe('SSE transcript parser', () => {
  it('reads id/event/data, skips comments and retry', () => {
    const text = 'retry: 3000\n: connected\n\nid: 1\nevent: TRADE\ndata: {"a":1}\n\n: heartbeat\n\nid: 2\nevent: RATE_TICK\ndata: {"b":2}\n';
    expect(parseSseTranscript(text)).toEqual([
      { id: '1', event: 'TRADE', data: '{"a":1}' },
      { id: '2', event: 'RATE_TICK', data: '{"b":2}' },
    ]);
  });
});
