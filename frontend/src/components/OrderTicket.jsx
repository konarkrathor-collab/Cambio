import { useEffect, useId, useMemo, useState } from 'react';
import { describeError, placeOrder, useStore } from '../app/runtime.js';
import { numberToTicks } from '../lib/decimal.js';
import { formatInt, formatPrice, normalizePair, tickDecimals } from '../lib/format.js';
import { REASON_TEXT, STATUS_TEXT, validateOrder } from '../lib/validation.js';

/** The order form (contract §3.1) and the result of the last submission. */
export default function OrderTicket({ traderId, onTrader }) {
  const pair = useStore((s) => s.pair);
  const accounts = useStore((s) => s.accounts);
  const tickers = useStore((s) => s.tickers);
  const book = useStore((s) => s.book);
  const [form, setForm] = useState({ pair, side: 'BUY', type: 'LIMIT', price: '', quantity: '10' });
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState(null); // {response} | {error}
  const [touched, setTouched] = useState(false);
  const id = useId();

  useEffect(() => setForm((f) => ({ ...f, pair })), [pair]);

  const tickerMap = useMemo(() => new Map((tickers || []).map((t) => [t.pair, t.tickSize])), [tickers]);
  const formPair = normalizePair(form.pair);
  const account = accounts ? accounts.find((a) => String(a.traderId) === String(traderId)) ?? null : undefined;
  let bestOppositeTicks;
  if (book && book.pair === formPair) {
    const side = form.side === 'BUY' ? book.asks : book.bids;
    bestOppositeTicks = side.length ? numberToTicks(side[0].price, tickDecimals(book.tickSize)) : null;
  }
  const v = validateOrder({ ...form, traderId }, { tickers: tickerMap, account, bestOppositeTicks });
  const tickSize = tickerMap.get(formPair) ?? 0.00001;

  const set = (k) => (e) => setForm({ ...form, [k]: e.target.value });

  const submit = async (e) => {
    e.preventDefault();
    setTouched(true);
    if (!v.body) return;
    setBusy(true);
    try {
      const response = await placeOrder(v.body);
      setResult({ response });
    } catch (err) {
      setResult({ error: describeError(err), code: err.code });
    } finally {
      setBusy(false);
    }
  };

  const showErr = (k) => (touched || form[k] !== '') && v.errors[k];
  const fieldMsg = (k) => {
    const err = k === 'traderId' ? touched && v.errors[k] : showErr(k);
    if (err) return <p className="field-error" id={`${id}-${k}-msg`}>{err}</p>;
    if (v.warnings[k]) return <p className="field-warn" id={`${id}-${k}-msg`}>{v.warnings[k]}</p>;
    return null;
  };
  const described = (k) => (v.errors[k] || v.warnings[k] ? `${id}-${k}-msg` : undefined);

  return (
    <div className="ticket">
      <form onSubmit={submit} noValidate>
        <div className="form-grid">
          <div className="field">
            <label htmlFor={`${id}-trader`} className="field-label">Trader</label>
            <select id={`${id}-trader`} className="input" value={traderId} onChange={(e) => onTrader(e.target.value)} aria-describedby={described('traderId')}>
              {!accounts ? <option value={traderId}>{accounts === null ? 'loading…' : traderId}</option> : null}
              {(accounts || []).map((a) => (
                <option key={a.traderId} value={String(a.traderId)}>T{a.traderId}</option>
              ))}
            </select>
            {fieldMsg('traderId')}
          </div>
          <div className="field">
            <label htmlFor={`${id}-pair`} className="field-label">Pair</label>
            <input id={`${id}-pair`} className="input num" value={form.pair} onChange={set('pair')} spellCheck={false} autoComplete="off" aria-describedby={described('pair')} />
            {fieldMsg('pair')}
          </div>
          <fieldset className="field segmented">
            <legend className="field-label">Side</legend>
            {['BUY', 'SELL'].map((s) => (
              <label key={s} className={`seg seg-${s.toLowerCase()}${form.side === s ? ' on' : ''}`}>
                <input type="radio" name={`${id}-side`} value={s} checked={form.side === s} onChange={set('side')} />
                {s}
              </label>
            ))}
          </fieldset>
          <fieldset className="field segmented">
            <legend className="field-label">Type</legend>
            {['LIMIT', 'MARKET'].map((t) => (
              <label key={t} className={`seg${form.type === t ? ' on' : ''}`}>
                <input type="radio" name={`${id}-type`} value={t} checked={form.type === t} onChange={set('type')} />
                {t}
              </label>
            ))}
            {fieldMsg('type')}
          </fieldset>
          <div className="field">
            <label htmlFor={`${id}-price`} className="field-label">Price <span className="muted">(tick {formatPrice(tickSize, tickSize)})</span></label>
            <input
              id={`${id}-price`}
              className="input num"
              inputMode="decimal"
              value={form.type === 'MARKET' ? '' : form.price}
              placeholder={form.type === 'MARKET' ? 'market: best available' : ''}
              disabled={form.type === 'MARKET'}
              onChange={set('price')}
              aria-describedby={described('price')}
            />
            {form.type === 'LIMIT' ? fieldMsg('price') : null}
          </div>
          <div className="field">
            <label htmlFor={`${id}-qty`} className="field-label">Quantity</label>
            <input id={`${id}-qty`} className="input num" inputMode="numeric" value={form.quantity} onChange={set('quantity')} aria-describedby={described('quantity')} />
            {fieldMsg('quantity')}
          </div>
        </div>
        <button type="submit" className={`btn btn-${form.side === 'BUY' ? 'buy' : 'sell'}`} disabled={busy}>
          {busy ? 'Sending…' : `${form.side} ${form.type}`}
        </button>
      </form>
      <div aria-live="polite" className="ticket-result">
        {result ? <Result result={result} tickSize={tickSize} /> : null}
      </div>
    </div>
  );
}

