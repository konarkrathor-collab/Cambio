import { CURRENCIES } from './currencies.js';

const SIZE = 520;
const C = SIZE / 2;
const R = 196;
const ORDER = Object.keys(CURRENCIES);

function pos(code) {
  const i = Math.max(0, ORDER.indexOf(code));
  const a = (i / ORDER.length) * Math.PI * 2 - Math.PI / 2;
  return { x: C + R * Math.cos(a), y: C + R * Math.sin(a) };
}

/** Shortens a segment so arrowheads end at the edge of the node discs. */
function trim(a, b, by) {
  const dx = b.x - a.x;
  const dy = b.y - a.y;
  const len = Math.hypot(dx, dy) || 1;
  return { x1: a.x + (dx / len) * by, y1: a.y + (dy / len) * by, x2: b.x - (dx / len) * by, y2: b.y - (dy / len) * by };
}

export default function LoopMap({ graph, cycle }) {
  const active = new Set((graph?.nodes ?? []).filter((n) => n.active).map((n) => n.id));
  const seen = new Set();
  const lines = [];
  for (const e of graph?.edges ?? []) {
    if (!e.direct || e.stale) continue;
    const key = [e.from, e.to].sort().join('-');
    if (seen.has(key)) continue;
    seen.add(key);
    lines.push(e);
  }
  const legs = cycle && cycle.length > 2 ? cycle.slice(0, -1).map((from, i) => ({ from, to: cycle[i + 1] })) : [];
  const inCycle = new Set(cycle ?? []);
  return (
    <svg className="loopmap" viewBox={`0 0 ${SIZE} ${SIZE}`} role="img" aria-label={cycle?.length ? `Currency map with the loop ${cycle.join(' to ')} highlighted` : 'Currency map, no loop right now'}>
      <defs>
        <marker id="arrow" viewBox="0 0 10 10" refX="8" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
          <path d="M1 1l8 4-8 4z" fill="var(--ink)" />
        </marker>
      </defs>
      {lines.map((e) => {
        const a = pos(e.from);
        const b = pos(e.to);
        return <line key={`${e.from}-${e.to}`} x1={a.x} y1={a.y} x2={b.x} y2={b.y} className="map-edge" />;
      })}
      {legs.map((l) => {
        const t = trim(pos(l.from), pos(l.to), 26);
        return <line key={`${cycle.join('')}-${l.from}-${l.to}`} {...t} className="map-leg" pathLength="1" markerEnd="url(#arrow)" />;
      })}
      {ORDER.map((code) => {
        const p = pos(code);
        const on = active.has(code);
        const hot = inCycle.has(code);
        return (
          <g key={code} className={`map-node${on ? '' : ' map-off'}${hot ? ' map-hot' : ''}`} transform={`translate(${p.x} ${p.y})`}>
            <circle r="24" />
            <text y="5" textAnchor="middle" className="map-code">{code}</text>
          </g>
        );
      })}
    </svg>
  );
}
