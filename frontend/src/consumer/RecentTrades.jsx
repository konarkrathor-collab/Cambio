import { useStore } from '../app/runtime.js';
import { formatInt, formatPrice } from '../lib/format.js';

export default function RecentTrades({ pair, tickSize }) {
  const trades = useStore((s) => s.trades);
  return (
    <section className="card trades-card" aria-label="Recent trades">
      <header className="card-head"><h2 className="display">Recent trades</h2><p className="caption">{pair}</p></header>
      {trades.length === 0 ? (
        <p className="empty">No trades on {pair} yet.</p>
      ) : (
        <ul className="tlist">
          {trades.slice(0, 12).map((t) => (
            <li key={t.tradeId} className="trow">
              <span className={t.takerSide === 'BUY' ? 'trow-side' : 'trow-side'}>{t.takerSide === 'BUY' ? '▲ Buy' : '▼ Sell'}</span>
              <span className="num">{formatInt(t.quantity)}</span>
              <span className="num trow-price">{formatPrice(t.price, tickSize)}</span>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
