import { useState } from 'react';
import { area, curveStepAfter, curveStepBefore, line } from 'd3-shape';
import { scaleLinear } from 'd3-scale';
import { depthSeries } from '../lib/depth.js';
import { formatInt, formatPrice } from '../lib/format.js';
import { getHistory } from './history.js';
import { useWidth } from './useSize.js';

const H = 260;
const PAD = { l: 8, r: 64, t: 32, b: 28 };

function PriceLine({ points, width, tickSize }) {
  if (!points || points.length < 5) {
    return <p className="chart-empty">The line draws as prices move. It needs a few price changes after you open Cambio.</p>;
  }
  const lo = Math.min(...points);
  const hi = Math.max(...points);
  const pad = (hi - lo || lo * 0.0001) * 0.15;
  const x = scaleLinear().domain([0, points.length - 1]).range([PAD.l, width - PAD.r]);
  const y = scaleLinear().domain([lo - pad, hi + pad]).range([H - PAD.b, PAD.t]);
  const d = line().x((_, i) => x(i)).y((p) => y(p))(points);
  const last = points[points.length - 1];
  // three gridlines, but never two with the same label
  const ticks = [hi, (hi + lo) / 2, lo].filter((t, i, all) => all.findIndex((u) => formatPrice(u, tickSize) === formatPrice(t, tickSize)) === i);
  return (
    <svg width={width} height={H} role="img" aria-label={`Price line, ${formatPrice(lo, tickSize)} to ${formatPrice(hi, tickSize)}`}>
      {ticks.map((t) => (
        <g key={t}>
          <line x1={PAD.l} x2={width - PAD.r} y1={y(t)} y2={y(t)} className="grid" />
          <text x={width - PAD.r + 8} y={y(t) + 4} className="axis">{formatPrice(t, tickSize)}</text>
        </g>
      ))}
      <path d={d} fill="none" className="chart-line" />
      <circle cx={x(points.length - 1)} cy={y(last)} r="5" className="chart-dot" />
      <text x={PAD.l} y={H - 8} className="axis">Since you opened Cambio</text>
    </svg>
  );
}

function Depth({ book, width }) {
  const { bids, asks } = depthSeries(book);
  if (bids.length === 0 && asks.length === 0) {
    return <p className="chart-empty">No orders on the book yet, so there is no depth to draw.</p>;
  }
  const prices = [...bids, ...asks].map((p) => p.price);
  const lo = Math.min(...prices);
  const hi = Math.max(...prices);
  // pad both ends so each side runs out to the edge and the real gap between them stays visible
  const pad = (hi - lo || book.tickSize * 4) * 0.35;
  const minP = lo - pad;
  const maxP = hi + pad;
  const maxC = Math.max(...bids.map((b) => b.cumulative), ...asks.map((a) => a.cumulative), 1);
  const x = scaleLinear().domain([minP, maxP]).range([PAD.l, width - PAD.r]);
  const y = scaleLinear().domain([0, maxC * 1.08]).range([H - PAD.b, PAD.t]);
  // buy depth at a price is the volume at or above it (steps to the left of each level);
  // sell depth is the volume at or below it (steps to the right of each level)
  const bidPts = bids.length ? [{ price: minP, cumulative: bids[0].cumulative }, ...bids] : [];
  const askPts = asks.length ? [...asks, { price: maxP, cumulative: asks[asks.length - 1].cumulative }] : [];
  const edge = (pts, curve) => line().curve(curve).x((p) => x(p.price)).y((p) => y(p.cumulative))(pts);
  const fill = (pts, curve) => area().curve(curve).x((p) => x(p.price)).y0(y(0)).y1((p) => y(p.cumulative))(pts);
  const tickSize = book.tickSize;
  const bestBid = bids.length ? bids[bids.length - 1].price : null;
  const bestAsk = asks.length ? asks[0].price : null;
  return (
    <svg width={width} height={H} role="img" aria-label="Depth chart: buy orders on the left, sell orders on the right">
      <line x1={PAD.l} x2={width - PAD.r} y1={y(0)} y2={y(0)} className="grid" />
      {bidPts.length ? <><path d={fill(bidPts, curveStepBefore)} className="depth-bid-fill" /><path d={edge(bidPts, curveStepBefore)} fill="none" className="depth-bid" /></> : null}
      {askPts.length ? <><path d={fill(askPts, curveStepAfter)} className="depth-ask-fill" /><path d={edge(askPts, curveStepAfter)} fill="none" className="depth-ask" /></> : null}
      {bestBid !== null ? <text x={x(bestBid)} y={H - 8} className="axis" textAnchor="end">{formatPrice(bestBid, tickSize)}</text> : null}
      {bestAsk !== null ? <text x={x(bestAsk)} y={H - 8} className="axis">{formatPrice(bestAsk, tickSize)}</text> : null}
      <text x={width - PAD.r + 8} y={y(maxC) + 4} className="axis">{formatInt(maxC)}</text>
      <text x={PAD.l + 8} y={14} className="axis">Buy orders</text>
      <text x={width - PAD.r - 8} y={14} className="axis" textAnchor="end">Sell orders</text>
    </svg>
  );
}

export default function PriceChart({ pair, book, tickSize }) {
  const [mode, setMode] = useState('price');
  const [ref, width] = useWidth();
  const h = getHistory(pair);
  return (
    <section className="card chart-card" aria-label="Chart">
      <div className="chart-head">
        <div className="seg" role="group" aria-label="Chart type">
          {['price', 'depth'].map((m) => (
            <button key={m} type="button" className={mode === m ? 'seg-btn seg-on' : 'seg-btn'} aria-pressed={mode === m} onClick={() => setMode(m)}>
              {m === 'price' ? 'Price' : 'Depth'}
            </button>
          ))}
        </div>
      </div>
      <div ref={ref} className="chart-body">
        {mode === 'price' ? <PriceLine points={h?.points} width={width} tickSize={tickSize} /> : book ? <Depth book={book} width={width} /> : <p className="chart-empty">Loading…</p>}
      </div>
    </section>
  );
}