function Result({ result, tickSize }) {
  if (result.error) {
    return (
      <div className="result result-error" role="alert">
        <strong>{result.code === 'NETWORK' ? 'Not sent' : 'Not accepted (HTTP error)'}</strong>
        <p>{result.error}</p>
      </div>
    );
  }
  const r = result.response;
  const tone = r.status === 'REJECTED' ? 'error' : r.status === 'QUEUED' ? 'info' : 'ok';
  return (
    <div className={`result result-${tone}`}>
      <p>
        <strong className="num">{r.orderId}</strong> <span className={`status status-${r.status.toLowerCase()}`}>{r.status}</span>{' '}
        {STATUS_TEXT[r.status]}
      </p>
      {r.reason ? <p><span className="num">{r.reason}</span>: {REASON_TEXT[r.reason] || r.reason}</p> : null}
      {r.status !== 'REJECTED' ? (
        <p className="muted num">
          filled {formatInt(r.filledQuantity)} · resting {formatInt(r.restingQuantity)} · seq {formatInt(r.seq)}
        </p>
      ) : null}
      {r.trades.length ? (
        <table className="mini-table">
          <caption className="sr-only">Fills of {r.orderId}</caption>
          <thead>
            <tr><th scope="col">Trade</th><th scope="col" className="num">Price</th><th scope="col" className="num">Qty</th><th scope="col">Against</th></tr>
          </thead>
          <tbody>
            {r.trades.map((t) => (
              <tr key={t.tradeId}>
                <td className="num">#{t.tradeId}</td>
                <td className="num">{formatPrice(t.price, tickSize)}</td>
                <td className="num">{formatInt(t.quantity)}</td>
                <td className="num">{r.side === 'BUY' ? `${t.sellOrderId} (T${t.sellerId})` : `${t.buyOrderId} (T${t.buyerId})`}</td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : null}
    </div>
  );
}
