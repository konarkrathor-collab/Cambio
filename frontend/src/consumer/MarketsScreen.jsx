import { useEffect, useRef, useState } from 'react';
import { selectPair, useStore } from '../app/runtime.js';
import { formatPrice } from '../lib/format.js';
import { comparePairs, currencyName, splitPair } from './currencies.js';
import { changePercent, getHistory, useHistoryVersion } from './history.js';
import { splitBalance, usePracticeAccount } from './practice.js';
import { href } from './route.js';
import Change from './Change.jsx';
import CurrencyChips from './CurrencyChips.jsx';
import Sparkline from './Sparkline.jsx';

function usePairs() {
  const rates = useStore((s) => s.rates);
  const tickers = useStore((s) => s.tickers);
  const tick = new Map((tickers ?? []).map((t) => [t.pair, t.tickSize]));
  return Object.keys(rates)
    .sort(comparePairs)
    .map((pair) => ({ pair, rate: rates[pair].rate, tickSize: tick.get(pair) ?? 0.00001 }));
}

/** The price briefly tints toward gain or loss when it moves, then settles back to ink. */
function Price({ value, tickSize }) {
  const prev = useRef(value);
  const [flash, setFlash] = useState('');
  useEffect(() => {
    if (value === prev.current) return undefined;
    setFlash(value > prev.current ? 'up' : 'down');
    prev.current = value;
    const t = setTimeout(() => setFlash(''), 700);
    return () => clearTimeout(t);
  }, [value]);
  return <span className={`price num ${flash ? `price-${flash}` : ''}`}>{formatPrice(value, tickSize)}</span>;
}

function Row({ pair, rate, tickSize, index }) {
  const { base, quote } = splitPair(pair);
  const h = getHistory(pair);
  const pct = changePercent(h);
  const tone = pct === null || Math.round(pct * 100) === 0 ? 'flat' : pct > 0 ? 'up' : 'down';
  return (
    <li className="mrow" style={{ '--i': index }}>
      <a className="mrow-link" href={href('trade')} onClick={() => selectPair(pair)} aria-label={`Trade ${pair}`}>
        <CurrencyChips base={base} quote={quote} />
        <span className="mrow-name">
          <span className="mrow-pair display">{pair}</span>
          <span className="mrow-sub">{currencyName(base)} to {currencyName(quote)}</span>
        </span>
        <Sparkline points={h?.points} tone={tone} />
        <Price value={rate} tickSize={tickSize} />
        <Change percent={pct} />
      </a>
    </li>
  );
}

function LoopCard() {
  const scan = useStore((s) => s.scan);
  const pairs = useStore((s) => Object.keys(s.rates).length);
  if (scan && scan.hasArbitrage && scan.cycle && scan.cycle.length > 0) {
    return (
      <section className="card loop-card loop-found" aria-label="A loop was found">
        <p className="caption">Loop found</p>
        <p className="loop-path display">{scan.cycle.join(' → ')}</p>
        <p className="loop-note">Possible gain of about <strong className="num">+{scan.profitPercent.toFixed(2)}%</strong> if every step fills. Real markets close these quickly.</p>
        <a className="pill pill-ink" href={href('opportunities')}>See the loop</a>
      </section>
    );
  }
  return (
    <section className="card loop-card" aria-label="Loop finder">
      <p className="caption">Loop finder</p>
      <p className="loop-path display">No loops right now</p>
      <p className="loop-note">Cambio checks every route between currencies{pairs ? ` across ${pairs} pairs` : ''} and tells you the moment one pays back more than it costs.</p>
      <a className="pill pill-quiet" href={href('opportunities')}>How it works</a>
    </section>
  );
}

export default function MarketsScreen() {
  useHistoryVersion();
  const pairs = usePairs();
  const { traderId, balance } = usePracticeAccount();
  const bal = balance === null ? null : splitBalance(balance);
  return (
    <div className="screen markets">
      <div className="hero">
        <section className="card balance-card" aria-label="Practice balance">
          <p className="caption">Practice balance</p>
          <p className="balance num" aria-live="off">
            {bal ? (<><span className="balance-whole display">{bal.whole}</span><span className="balance-cents">{bal.cents}</span></>) : <span className="balance-whole display">—</span>}
          </p>
          <p className="balance-sub">Simulated money · Practice account {traderId}</p>
          <div className="actions">
            <a className="pill pill-ink" href={href('trade')}>Trade</a>
            <a className="pill pill-quiet" href={href('portfolio')}>Portfolio</a>
          </div>
        </section>
        <LoopCard />
      </div>

      <section className="card list-card" aria-labelledby="markets-h">
        <header className="list-head">
          <h1 id="markets-h" className="display">Markets</h1>
          <p className="caption">Change since you opened Cambio</p>
        </header>
        {pairs.length === 0 ? (
          <p className="empty">Waiting for the first prices…</p>
        ) : (
          <ul className="mlist">
            {pairs.map((p, i) => <Row key={p.pair} index={i} {...p} />)}
          </ul>
        )}
      </section>
    </div>
  );
}
