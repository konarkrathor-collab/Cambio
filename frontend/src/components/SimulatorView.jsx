import { useEffect, useId, useState } from 'react';
import { api } from '../lib/api.js';
import { describeError, useStore } from '../app/runtime.js';
import { evaluateTargets } from '../lib/targets.js';
import { formatInt, formatMicros, formatMillis, formatTime } from '../lib/format.js';
import Panel, { PanelState } from './Panel.jsx';
import { validateSimulation } from '../lib/simform.js';

const PRESETS = [5_000, 50_000, 1_000_000];

export default function SimulatorView() {
  const [form, setForm] = useState({ numOrders: '5000', seed: '42', baseline: true, profile: 'shallow', scanEvery: '100', rateVolatility: '0.01' });
  const [running, setRunning] = useState(false);
  const [startedAt, setStartedAt] = useState(null);
  const [result, setResult] = useState(null);
  const [loadState, setLoadState] = useState('loading'); // loading | none | error | ok
  const [error, setError] = useState(null);
  const [, tick] = useState(0);
  const id = useId();

  // GET /api/simulate/last only when health says a result exists: on a fresh server that
  // avoids the contract's 404, which the browser would log in the console
  const hasSimulation = useStore((s) => (s.health.data ? s.health.data.hasSimulation : null));
  useEffect(() => {
    if (hasSimulation === null) return undefined; // health not known yet
    if (hasSimulation === false) {
      setLoadState((st) => (st === 'ok' ? st : 'none'));
      return undefined;
    }
    let live = true;
    api.simulateLast().then(
      (r) => live && (setResult((cur) => cur ?? r), setLoadState('ok')),
      (e) => live && (e.code === 'NO_SIMULATION' ? setLoadState('none') : (setLoadState('error'), setError(describeError(e)))),
    );
    return () => {
      live = false;
    };
  }, [hasSimulation]);

  useEffect(() => {
    if (!running) return undefined;
    const t = setInterval(() => tick((n) => n + 1), 250);
    return () => clearInterval(t);
  }, [running]);

  const v = validateSimulation(form);
  const set = (k) => (e) => setForm({ ...form, [k]: e.target.type === 'checkbox' ? e.target.checked : e.target.value });

  const run = async (e) => {
    e.preventDefault();
    if (!v.body) return;
    setRunning(true);
    setStartedAt(Date.now());
    setError(null);
    try {
      const r = await api.simulate(v.body);
      setResult(r);
      setLoadState('ok');
    } catch (err) {
      setError(err.code === 'SIMULATION_RUNNING' ? 'Another simulation is running (409 SIMULATION_RUNNING). Try again when it finishes.' : describeError(err));
    } finally {
      setRunning(false);
    }
  };

  const field = (k, label, extra = {}) => (
    <div className="field">
      <label htmlFor={`${id}-${k}`} className="field-label">{label}</label>
      <input id={`${id}-${k}`} className="input num" value={form[k]} onChange={set(k)} disabled={running} aria-invalid={Boolean(v.errors[k])} aria-describedby={v.errors[k] ? `${id}-${k}-err` : undefined} {...extra} />
      {v.errors[k] ? <p className="field-error" id={`${id}-${k}-err`}>{v.errors[k]}</p> : null}
    </div>
  );

  return (
    <div className="grid-sim">
      <Panel id="simform" title="Run a simulation">
        <form onSubmit={run} noValidate>
          <div className="presets" role="group" aria-label="Order count presets">
            {PRESETS.map((n) => (
              <button key={n} type="button" className={`btn btn-small${form.numOrders === String(n) ? ' on' : ''}`} disabled={running} onClick={() => setForm({ ...form, numOrders: String(n) })} aria-pressed={form.numOrders === String(n)}>
                {formatInt(n)}
              </button>
            ))}
          </div>
          <div className="form-grid">
            {field('numOrders', 'Orders', { inputMode: 'numeric' })}
            {field('seed', 'Seed', { inputMode: 'numeric' })}
            <div className="field">
              <label htmlFor={`${id}-profile`} className="field-label">Profile</label>
              <select id={`${id}-profile`} className="input" value={form.profile} onChange={set('profile')} disabled={running}>
                <option value="shallow">shallow (default mix)</option>
                <option value="deep">deep (books grow deep)</option>
              </select>
            </div>
            <div className="field field-check">
              <input id={`${id}-baseline`} type="checkbox" checked={form.baseline} onChange={set('baseline')} disabled={running} />
              <label htmlFor={`${id}-baseline`}>Naive baseline</label>
            </div>
            {field('scanEvery', 'Scan every (0 = off)', { inputMode: 'numeric' })}
            {field('rateVolatility', 'Rate volatility (%)', { inputMode: 'decimal' })}
          </div>
          <button type="submit" className="btn btn-primary" disabled={running || !v.body}>
            {running ? `Running… ${((Date.now() - startedAt) / 1000).toFixed(1)} s` : 'Run simulation'}
          </button>
        </form>
        <div aria-live="polite">{error ? <p className="result result-error" role="alert">{error}</p> : null}</div>
        <p className="note muted">
          Runs inside the server on an isolated engine: the live books, accounts and stream are not touched. Timings are measured inside the engine (no HTTP).
        </p>
      </Panel>
      <Panel id="simresult" title="Engine vs naive baseline" className="area-simresult">
        {loadState === 'loading' ? <PanelState kind="loading">Loading the last result…</PanelState> : null}
        {loadState === 'none' && !result ? <PanelState kind="empty">No simulation has run since the server started.</PanelState> : null}
        {loadState === 'error' && !result ? <PanelState kind="error">{error}</PanelState> : null}
        {result ? <Results r={result} /> : null}
      </Panel>
    </div>
  );
}

