import { useStore } from '../app/runtime.js';
import { formatInt, formatPrice } from '../lib/format.js';
import { PanelState } from './Panel.jsx';

/** History from GET /api/trades plus live TRADE events, newest first, at most 100. */
export default function TradesFeed() {
  const trades = useStore((s) => s.trades);
  const book = useStore((s) => s.book);
  const pair = useStore((s) => s.pair);
  const error = useStore((s) => s.errors.trades);
  const tickSize = book?.tickSize ?? (pair.endsWith('/JPY') ? 0.001 : 0.00001);
  if (trades.length === 0) {
    return error ? <PanelState kind="error">Trades unavailable: {error}</PanelState> : <PanelState kind="empty">No trades on {pair} in this server session yet.</PanelState>;
  }
  return (
    <div className="scroll">
      <table className="mini-table trades">
        <caption className="sr-only">Recent trades on {pair}, newest first</caption>
        <thead>
          <tr><th scope="col">#</th><th scope="col">Taker</th><th scope="col" className="num">Price</th><th scope="col" className="num">Qty</th><th scope="col">Buyer ← Seller</th><th scope="col" className="num">seq</th></tr>
        </thead>
        <tbody>
          {trades.map((t) => (
            <tr key={t.tradeId}>
              <td className="num">{t.tradeId}</td>
              <td className={t.takerSide === 'BUY' ? 'tone-bid' : 'tone-ask'}>{t.takerSide}</td>
              <td className="num">{formatPrice(t.price, tickSize)}</td>
              <td className="num">{formatInt(t.quantity)}</td>
              <td className="num">T{t.buyerId} ← T{t.sellerId}</td>
              <td className="num muted">{formatInt(t.seq)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
