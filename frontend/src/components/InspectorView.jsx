import { useEffect, useState } from 'react';
import { api } from '../lib/api.js';
import { describeError, useStore } from '../app/runtime.js';
import { formatInt, formatPrice } from '../lib/format.js';
import Panel, { PanelState } from './Panel.jsx';

export const REFRESH_MS = 2000;

/** GET /api/inspector/{pair}: refreshed on BOOK_UPDATE for the pair and every 2 s. */
export default function InspectorView() {
  const pair = useStore((s) => s.pair);
  const bookVersion = useStore((s) => s.bookVersion);
  const tickers = useStore((s) => s.tickers);
  const [data, setData] = useState(null);
  const [error, setError] = useState(null);
  const [tickTime, setTickTime] = useState(0);

  useEffect(() => {
    const t = setInterval(() => setTickTime((n) => n + 1), REFRESH_MS);
    return () => clearInterval(t);
  }, []);

  useEffect(() => {
    let live = true;
    api.inspector(pair).then(
      (d) => live && (setData(d), setError(null)),
      (e) => live && setError(describeError(e)),
    );
    return () => {
      live = false;
    };
  }, [pair, bookVersion, tickTime]);

  const tickSize = (tickers || []).find((t) => t.pair === pair)?.tickSize ?? (pair.endsWith('/JPY') ? 0.001 : 0.00001);
  if (!data || data.pair !== pair) {
    return error ? <PanelState kind="error">Inspector unavailable: {error}</PanelState> : <PanelState kind="loading">Loading the inspector…</PanelState>;
  }
  return (
    <div className="grid-insp">
      {error ? <PanelState kind="error">Showing the last snapshot; refresh failed: {error}</PanelState> : null}
      {[['asks', 'Asks (SELL side)'], ['bids', 'Bids (BUY side)']].map(([side, title]) => (
        <Panel key={side} id={`insp-${side}`} title={`${title} · ${pair}`} meta={`${formatInt(data[side].levelCount)} levels`} className={`insp-${side}`}>
          <Side side={data[side]} tickSize={tickSize} kind={side} />
        </Panel>
      ))}
      <p className="muted note insp-foot num">seq {formatInt(data.seq)} · refreshed on every book update for {pair} and every {REFRESH_MS / 1000} s</p>
    </div>
  );
}

function Side({ side, tickSize, kind }) {
  return (
    <div className="insp-side">
      <TreeDiagram shape={side.shape} levelCount={side.levelCount} />
      {side.levels.length === 0 ? (
        <PanelState kind="empty">No levels on this side.</PanelState>
      ) : (
        <div className="scroll">
          <table className="mini-table">
            <caption className="sr-only">Price levels, best first, each with its FIFO queue head</caption>
            <thead>
              <tr><th scope="col" className="num">Price</th><th scope="col" className="num">Volume</th><th scope="col" className="num">Orders</th><th scope="col">Queue head</th><th scope="col" className="num">Head remaining</th></tr>
            </thead>
            <tbody>
              {side.levels.map((l) => (
                <tr key={l.price}>
                  <td className={`num ${kind === 'bids' ? 'tone-bid' : 'tone-ask'}`}>{formatPrice(l.price, tickSize)}</td>
                  <td className="num">{formatInt(l.volume)}</td>
                  <td className="num">{formatInt(l.orders)}</td>
                  <td className="num">{l.head ? `${l.head.orderId} · T${l.head.traderId}` : '—'}</td>
                  <td className="num">{l.head ? formatInt(l.head.remaining) : '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
          {side.truncated ? <p className="muted note">Showing the best 50 of {formatInt(side.levelCount)} levels.</p> : null}
        </div>
      )}
    </div>
  );
}

/** The ladder's B+ tree as root, internal levels and leaves (linked left to right), with its real counts. */
function TreeDiagram({ shape, levelCount }) {
  const leaves = Math.min(shape.leaves, 16);
  const internalLevels = Math.max(0, shape.height - 1);
  // with a single leaf its fill is exact; with more, only the average is known, so none is drawn
  const fill = shape.leaves === 1 ? Math.min(1, shape.entries / shape.order) : null;
  return (
    <figure className="tree" aria-label={`${shape.structure} of order ${shape.order}: height ${shape.height}, ${shape.internalNodes} internal nodes, ${shape.leaves} leaves, ${shape.entries} entries`}>
      <figcaption className="tree-caption num">
        {shape.structure} · order {shape.order} · height {shape.height} · {formatInt(shape.internalNodes)} internal · {formatInt(shape.leaves)} leaves · {formatInt(shape.entries)} entries ({formatInt(levelCount)} price levels)
      </figcaption>
      <div className="tree-rows" aria-hidden="true">
        {internalLevels > 0 ? (
          <>
            <div className="tree-row">
              <span className="tree-node tree-internal">root{internalLevels > 1 ? ` + ${formatInt(shape.internalNodes - 1)} internal` : ''}</span>
            </div>
            <div className="tree-fan" />
          </>
        ) : null}
        <div className="tree-row tree-leaves">
          {Array.from({ length: leaves }, (_, i) => (
            <span key={i} className="tree-node tree-leaf">
              {internalLevels === 0 ? 'root leaf' : ''}
              {fill !== null ? <span className="tree-fill" style={{ width: `${fill * 100}%` }} /> : null}
            </span>
          ))}
          {shape.leaves > leaves ? <span className="muted num">+{formatInt(shape.leaves - leaves)} leaves</span> : null}
        </div>
        {fill !== null ? <div className="tree-row tree-note num">{formatInt(shape.entries)} of {shape.order} slots used</div> : null}
        <div className="tree-row tree-note muted">leaves are linked left to right; each entry is one price level with its FIFO queue</div>
      </div>
    </figure>
  );
}