function Check({ ok }) {
  return ok ? <span className="tone-ok">✓ match</span> : <span className="tone-down">✗ MISMATCH</span>;
}

function Results({ r }) {
  const b = r.baseline;
  const rows = [
    ['Orders/s', formatInt(r.ordersPerSec), b && formatInt(b.ordersPerSec)],
    ['Duration', formatMillis(r.durationMs), b && formatMillis(b.durationMs)],
    ['Submit p50', formatMicros(r.latencyUs.submit.p50), b && formatMicros(b.latencyUs.submit.p50)],
    ['Submit p99', formatMicros(r.latencyUs.submit.p99), b && formatMicros(b.latencyUs.submit.p99)],
    ['Cancel p50', formatMicros(r.latencyUs.cancel.p50), b && formatMicros(b.latencyUs.cancel.p50)],
    ['Cancel p99', formatMicros(r.latencyUs.cancel.p99), b && formatMicros(b.latencyUs.cancel.p99)],
    ['Trades', formatInt(r.trades), b && formatInt(b.trades)],
    ['Rejects', formatInt(r.rejects), b && formatInt(b.rejects)],
    ['Cancels', formatInt(r.cancels), b && formatInt(b.cancels)],
    ['Resting at end', formatInt(r.restingOrders), b && formatInt(b.restingOrders)],
    ['Trade checksum', r.tradeChecksum, b && b.tradeChecksum],
  ];
  const targets = evaluateTargets(r);
  const maxRate = Math.max(r.ordersPerSec, b ? b.ordersPerSec : 0, 1);
  return (
    <div className="sim-results">
      <p className="muted num">
        {formatInt(r.numOrders)} orders · seed {r.seed} · profile {r.profile} · scan every {formatInt(r.scanEvery)} · volatility {r.rateVolatility}% · finished {formatTime(r.finishedAt)} UTC
      </p>
      <table className="mini-table sim-table">
        <caption className="sr-only">Engine against the naive baseline</caption>
        <thead><tr><th scope="col">Measure</th><th scope="col" className="num">Engine</th><th scope="col" className="num">Naive baseline</th></tr></thead>
        <tbody>
          {rows.map(([k, e, n]) => (
            <tr key={k}><th scope="row">{k}</th><td className="num">{e}</td><td className="num">{b ? n : 'not run'}</td></tr>
          ))}
          {b ? (
            <>
              <tr><th scope="row">Speedup</th><td className="num" colSpan={2}><strong>{b.speedup}×</strong> {Number(b.speedup) < 1 ? '(the baseline was faster on this run)' : ''}</td></tr>
              <tr><th scope="row">Trades match</th><td colSpan={2}><Check ok={b.tradesMatch} /></td></tr>
              <tr><th scope="row">Final cash matches</th><td colSpan={2}><Check ok={b.cashMatch} /></td></tr>
              <tr><th scope="row">checksumMatch</th><td colSpan={2}><Check ok={b.checksumMatch} /></td></tr>
            </>
          ) : null}
        </tbody>
      </table>
      <div className="bars" aria-hidden="true">
        <div className="bar-row"><span className="bar-label">engine</span><span className="bar bar-engine" style={{ width: `${(r.ordersPerSec / maxRate) * 100}%` }} /><span className="num">{formatInt(r.ordersPerSec)}/s</span></div>
        {b ? <div className="bar-row"><span className="bar-label">baseline</span><span className="bar bar-base" style={{ width: `${(b.ordersPerSec / maxRate) * 100}%` }} /><span className="num">{formatInt(b.ordersPerSec)}/s</span></div> : null}
      </div>
      <h3 className="subhead">Doc 04 targets</h3>
      <ul className="targets">
        {targets.map((t) => (
          <li key={t.id} className={t.met === true ? 'target-met' : t.met === false ? 'target-missed' : 'target-na'}>
            <strong>{t.met === true ? 'MET' : t.met === false ? 'MISSED' : 'NOT MEASURED'}</strong>{' '}
            {t.target}
            {t.measured !== null ? <> · measured <span className="num">{t.id === 'throughput' ? formatInt(t.measured) : t.measured.toFixed(3)}</span> {t.unit}</> : null}
            <span className="muted"> — {t.note}</span>
          </li>
        ))}
      </ul>
      <p className="muted">
        Scans: {formatInt(r.scans.count)}, loopholes found in {formatInt(r.scans.loopholesFound)} (synthetic rates have no bid/ask spread).
      </p>
    </div>
  );
}
