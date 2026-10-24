import { useEffect, useState } from 'react';
import { describeError, useStore } from '../app/runtime.js';
import { api } from '../lib/api.js';
import { injectBlocked, loopholePayload } from '../lib/demo.js';
import { formatRate } from '../lib/format.js';
import { CURRENCIES } from './currencies.js';
import { legsFromCycle, multiplierOf, paperRun } from './loops.js';
import LoopMap from './LoopMap.jsx';

const money = (n) => n.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
const clock = (t) => (t ? new Date(t).toLocaleTimeString([], { hour12: false }) : '');

function PaperRun({ legs }) {
  const start = legs[0].from;
  const [text, setText] = useState('1000');
  const [run, setRun] = useState(null);
  const amount = Number(text.replace(/,/g, ''));
  const valid = Number.isFinite(amount) && amount > 0 && amount <= 1e9;
  const result = run ? paperRun(run, legs) : null;
  return (
    <div className="paper">
      <label className="field" htmlFor="paper-amount">
        <span className="field-label">Start with ({start})</span>
        <input id="paper-amount" className="input num" inputMode="decimal" value={text} onChange={(e) => { setText(e.target.value); setRun(null); }} aria-invalid={!valid} />
      </label>
      <button type="button" className="pill pill-ink pill-block" disabled={!valid} onClick={() => setRun(amount)}>
        {run ? 'Run it again' : 'Try it on paper'}
      </button>
      {!valid ? <p className="msg msg-error">Enter an amount above zero.</p> : null}
      {result ? (
        <ol className="steps" aria-live="polite">
          {result.steps.map((s, i) => (
            <li key={`${run}-${i}`} className="step" style={{ '--i': i }}>
              <span className="step-n" aria-hidden="true">{i + 1}</span>
              <span>
                Convert <strong className="num">{money(s.amountIn)} {s.from}</strong> to <strong className="num">{money(s.amountOut)} {s.to}</strong>
                <span className="step-sub num"> at {formatRate(s.rate)}</span>
              </span>
            </li>
          ))}
          <li className="step step-end" style={{ '--i': result.steps.length }}>
            <span className="step-n" aria-hidden="true">=</span>
            <span>
              You end with <strong className="num">{money(result.end)} {start}</strong>:{' '}
              <strong className="num">{result.gain >= 0 ? '+' : '−'}{money(Math.abs(result.gain))} {start}</strong> ({result.percent >= 0 ? '+' : '−'}{Math.abs(result.percent).toFixed(3)}%).
            </span>
          </li>
        </ol>
      ) : null}
      <p className="caption">On paper only, at today’s quotes, with no fees. Real prices move while you trade, which is why loops rarely last.</p>
    </div>
  );
}

function Found({ scan, graph }) {
  const legs = legsFromCycle(scan.cycle, graph?.edges);
  return (
    <section className="card found-card" aria-label="Loop found">
      <p className="caption">Loop found</p>
      <h1 className="found-path display">{scan.cycle.join(' → ')}</h1>
      <p className="found-gain"><span className="num">+{scan.profitPercent.toFixed(2)}%</span> possible gain</p>
      <p className="caption">Each leg converts one currency into the next. If every step fills at these prices, you finish with more than you started with.</p>
      {legs ? <PaperRun key={scan.cycle.join('')} legs={legs} /> : <p className="empty">Prices moved while loading. The loop will update in a moment.</p>}
    </section>
  );
}

function Quiet() {
  const status = useStore((s) => s.status);
  const [busy, setBusy] = useState(false);
  const [note, setNote] = useState(null);
  const blocked = injectBlocked(status);
  const example = async () => {
    setBusy(true);
    setNote(null);
    try {
      await api.postRates(loopholePayload(Date.now()));
      setNote('Example prices added. They clear by themselves after about 30 seconds.');
    } catch (e) {
      setNote(describeError(e));
    } finally {
      setBusy(false);
    }
  };
  return (
    <section className="card found-card" aria-label="No loop right now">
      <p className="caption">Loop finder</p>
      <h1 className="found-path display">No loops right now</h1>
      <p className="caption">Cambio checks every route between currencies all the time. Real markets close these gaps within milliseconds, so most of the time there is nothing to find.</p>
      <div className="actions">
        <button type="button" className="pill pill-ink" onClick={example} disabled={busy || blocked}>{busy ? 'Adding…' : 'Show an example loop'}</button>
      </div>
      <p className="caption">{blocked ? 'Example prices are switched off while live prices are flowing.' : 'Adds practice prices so you can see how a loop looks and what it would pay.'}</p>
      {note ? <p className="caption" role="status">{note}</p> : null}
    </section>
  );
}

function Recent() {
  const alerts = useStore((s) => s.alerts);
  if (alerts.length === 0) return null;
  return (
    <section className="card recent-card" aria-label="Recent loops">
      <h2 className="display">Recent loops</h2>
      <ul className="rlist">
        {alerts.slice(0, 6).map((a, i) => (
          <li key={`${a.at}-${i}`} className="rrow">
            <span className="rrow-main">
              <strong>{a.active ? 'Found' : 'Gone'}</strong>
              {a.cycle.length ? <span className="num"> {a.cycle.join(' → ')}</span> : null}
            </span>
            <span className="num rrow-sub">{a.active ? `+${a.profitPercent.toFixed(2)}%` : ''} {clock(a.at)}</span>
          </li>
        ))}
      </ul>
    </section>
  );
}

function HowItWorks() {
  return (
    <section className="card how-card" aria-label="How loops work">
      <h2 className="display">How a loop works</h2>
      <ol className="how">
        <li>Currencies trade against each other at prices that are meant to agree.</li>
        <li>Sometimes they do not: going US Dollar to Singapore Dollar to Hong Kong Dollar and back to US Dollar returns slightly more than you began with.</li>
        <li>Cambio finds those loops by checking every route at once. Most are closed by banks and traders almost instantly.</li>
      </ol>
    </section>
  );
}

export default function OpportunitiesScreen() {
  const graph = useStore((s) => s.graph);
  const scan = useStore((s) => s.scan);
  const found = scan && scan.hasArbitrage && scan.cycle && scan.cycle.length > 2;
  const cycle = found ? scan.cycle : null;
  // the loop draws itself once each time a new one appears
  const [, force] = useState(0);
  useEffect(() => { force((n) => n + 1); }, [cycle?.join('')]);
  return (
    <div className="screen opps">
      <div className="opps-grid">
        <section className="card map-card" aria-label="Currency map">
          <LoopMap graph={graph} cycle={cycle} />
          <p className="caption map-note">Each circle is a currency. Faint lines are prices Cambio is watching. {Object.keys(CURRENCIES).length} currencies in all; those without a price are dimmed.</p>
        </section>
        <div className="opps-side">
          {found ? <Found scan={scan} graph={graph} /> : <Quiet />}
          <Recent />
          <HowItWorks />
        </div>
      </div>
    </div>
  );
}
