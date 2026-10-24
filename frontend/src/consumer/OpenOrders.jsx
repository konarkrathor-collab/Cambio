import { useState } from 'react';
import { cancelOrder, describeError, useStore } from '../app/runtime.js';
import { formatInt, formatPrice } from '../lib/format.js';

export default function OpenOrders() {
  const orders = useStore((s) => s.myOrders);
  const tickers = useStore((s) => s.tickers);
  const [busy, setBusy] = useState(null);
  const [note, setNote] = useState(null);
  const tick = (pair) => (tickers ?? []).find((t) => t.pair === pair)?.tickSize ?? 0.00001;

  const cancel = async (id) => {
    setBusy(id);
    try {
      await cancelOrder(id);
      setNote('Order cancelled.');
    } catch (e) {
      setNote(e.code === 'ORDER_NOT_FOUND' ? 'That order was already filled or cancelled.' : describeError(e));
    } finally {
      setBusy(null);
    }
  };

  return (
    <section className="card orders-card" aria-label="Your open orders">
      <header className="card-head"><h2 className="display">Your open orders</h2></header>
      {orders.length === 0 ? (
        <p className="empty">Orders that are waiting on the book show up here, with a button to cancel.</p>
      ) : (
        <ul className="olist">
          {orders.map((o) => (
            <li key={o.orderId} className="orow">
              <span className="orow-main">
                <strong>{o.side === 'BUY' ? 'Buy' : 'Sell'} {formatInt(o.remaining)}</strong> {o.pair}
                <span className="orow-sub num"> at {formatPrice(o.price, tick(o.pair))}{o.remaining < o.quantity ? ` · ${formatInt(o.quantity - o.remaining)} done` : ''}</span>
              </span>
              <button type="button" className="pill pill-quiet pill-small" onClick={() => cancel(o.orderId)} disabled={busy === o.orderId} aria-label={`Cancel ${o.side === 'BUY' ? 'buy' : 'sell'} order ${o.orderId}, final`}>
                {busy === o.orderId ? '…' : 'Cancel'}
              </button>
            </li>
          ))}
        </ul>
      )}
      {note ? <p className="caption" role="status">{note} Cancelling is final.</p> : null}
    </section>
  );
}
