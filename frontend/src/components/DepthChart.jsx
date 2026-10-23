import { scaleLinear } from 'd3-scale';
import { area, curveStepAfter, curveStepBefore } from 'd3-shape';
import { useStore } from '../app/runtime.js';
import { depthSeries } from '../lib/depth.js';
import { formatInt, formatPrice } from '../lib/format.js';

const W = 520;
const H = 132;
const M = { top: 20, right: 34, bottom: 22, left: 52 };

/** Cumulative depth as two step areas: bids (left of the spread), asks (right). */
export default function DepthChart() {
  const book = useStore((s) => s.book);
  if (!book) return null;
  const { bids, asks } = depthSeries(book);
  if (bids.length === 0 && asks.length === 0) {
    return <p className="panel-state panel-state-empty">No depth to chart yet.</p>;
  }
  const prices = [...bids, ...asks].map((p) => p.price);
  let lo = Math.min(...prices);
  let hi = Math.max(...prices);
  // a margin of 2 ticks (at least) either side, so the outermost steps are visible
  const pad = Math.max(book.tickSize * 2, (hi - lo) * 0.08);
  lo -= pad;
  hi += pad;
  const maxY = Math.max(1, ...bids.map((p) => p.cumulative), ...asks.map((p) => p.cumulative));
  const x = scaleLinear().domain([lo, hi]).range([M.left, W - M.right]);
  const y = scaleLinear().domain([0, maxY]).nice().range([H - M.bottom, M.top]);
  // bids: the cumulative at a price holds down to the next lower level
  const bidArea = area().x((p) => x(p.price)).y0(y(0)).y1((p) => y(p.cumulative)).curve(curveStepBefore);
  const askArea = area().x((p) => x(p.price)).y0(y(0)).y1((p) => y(p.cumulative)).curve(curveStepAfter);
  const [x0, x1] = x.domain();
  // the outermost levels' totals hold to the chart's edges, so even one level has an area
  const bidPts = bids.length
    ? [{ price: x0, cumulative: bids[0].cumulative }, ...bids, { price: bids[bids.length - 1].price, cumulative: 0 }]
    : [];
  const askPts = asks.length
    ? [{ price: asks[0].price, cumulative: 0 }, ...asks, { price: x1, cumulative: asks[asks.length - 1].cumulative }]
    : [];
  const xTicks = x.ticks(4);
  const yTicks = y.ticks(4);

  return (
    <figure className="depth">
      <svg viewBox={`0 0 ${W} ${H}`} role="img" aria-label={`Cumulative depth for ${book.pair}: ${formatInt(bids[0]?.cumulative ?? 0)} bid volume, ${formatInt(asks[asks.length - 1]?.cumulative ?? 0)} ask volume in the top ${Math.max(bids.length, asks.length)} levels`}>
        {yTicks.map((t) => (
          <g key={`y${t}`}>
            <line x1={M.left} x2={W - M.right} y1={y(t)} y2={y(t)} className="grid" />
            <text x={M.left - 6} y={y(t)} className="axis" textAnchor="end" dominantBaseline="middle">{formatInt(t)}</text>
          </g>
        ))}
        {xTicks.map((t) => (
          <text key={`x${t}`} x={x(t)} y={H - 8} className="axis" textAnchor="middle">{formatPrice(t, book.tickSize)}</text>
        ))}
        {bidPts.length ? <path d={bidArea(bidPts)} className="depth-bid" /> : null}
        {askPts.length ? <path d={askArea(askPts)} className="depth-ask" /> : null}
        <text x={M.left + 2} y={12} className="legend legend-bid">BIDS</text>
        <text x={W - M.right} y={12} className="legend legend-ask" textAnchor="end">ASKS</text>
      </svg>
    </figure>
  );
}
