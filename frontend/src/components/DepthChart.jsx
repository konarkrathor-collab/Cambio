import { useEffect, useMemo, useRef, useState } from 'react';
import { scaleLinear } from 'd3-scale';
import { area, curveStepAfter, curveStepBefore } from 'd3-shape';
import { useStore } from '../app/runtime.js';
import { depthSeries } from '../lib/depth.js';
import { formatInt, formatPrice } from '../lib/format.js';
import { depthFrame, easeOut, prefersReducedMotion } from '../lib/morph.js';

const W0 = 520; // used until the figure has been measured
const H0 = 148;
const M = { top: 22, right: 34, bottom: 22, left: 52 };
const MORPH_MS = 420; // --dur-slow

/** The frame this book should end at: the series plus padded x domain and y maximum. */
function target(book) {
  const { bids, asks } = depthSeries(book);
  if (bids.length === 0 && asks.length === 0) return null;
  const prices = [...bids, ...asks].map((p) => p.price);
  let lo = Math.min(...prices);
  let hi = Math.max(...prices);
  // a margin of 2 ticks (at least) either side, so the outermost steps are visible
  const pad = Math.max(book.tickSize * 2, (hi - lo) * 0.08);
  lo -= pad;
  hi += pad;
  const maxY = Math.max(1, ...bids.map((p) => p.cumulative), ...asks.map((p) => p.cumulative));
  return { lo, hi, maxY, bids, asks, pair: book.pair };
}

/** Morphs from the frame on screen to `goal` over MORPH_MS (instantly under reduced motion or on a new pair). */
function useMorph(goal) {
  const [frame, setFrame] = useState(goal);
  const shown = useRef(goal);
  useEffect(() => {
    const from = shown.current;
    if (!goal || !from || from.pair !== goal.pair || prefersReducedMotion() || typeof requestAnimationFrame !== 'function') {
      shown.current = goal;
      setFrame(goal);
      return undefined;
    }
    let raf = 0;
    const t0 = performance.now();
    const step = (now) => {
      const t = easeOut((now - t0) / MORPH_MS);
      const f = t >= 1 ? goal : { ...depthFrame(from, goal, t), pair: goal.pair };
      shown.current = f;
      setFrame(f);
      if (t < 1) raf = requestAnimationFrame(step);
    };
    raf = requestAnimationFrame(step);
    return () => cancelAnimationFrame(raf);
  }, [goal]);
  return frame;
}

/** The figure's size in CSS pixels, so the chart draws at 1:1 (axis text stays 10 px at any width). */
function useSize(ref) {
  const [size, setSize] = useState({ w: W0, h: H0 });
  const watched = useRef(null); // { el, ro }: observe each figure element once
  useEffect(() => {
    const el = ref.current;
    if (el === (watched.current && watched.current.el)) return;
    if (watched.current) watched.current.ro.disconnect();
    watched.current = null;
    if (!el || typeof ResizeObserver === 'undefined') return;
    const ro = new ResizeObserver(([e]) => {
      const w = Math.round(e.contentRect.width);
      const h = Math.round(e.contentRect.height);
      if (w > 0 && h > 0) setSize((s) => (s.w === w && s.h === h ? s : { w, h }));
    });
    ro.observe(el);
    watched.current = { el, ro };
  });
  useEffect(() => () => watched.current && watched.current.ro.disconnect(), []);
  return size;
}

/** Cumulative depth as two step areas: bids (left of the spread), asks (right). */
export default function DepthChart() {
  const book = useStore((s) => s.book);
  const box = useRef(null);
  const { w: W, h: H } = useSize(box);
  const goal = useMemo(() => (book ? target(book) : null), [book]);
  const f = useMorph(goal);
  if (!book) return null;
  if (!f) {
    return <p className="panel-state panel-state-empty">No depth to chart yet. It draws as soon as one side of the book has an order.</p>;
  }
  return <DepthFigure book={book} f={f} goal={goal} box={box} W={W} H={H} />;
}

function DepthFigure({ book, f, goal, box, W, H }) {
  const x = scaleLinear().domain([f.lo, f.hi]).range([M.left, W - M.right]);
  const y = scaleLinear().domain([0, f.maxY]).nice().range([H - M.bottom, M.top]);
  // bids: the cumulative at a price holds down to the next lower level
  const bidArea = area().x((p) => x(p.price)).y0(y(0)).y1((p) => y(p.cumulative)).curve(curveStepBefore);
  const askArea = area().x((p) => x(p.price)).y0(y(0)).y1((p) => y(p.cumulative)).curve(curveStepAfter);
  const bids = f.bids;
  const asks = f.asks;
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
  // the summary in the label uses the real book, never a frame in mid-morph
  const real = goal || f;

  return (
    <figure className="depth" ref={box}>
      <svg width={W} height={H} viewBox={`0 0 ${W} ${H}`} role="img" aria-label={`Cumulative depth for ${book.pair}: ${formatInt(real.bids[0]?.cumulative ?? 0)} bid volume, ${formatInt(real.asks[real.asks.length - 1]?.cumulative ?? 0)} ask volume in the top ${Math.max(real.bids.length, real.asks.length)} levels`}>
        {yTicks.map((t) => (
          <g key={`y${t}`}>
            <line x1={M.left} x2={W - M.right} y1={y(t)} y2={y(t)} className="grid" />
            <text x={M.left - 6} y={y(t)} className="axis" textAnchor="end" dominantBaseline="middle">{formatInt(t)}</text>
          </g>
        ))}
        {xTicks.map((t) => (
          <text key={`x${t}`} x={x(t)} y={H - 6} className="axis" textAnchor="middle">{formatPrice(t, book.tickSize)}</text>
        ))}
        {bidPts.length ? <path d={bidArea(bidPts)} className="depth-bid" /> : null}
        {askPts.length ? <path d={askArea(askPts)} className="depth-ask" /> : null}
        <text x={M.left + 2} y={12} className="legend legend-bid">BIDS</text>
        <text x={W - M.right} y={12} className="legend legend-ask" textAnchor="end">ASKS</text>
      </svg>
    </figure>
  );
}
