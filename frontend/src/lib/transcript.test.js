// Replays the recorded /api/stream transcript of contract §7 through the reducers.
import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { applyEvents, initialState } from './reducers.js';
import { parseSseTranscript } from './stream.js';
import { formatPrice } from './format.js';
import { cumulativeConsistent } from './depth.js';

const contract = readFileSync(new URL('../../../documentation/09_api_contract.md', import.meta.url), 'utf8');
const section = contract.slice(contract.indexOf('### 7 · Event stream'));
const transcript = section.slice(section.indexOf('```text') + 7, section.indexOf('```', section.indexOf('```text') + 7));
const frames = parseSseTranscript(transcript);
const events = frames.map((f) => ({ type: f.event, id: f.id, data: JSON.parse(f.data) }));

describe('the recorded event stream (contract §7)', () => {
  it('parses: ids strictly increase, every event is one of the four types', () => {
    expect(frames.length).toBeGreaterThan(10);
    const ids = frames.map((f) => Number(f.id));
    for (let i = 1; i < ids.length; i++) expect(ids[i]).toBeGreaterThan(ids[i - 1]);
    for (const f of frames) expect(['BOOK_UPDATE', 'TRADE', 'LOOPHOLE_ALERT', 'RATE_TICK']).toContain(f.event);
  });

  it('replays into the state the last events describe', () => {
    const s = applyEvents(initialState('EUR/USD'), events);
    const of = (type) => events.filter((e) => e.type === type).map((e) => e.data);

    const books = of('BOOK_UPDATE').filter((b) => b.pair === 'EUR/USD');
    expect(s.book).toEqual(books[books.length - 1]);
    for (const b of books) {
      expect(cumulativeConsistent(b.bids)).toBe(true);
      expect(cumulativeConsistent(b.asks)).toBe(true);
    }

    const trades = of('TRADE').filter((t) => t.pair === 'EUR/USD');
    expect(s.trades.map((t) => t.tradeId)).toEqual(trades.map((t) => t.tradeId).sort((a, b) => b - a));

    const maxSeq = Math.max(...events.filter((e) => e.data.seq !== undefined).map((e) => e.data.seq));
    expect(s.seq).toBe(maxSeq);

    for (const tick of of('RATE_TICK')) {
      const last = of('RATE_TICK').filter((t) => t.pair === tick.pair).pop();
      expect(s.rates[tick.pair].rate).toBe(last.rate);
    }

    const alerts = of('LOOPHOLE_ALERT');
    expect(s.alerts.map((a) => a.active)).toEqual(alerts.map((a) => a.active).reverse());
    const { active, ...lastScan } = alerts[alerts.length - 1];
    expect(active).toBe(false);
    expect(s.scan).toEqual(lastScan);
  });

  it('shows the recorded prices with the tick decimals', () => {
    const first = events.find((e) => e.type === 'TRADE').data;
    expect(first.price).toBe(1.0851); // JSON drops the trailing zero...
    expect(formatPrice(first.price, 0.00001)).toBe('1.08510'); // ...the display restores it
  });

  it('found the loophole, then cleared it', () => {
    const alerts = events.filter((e) => e.type === 'LOOPHOLE_ALERT').map((e) => e.data);
    expect(alerts[0].active).toBe(true);
    expect(alerts[0].cycle[0]).toBe(alerts[0].cycle[alerts[0].cycle.length - 1]);
    expect(alerts[0].profitPercent).toBeGreaterThan(alerts[0].minProfitPercent);
    expect(alerts.some((a) => a.active === false)).toBe(true);
  });
});
