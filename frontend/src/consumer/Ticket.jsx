import { useId, useMemo, useState } from 'react';
import { describeError, placeOrder, useStore } from '../app/runtime.js';
import { numberToTicks } from '../lib/decimal.js';
import { formatInt, formatPrice, tickDecimals } from '../lib/format.js';
import { REASON_TEXT, validateOrder } from '../lib/validation.js';
import { splitPair } from './currencies.js';
import { practiceTraderId } from './practice.js';

const money = (n) => n.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 4 });

/** What the order will do, in a sentence, before it is sent. */
function summary({ side, type, qty, price, base, quote, tickSize, bestOpp, rate }) {
  const buy = side === 'BUY';
  if (!(qty > 0)) return 'Enter an amount to see what happens.';
  const verb = buy ? 'buy' : 'sell';
  if (type === 'LIMIT') {
    const p = Number(price);
    if (!(p > 0)) return 'Enter a price to see what happens.';
    return `You ${verb} ${formatInt(qty)} ${base} at ${formatPrice(p, tickSize)} or better, and ${buy ? 'pay' : 'receive'} up to ${money(qty * p)} ${quote}.`;
  }
  const ref = bestOpp ?? null;
  if (ref === null) return `No one is ${buy ? 'selling' : 'buying'} ${base} right now, so a market order cannot be filled.`;
  return `You ${verb} ${formatInt(qty)} ${base} at the best price available, about ${money(qty * ref)} ${quote}.`;
}

function Result({ result, tickSize, base }) {
  if (result.error) {
    return <div className="result result-bad" role="alert"><strong>Not sent.</strong> {result.error}</div>;
  }
  const r = result.response;
  const verb = r.side === 'BUY' ? 'bought' : 'sold';
  let title;
  let tone = 'ok';
  if (r.status === 'REJECTED') {
    title = 'Not accepted.';
    tone = 'bad';
  } else if (r.status === 'FILLED') title = `Done. You ${verb} ${formatInt(r.filledQuantity)} ${base}.`;
  else if (r.status === 'PARTIAL') title = `Partly done. You ${verb} ${formatInt(r.filledQuantity)} of ${formatInt(r.quantity)} ${base}; ${formatInt(r.restingQuantity)} is waiting on the book.`;
  else title = 'Order placed. It waits on the book until someone trades at your price.';
  return (
    <div className={`result result-${tone}`} role="status">
      <strong>{title}</strong>
      {r.status === 'REJECTED' && r.reason ? <p>{REASON_TEXT[r.reason] ?? r.reason}</p> : null}
      {r.trades?.length ? (
        <ul className="fills">
          {r.trades.map((t) => <li key={t.tradeId} className="num">{formatInt(t.quantity)} at {formatPrice(t.price, tickSize)}</li>)}
        </ul>
      ) : null}
    </div>
  );
}

export default function Ticket({ pair, tickSize }) {
  const id = useId();
  const { base, quote } = splitPair(pair);
  const traderId = practiceTraderId();
  const tickers = useStore((s) => s.tickers);
  const accounts = useStore((s) => s.accounts);
  const book = useStore((s) => s.book);
  const rate = useStore((s) => s.rates[pair]?.rate ?? null);
  const [side, setSide] = useState('BUY');
  const [type, setType] = useState('LIMIT');
  const [qty, setQty] = useState('10');
  const [typedPrice, setTypedPrice] = useState(null); // null = follow the live price
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState(null);
  const [touched, setTouched] = useState(false);

  const d = tickDecimals(tickSize);
  // until the user types, the price follows the live rate, else the book's last trade or its best price
  const fallback = rate ?? (book && book.pair === pair ? book.lastTradePrice ?? book.bids?.[0]?.price ?? book.asks?.[0]?.price ?? null : null);
  const price = typedPrice ?? (fallback ? formatPrice(fallback, tickSize) : '');
  const tickerMap = useMemo(() => new Map((tickers ?? []).map((t) => [t.pair, t.tickSize])), [tickers]);
  const account = accounts ? accounts.find((a) => a.traderId === traderId) ?? null : undefined;
  const oppSide = book && book.pair === pair ? (side === 'BUY' ? book.asks : book.bids) : null;
  const bestOppTicks = oppSide === null ? undefined : oppSide.length ? numberToTicks(oppSide[0].price, d) : null;
  const bestOpp = oppSide && oppSide.length ? oppSide[0].price : null;

  const v = validateOrder({ traderId, pair, side, type, price: type === 'LIMIT' ? price : '', quantity: qty }, { tickers: tickerMap, account, bestOppositeTicks: bestOppTicks });
  const q = Number(qty);
  const message = Object.values(v.errors)[0] ?? Object.values(v.warnings)[0] ?? null;
  const isError = Object.keys(v.errors).length > 0;

  const submit = async (e) => {
    e.preventDefault();
    setTouched(true);
    if (!v.body) return;
    setBusy(true);
    try {
      setResult({ response: await placeOrder(v.body) });
    } catch (err) {
      setResult({ error: describeError(err) });
    } finally {
      setBusy(false);
    }
  };

  return (
    <section className="card ticket-card" aria-label="Place an order">
      <form onSubmit={submit} noValidate>
        <div className="seg seg-big" role="group" aria-label="Buy or sell">
          {['BUY', 'SELL'].map((s) => (
            <button key={s} type="button" className={side === s ? 'seg-btn seg-on' : 'seg-btn'} aria-pressed={side === s} onClick={() => setSide(s)}>
              {s === 'BUY' ? 'Buy' : 'Sell'}
            </button>
          ))}
        </div>
        <div className="seg seg-small" role="group" aria-label="Order type">
          {[['LIMIT', 'Choose a price'], ['MARKET', 'Best price now']].map(([t, label]) => (
            <button key={t} type="button" className={type === t ? 'seg-btn seg-on' : 'seg-btn'} aria-pressed={type === t} onClick={() => setType(t)}>{label}</button>
          ))}
        </div>

        <label className="field" htmlFor={`${id}-qty`}>
          <span className="field-label">Amount ({base})</span>
          <input id={`${id}-qty`} className="input num" inputMode="numeric" value={qty} onChange={(e) => setQty(e.target.value)} aria-invalid={touched && isError} aria-describedby={`${id}-msg`} />
        </label>
        {type === 'LIMIT' ? (
          <label className="field" htmlFor={`${id}-price`}>
            <span className="field-label">Price ({quote} per {base})</span>
            <input id={`${id}-price`} className="input num" inputMode="decimal" value={price} onChange={(e) => setTypedPrice(e.target.value)} aria-describedby={`${id}-msg`} />
          </label>
        ) : null}

        <p className="summary">{summary({ side, type, qty: q, price, base, quote, tickSize, bestOpp, rate })}</p>
        <p id={`${id}-msg`} className={message ? (isError ? 'msg msg-error' : 'msg msg-warn') : 'msg'} aria-live="polite">
          {message ? `${isError ? 'Check this: ' : 'Heads up: '}${message}` : ''}
        </p>

        <button type="submit" className="pill pill-ink pill-block" disabled={busy}>
          {busy ? 'Sending…' : `${side === 'BUY' ? 'Buy' : 'Sell'} ${base}`}
        </button>
      </form>
      {result ? <Result result={result} tickSize={tickSize} base={base} /> : null}
    </section>
  );
}
