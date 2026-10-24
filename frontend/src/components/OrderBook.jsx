import { useEffect, useRef, useState } from 'react';
import { useStore } from '../app/runtime.js';
import { maxCumulative, spread } from '../lib/depth.js';
import { formatInt, formatPrice } from '../lib/format.js';
import { PanelState } from './Panel.jsx';
import { BOOK_DEPTH } from '../app/runtime.js';

const FLASH_MS = 600;

/** Which prices changed volume since the last snapshot (for a brief flash). */
function useChanged(book) {
  const prev = useRef(new Map());
  const [changed, setChanged] = useState(() => new Set());
  useEffect(() => {
    if (!book) {
      prev.current = new Map();
      return undefined;
    }
    const now = new Map();
    const diff = new Set();
    for (const side of ['bids', 'asks']) {
      for (const l of book[side]) {
        const key = `${side}:${l.price}`;
        now.set(key, l.volume);
        if (prev.current.size > 0 && prev.current.get(key) !== l.volume) diff.add(key);
      }
    }
    prev.current = now;
    if (diff.size === 0) return undefined;
    setChanged(diff);
    const t = setTimeout(() => setChanged(new Set()), FLASH_MS);
    return () => clearTimeout(t);
  }, [book]);
  return changed;
}

function Row({ side, level, tickSize, max, flash, best }) {
  if (!level) {
    return (
      <tr className={`book-row book-${side} book-empty`} aria-hidden="true">
        <td colSpan={5}>&nbsp;</td>
      </tr>
    );
  }
  const pct = max > 0 ? (level.cumulative / max) * 100 : 0;
  const tint = side === 'ask' ? 'var(--ask-bar)' : 'var(--bid-bar)';
  // the depth bar: cumulative volume as a share of the deepest side, drawn from the right
  const bar = { background: `linear-gradient(to left, ${tint} ${pct}%, transparent ${pct}%)` };
  return (
    <tr className={`book-row book-${side}${flash ? ' flash' : ''}${best ? ' book-best' : ''}`} style={bar}>
      <td className="book-side">{side === 'ask' ? 'ASK' : 'BID'}</td>
      <td className="num price">{formatPrice(level.price, tickSize)}</td>
      <td className="num">{formatInt(level.volume)}</td>
      <td className="num">{formatInt(level.orders)}</td>
      <td className="num">{formatInt(level.cumulative)}</td>
    </tr>
  );
}

/** The ladder: asks above (best ask lowest), the spread, bids below (best bid highest). */
export default function OrderBook() {
  const book = useStore((s) => s.book);
  const error = useStore((s) => s.errors.book);
  const changed = useChanged(book);

  if (!book) {
    return error ? <PanelState kind="error">Order book unavailable: {error}</PanelState> : <PanelState kind="loading">Loading the order book…</PanelState>;
  }
  const max = maxCumulative(book);
  const sp = spread(book);
  const asks = [];
  for (let i = BOOK_DEPTH - 1; i >= 0; i--) asks.push(book.asks[i] || null);
  const bids = [];
  for (let i = 0; i < BOOK_DEPTH; i++) bids.push(book.bids[i] || null);

  return (
    <div className="book">
      {error ? <PanelState kind="error">Showing the last snapshot; refresh failed: {error}</PanelState> : null}
      <table className="book-table">
        <caption className="sr-only">Order book for {book.pair}: asks above the spread, bids below</caption>
        <thead>
          <tr>
            <th scope="col">Side</th>
            <th scope="col" className="num">Price</th>
            <th scope="col" className="num">Volume</th>
            <th scope="col" className="num">Orders</th>
            <th scope="col" className="num">Cumulative</th>
          </tr>
        </thead>
        <tbody>
          {asks.map((l, i) => (
            <Row key={`a${i}`} side="ask" level={l} tickSize={book.tickSize} max={max} flash={l && changed.has(`asks:${l.price}`)} best={i === BOOK_DEPTH - 1} />
          ))}
          <tr className="spread-row">
            <td className="spread-k">SPREAD</td>
            <td className="num" colSpan={2}>
              {sp && sp.ticks !== null ? `${sp.text} (${formatInt(sp.ticks)} tick${sp.ticks === 1 ? '' : 's'})` : book.bids.length || book.asks.length ? 'one side empty' : 'empty book'}
            </td>
            <td className="num muted" colSpan={2}>
              last {formatPrice(book.lastTradePrice, book.tickSize)}
            </td>
          </tr>
          {bids.map((l, i) => (
            <Row key={`b${i}`} side="bid" level={l} tickSize={book.tickSize} max={max} flash={l && changed.has(`bids:${l.price}`)} best={i === 0} />
          ))}
        </tbody>
      </table>
      {book.bids.length === 0 && book.asks.length === 0 ? (
        <PanelState kind="empty">No resting orders on {book.pair}. Place one, or use DEMO › Seed flow.</PanelState>
      ) : null}
    </div>
  );
}
