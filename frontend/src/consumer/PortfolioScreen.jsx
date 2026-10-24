import { useEffect, useState } from 'react';
import { useStore } from '../app/runtime.js';
import { api } from '../lib/api.js';
import { formatInt, formatPrice } from '../lib/format.js';
import { splitBalance, usePracticeAccount, UNITS_PER_BALANCE } from './practice.js';
import OpenOrders from './OpenOrders.jsx';

const STARTING_BALANCE = 50_000; // the demo accounts start with 5,000,000,000 simulation units
const money = (n) => n.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
const signed = (n) => `${n >= 0 ? '+' : '−'}${money(Math.abs(n))}`;

/** The trades this account took part in, newest first: the server's last 500 plus any seen live. */
function useMyFills(traderId) {
  const live = useStore((s) => s.recentFills);
  const [fetched, setFetched] = useState([]);
  const [state, setState] = useState('loading');
  useEffect(() => {
    let off = false;
    api.trades('', 500).then(
      (r) => { if (!off) { setFetched(r.trades ?? []); setState('ready'); } },
      () => { if (!off) setState('error'); },
    );
    return () => { off = true; };
  }, []);
  const byId = new Map();
  for (const t of [...fetched, ...live]) byId.set(t.tradeId, t);
  const mine = [...byId.values()].filter((t) => t.buyerId === traderId || t.sellerId === traderId);
  mine.sort((a, b) => b.tradeId - a.tradeId);
  return { fills: mine, state };
}

function Fills({ traderId }) {
  const tickers = useStore((s) => s.tickers);
  const { fills, state } = useMyFills(traderId);
  const tick = (pair) => (tickers ?? []).find((t) => t.pair === pair)?.tickSize ?? 0.00001;
  return (
    <section className="card fills-card" aria-label="Your trades">
      <header className="card-head"><h2 className="display">Your trades</h2><p className="caption">Most recent first</p></header>
      {state === 'error' ? <p className="empty">Could not load your trades. They will appear as you trade.</p> : null}
      {state !== 'error' && fills.length === 0 ? <p className="empty">{state === 'loading' ? 'Loading…' : 'You have not traded yet. Go to Trade to place your first order.'}</p> : null}
      {fills.length > 0 ? (
        <ul className="flist">
          {fills.slice(0, 20).map((t) => {
            const bought = t.buyerId === traderId;
            const cash = (bought ? -1 : 1) * t.quantity * t.price;
            return (
              <li key={t.tradeId} className="frow">
                <span className="frow-main">
                  <strong>{bought ? 'Bought' : 'Sold'} {formatInt(t.quantity)}</strong> {t.pair}
                  <span className="frow-sub num"> at {formatPrice(t.price, tick(t.pair))}</span>
                </span>
                <span className="num frow-cash">{signed(cash)}</span>
              </li>
            );
          })}
        </ul>
      ) : null}
    </section>
  );
}

export default function PortfolioScreen() {
  const { traderId, balance } = usePracticeAccount();
  const account = useStore((s) => (s.accounts ? s.accounts.find((a) => a.traderId === traderId) : null));
  const bal = balance === null ? null : splitBalance(balance);
  const change = balance === null ? null : balance - STARTING_BALANCE;
  const limit = account ? account.marginLimit / UNITS_PER_BALANCE : null;
  return (
    <div className="screen portfolio">
      <h1 className="visually-hidden">Portfolio</h1>
      <section className="card pf-head" aria-label="Practice balance">
        <div>
          <p className="caption">Practice balance</p>
          <p className="balance num">
            {bal ? (<><span className="balance-whole display">{bal.whole}</span><span className="balance-cents">{bal.cents}</span></>) : <span className="balance-whole display">—</span>}
          </p>
          <p className="balance-sub">Simulated money · Practice account {traderId}</p>
        </div>
        <dl className="stats pf-stats">
          <div><dt>Since you started</dt><dd className="num">{change === null ? '—' : `${signed(change)}`}</dd></div>
          <div><dt>Starting balance</dt><dd className="num">{money(STARTING_BALANCE)}</dd></div>
          <div><dt>Most in one order</dt><dd className="num">{limit === null ? '—' : money(limit)}</dd></div>
        </dl>
      </section>
      <div className="pf-grid">
        <OpenOrders />
        <Fills traderId={traderId} />
      </div>
    </div>
  );
}
