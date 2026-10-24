import { useState } from 'react';
import { useStore } from '../app/runtime.js';
import { spread } from '../lib/depth.js';
import { formatInt, formatPrice } from '../lib/format.js';
import { addPracticeLiquidity } from './liquidity.js';

const LEVELS = 7;

function Level({ level, tickSize, max, kind }) {
  if (!level) return <li className="lv lv-blank" aria-hidden="true" />;
  const pct = max > 0 ? (level.cumulative / max) * 100 : 0;
  return (
    <li className={`lv lv-${kind}`}>
      <span className="lv-bar" style={{ width: `${pct}%` }} aria-hidden="true" />
      <span className="lv-price num">{formatPrice(level.price, tickSize)}</span>
      <span className="lv-vol num">{formatInt(level.volume)}</span>
      <span className="lv-cum num">{formatInt(level.cumulative)}</span>
    </li>
  );
}

export default function Ladder({ pair, book, tickSize }) {
  const hasRate = useStore((s) => Boolean(s.rates[pair]));
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState(null);
  const empty = !book || (book.bids.length === 0 && book.asks.length === 0);

  const seed = async () => {
    setBusy(true);
    setMsg(null);
    try {
      const r = await addPracticeLiquidity(pair, tickSize);
      setMsg(`Added ${r.posted} practice orders around ${r.mid}.`);
    } catch (e) {
      setMsg(e.message);
    } finally {
      setBusy(false);
    }
  };

  const sp = book ? spread(book) : null;
  const max = book ? Math.max(book.bids.at(-1)?.cumulative ?? 0, book.asks.at(-1)?.cumulative ?? 0) : 0;
  const asks = [];
  for (let i = Math.min(LEVELS, book?.asks.length ?? 0) - 1; i >= 0; i--) asks.push(book.asks[i]);
  const bids = [];
  for (let i = 0; i < Math.min(LEVELS, book?.bids.length ?? 0); i++) bids.push(book.bids[i]);

  return (
    <section className="card ladder-card" aria-label="Order book">
      <header className="card-head"><h2 className="display">Order book</h2><p className="caption">Orders waiting to trade</p></header>
      {empty ? null : <div className="lv-head" aria-hidden="true"><span>Price</span><span>Amount</span><span>Total</span></div>}
      {empty ? null : (
        <>
          <ul className="lv-list lv-asks" aria-label="Sell orders">{asks.map((l, i) => <Level key={`a${i}`} level={l} tickSize={tickSize} max={max} kind="ask" />)}</ul>
          <div className="lv-spread">
            {sp?.text ? <><span className="caption">Gap between buy and sell</span><span className="num">{sp.text}</span></> : <span className="caption">Only one side has orders</span>}
          </div>
          <ul className="lv-list lv-bids" aria-label="Buy orders">{bids.map((l, i) => <Level key={`b${i}`} level={l} tickSize={tickSize} max={max} kind="bid" />)}</ul>
        </>
      )}
      {empty ? (
        <div className="ladder-empty">
          <p>No one has an order on {pair} yet.</p>
          <button type="button" className="pill pill-ink" onClick={seed} disabled={busy || !hasRate}>{busy ? 'Adding…' : 'Add practice orders'}</button>
          <p className="caption">Practice orders come from simulated traders so you have someone to trade with.</p>
        </div>
      ) : null}
      {msg ? <p className="caption" role="status">{msg}</p> : null}
    </section>
  );
}
