import { useState } from 'react';
import { cancelOrder, describeError, useStore } from '../app/runtime.js';
import { formatInt, formatPrice } from '../lib/format.js';
import { PanelState } from './Panel.jsx';

/** Orders this browser session placed that still rest, with cancel. */
export default function MyOrders() {
  const orders = useStore((s) => s.myOrders);
  const tickers = useStore((s) => s.tickers);
  const [message, setMessage] = useState(null);
  const [busy, setBusy] = useState(null);
  const tick = (pair) => (tickers || []).find((t) => t.pair === pair)?.tickSize ?? 0.00001;

  const cancel = async (id) => {
    setBusy(id);
    try {
      const r = await cancelOrder(id);
      setMessage({ tone: 'ok', text: `${r.orderId} cancelled: ${formatInt(r.remainingQuantity)} removed from the book.` });
    } catch (e) {
      const text = e.code === 'ORDER_NOT_FOUND' ? `${id} no longer rests (filled or already cancelled).` : describeError(e);
      setMessage({ tone: 'error', text });
    } finally {
      setBusy(null);
    }
  };

  return (
    <div>
      {orders.length === 0 ? (
        <PanelState kind="empty">No resting orders from this session. Orders you place that rest appear here.</PanelState>
      ) : (
        <div className="scroll-mine">
        <table className="mini-table">
          <caption className="sr-only">Resting orders placed in this session</caption>
          <thead>
            <tr><th scope="col">Order</th><th scope="col">Pair</th><th scope="col">Side</th><th scope="col" className="num">Price</th><th scope="col" className="num">Remaining</th><th scope="col"><span className="sr-only">Action</span></th></tr>
          </thead>
          <tbody>
            {orders.map((o) => (
              <tr key={o.orderId}>
                <td className="num">{o.orderId}</td>
                <td className="num">{o.pair}</td>
                <td className={o.side === 'BUY' ? 'tone-bid' : 'tone-ask'}>{o.side}</td>
                <td className="num">{formatPrice(o.price, tick(o.pair))}</td>
                <td className="num">{formatInt(o.remaining)} / {formatInt(o.quantity)}</td>
                <td>
                  <button type="button" className="btn btn-small" onClick={() => cancel(o.orderId)} disabled={busy === o.orderId} aria-label={`Cancel ${o.orderId}`}>
                    {busy === o.orderId ? '…' : 'Cancel'}
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        </div>
      )}
      <p aria-live="polite" className={message ? `note tone-${message.tone}` : 'note'}>{message ? message.text : ''}</p>
    </div>
  );
}
