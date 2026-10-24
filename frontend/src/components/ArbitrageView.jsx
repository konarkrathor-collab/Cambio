import { useStore } from '../app/runtime.js';
import { formatInt, formatMultiplier, formatPercent, formatTime } from '../lib/format.js';
import Panel, { PanelState } from './Panel.jsx';
import CurrencyGraph from './CurrencyGraph.jsx';

export default function ArbitrageView() {
  const graph = useStore((s) => s.graph);
  const scan = useStore((s) => s.scan);
  const status = useStore((s) => s.status);
  const alerts = useStore((s) => s.alerts);
  const graphError = useStore((s) => s.errors.graph);
  const scanError = useStore((s) => s.errors.scan);

  const threshold = scan?.minProfitPercent ?? status?.minProfitPercent ?? null;
  const cycle = scan?.hasArbitrage ? scan.cycle : null;

  return (
    <div className="grid-arb">
      <Panel id="graph" title="Currency graph" className="area-graph">
        {graph ? (
          <>
            <CurrencyGraph graph={graph} cycle={cycle} />
            <ul className="legend-list" aria-label="Legend">
              <li><span className="key key-edge" aria-hidden="true" /> quoted rate</li>
              <li><span className="key key-derived" aria-hidden="true" /> derived through USD</li>
              <li><span className="key key-stale" aria-hidden="true" /> stale (faint, dashed)</li>
              <li><span className="key key-cycle" aria-hidden="true" /> detected cycle</li>
              <li><span className="key key-inactive" aria-hidden="true" /> currency without a rate (dimmed)</li>
            </ul>
          </>
        ) : graphError ? (
          <PanelState kind="error">Graph unavailable: {graphError}</PanelState>
        ) : (
          <PanelState kind="loading">Loading the currency graph…</PanelState>
        )}
      </Panel>
      <div className="col">
        <Panel id="cycle" title="Loophole">
          <div aria-live="polite" className="cycle-box">
            {!scan ? (
              scanError ? <PanelState kind="error">Scan unavailable: {scanError}</PanelState> : <PanelState kind="loading">Waiting for the first scan…</PanelState>
            ) : scan.hasArbitrage ? (
              <div className="cycle-found">
                <p className="cycle-tag"><span className="chip chip-accent">LOOPHOLE</span> <span className="faint num">scan {formatInt(scan.scanCount)} · {formatTime(scan.scannedAt)} UTC</span></p>
                <p className="cycle-legs num">{scan.cycle.join(' → ')}</p>
                <dl className="kv cycle-kv">
                  <dt>Profit</dt><dd className="num cycle-profit">+{formatPercent(scan.profitPercent)}</dd>
                  <dt>Multiplier</dt><dd className="num">{formatMultiplier(scan.multiplier)}</dd>
                  <dt>Legs</dt><dd className="num">{scan.cycle.length - 1}</dd>
                </dl>
              </div>
            ) : (
              <div>
                <p className="cycle-tag"><span className="chip">NONE</span> <span className="faint num">scan {formatInt(scan.scanCount)} · {formatTime(scan.scannedAt)} UTC</span></p>
                <p className="cycle-none">
                  No loophole above {formatPercent(threshold, 2)}. Real markets close these within milliseconds, so this is the expected result.
                </p>
              </div>
            )}
          </div>
        </Panel>
        <Panel id="scan" title="Scanner">
          <dl className="kv">
            <dt>Threshold</dt><dd className="num">{formatPercent(threshold, 3)}</dd>
            <dt>Last scan</dt><dd className="num">{formatTime(scan?.scannedAt ?? null)} UTC</dd>
            <dt>Scans</dt><dd className="num">{formatInt(scan?.scanCount ?? null)}</dd>
            <dt>Rates used</dt><dd className="num">{formatInt(scan?.edges ?? null)}</dd>
            <dt>Excluded (stale)</dt><dd className="num">{formatInt(scan?.excludedEdges ?? null)}</dd>
            <dt>Source</dt><dd className="num">{graph?.source ?? status?.source ?? '—'}{status?.replay ? ' (replay)' : ''}</dd>
            {scan?.belowThreshold ? <><dt>Below threshold</dt><dd>a cycle exists but earns less than the threshold</dd></> : null}
          </dl>
        </Panel>
        <Panel id="alerts" title="Loophole alerts" meta={alerts.length ? `last ${alerts.length}` : null} className="grow">
          {alerts.length === 0 ? (
            <PanelState kind="empty">No LOOPHOLE_ALERT since this page opened. Alerts arrive when a cycle is found or lost.</PanelState>
          ) : (
            <ol className="alerts" aria-live="polite">
              {alerts.map((a, i) => (
                <li key={`${a.scanCount}-${i}`} className={a.active ? 'alert-found' : 'alert-cleared'}>
                  <span className="num muted">{formatTime(a.at)}</span>{' '}
                  <strong>{a.active ? 'FOUND' : 'CLEARED'}</strong>{' '}
                  {a.active ? <span className="num">{a.cycle.join(' → ')} +{formatPercent(a.profitPercent)}</span> : <span className="muted">no cycle above the threshold</span>}
                </li>
              ))}
            </ol>
          )}
        </Panel>
      </div>
    </div>
  );
}
