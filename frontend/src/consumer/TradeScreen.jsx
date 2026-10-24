import { selectPair, useStore } from '../app/runtime.js';
import { formatPrice } from '../lib/format.js';
import { spread } from '../lib/depth.js';
import { comparePairs, splitPair } from './currencies.js';
import { changePercent, getHistory, useHistoryVersion } from './history.js';
import Change from './Change.jsx';
import CurrencyChips from './CurrencyChips.jsx';
import Ladder from './Ladder.jsx';
import OpenOrders from './OpenOrders.jsx';
import PriceChart from './PriceChart.jsx';
import RecentTrades from './RecentTrades.jsx';
import Ticket from './Ticket.jsx';

export default function TradeScreen() {
  useHistoryVersion();
  const pair = useStore((s) => s.pair);
  const rates = useStore((s) => s.rates);
  const book = useStore((s) => s.book);
  const tickers = useStore((s) => s.tickers);
  const { base, quote } = splitPair(pair);
  const tickSize = tickers?.find((t) => t.pair === pair)?.tickSize ?? book?.tickSize ?? 0.00001;
  const options = Array.from(new Set([pair, ...Object.keys(rates)])).sort(comparePairs);
  const rate = rates[pair]?.rate ?? book?.lastTradePrice ?? null;
  const pct = changePercent(getHistory(pair));
  const sp = book && book.pair === pair ? spread(book) : null;
  const bestBuy = book?.bids?.[0]?.price ?? null;
  const bestSell = book?.asks?.[0]?.price ?? null;

  return (
    <div className="screen trade">
      <h1 className="visually-hidden">Trade {pair}</h1>
      <header className="card trade-head">
        <div className="trade-pick">
          <CurrencyChips base={base} quote={quote} />
          <label className="pair-select">
            <span className="visually-hidden">Currency pair</span>
            <select value={pair} onChange={(e) => selectPair(e.target.value)}>
              {options.map((p) => <option key={p} value={p}>{p}</option>)}
            </select>
          </label>
        </div>
        <div className="trade-price">
          <span className="price-big display num">{rate ? formatPrice(rate, tickSize) : '—'}</span>
          <Change percent={pct} />
        </div>
        <dl className="stats">
          <div><dt>Best buy price</dt><dd className="num">{bestBuy !== null ? formatPrice(bestBuy, tickSize) : '—'}</dd></div>
          <div><dt>Best sell price</dt><dd className="num">{bestSell !== null ? formatPrice(bestSell, tickSize) : '—'}</dd></div>
          <div><dt>Gap</dt><dd className="num">{sp?.text ?? '—'}</dd></div>
        </dl>
      </header>
      <div className="trade-main">
        <div className="trade-left">
          <PriceChart pair={pair} book={book} tickSize={tickSize} />
          <div className="two-up">
            <Ladder pair={pair} book={book} tickSize={tickSize} />
            <RecentTrades pair={pair} tickSize={tickSize} />
          </div>
        </div>
        <div className="trade-right">
          <Ticket key={pair} pair={pair} tickSize={tickSize} />
          <OpenOrders />
        </div>
      </div>
    </div>
  );
}
