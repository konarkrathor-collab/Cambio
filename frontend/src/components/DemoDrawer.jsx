import { useId, useState } from 'react';
import { api } from '../lib/api.js';
import { describeError, placeOrder, refreshScan, refreshStatus, store, useStore } from '../app/runtime.js';
import { loopholePayload, seedFlow, seedMidTicks } from '../lib/demo.js';
import { fromTicks } from '../lib/decimal.js';
import { tickDecimals } from '../lib/format.js';

/** DEMO tools, clearly labelled: seed order flow and inject a (replay) loophole. */
export default function DemoDrawer() {
  const pair = useStore((s) => s.pair);
  const [n, setN] = useState('20');
  const [seed, setSeed] = useState('1');
  const [busy, setBusy] = useState(false);
  const [log, setLog] = useState([]);
  const id = useId();

  const add = (text, tone = 'info') => setLog((l) => [{ at: new Date().toISOString().slice(11, 19), text, tone }, ...l].slice(0, 30));

  const seedFlowAction = async () => {
    const count = Math.min(Math.max(parseInt(n, 10) || 0, 1), 500);
    const s = parseInt(seed, 10) || 1;
    const book = store.getState().book;
    const d = tickDecimals(book?.tickSize ?? (pair.endsWith('/JPY') ? 0.001 : 0.00001));
    const mid = seedMidTicks(pair, book && book.pair === pair ? book : null);
    const orders = seedFlow({ pair, midTicks: mid, decimals: d, n: count, seed: s });
    setBusy(true);
    let trades = 0;
    let rejected = 0;
    let resting = 0;
    try {
      for (const o of orders) {
        const r = await placeOrder(o);
        trades += r.trades.length;
        if (r.status === 'REJECTED') rejected++;
        if (r.restingQuantity > 0) resting++;
      }
      add(`Seed flow: ${count} limit orders on ${pair} around ${fromTicks(mid, d)} (seed ${s}, traders 1–11): ${trades} trades, ${resting} left resting, ${rejected} rejected.`);
      setSeed(String(s + 1));
    } catch (e) {
      add(`Seed flow stopped: ${describeError(e)}`, 'error');
    } finally {
      setBusy(false);
    }
  };

  const inject = async () => {
    setBusy(true);
    try {
      const r = await api.postRates(loopholePayload(Date.now()));
      add(`Injected a +0.4% EUR/USD 1.08, GBP/USD 1.30, EUR/GBP 0.83409 tick set as REPLAY data (accepted ${r.accepted}, rejected ${r.rejected}). Live quotes expire after 30 s, so the loophole clears by itself.`);
      refreshStatus();
      refreshScan();
    } catch (e) {
      add(`Inject failed: ${describeError(e)}`, 'error');
    } finally {
      setBusy(false);
    }
  };

  return (
    <details className="demo">
      <summary><span className="demo-tag">DEMO</span> tools: seed order flow, inject a loophole</summary>
      <div className="demo-body">
        <div className="demo-actions">
          <div className="field">
            <label htmlFor={`${id}-n`} className="field-label">Orders</label>
            <input id={`${id}-n`} className="input num" value={n} onChange={(e) => setN(e.target.value)} inputMode="numeric" />
          </div>
          <div className="field">
            <label htmlFor={`${id}-seed`} className="field-label">Seed</label>
            <input id={`${id}-seed`} className="input num" value={seed} onChange={(e) => setSeed(e.target.value)} inputMode="numeric" />
          </div>
          <button type="button" className="btn" onClick={seedFlowAction} disabled={busy}>Seed flow on {pair}</button>
          <button type="button" className="btn btn-warn" onClick={inject} disabled={busy}>Inject loophole (replay)</button>
        </div>
        <ol className="demo-log" aria-live="polite" aria-label="Demo log">
          {log.length === 0 ? <li className="muted">Nothing done yet. Every action is logged here.</li> : null}
          {log.map((l, i) => (
            <li key={`${l.at}-${i}`} className={l.tone === 'error' ? 'tone-down' : undefined}>
              <span className="num muted">{l.at}</span> {l.text}
            </li>
          ))}
        </ol>
      </div>
    </details>
  );
}
