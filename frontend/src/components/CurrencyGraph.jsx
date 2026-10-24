import { useEffect, useMemo, useRef } from 'react';
import { forceCollide, forceManyBody, forceRadial, forceSimulation } from 'd3-force';
import { select } from 'd3-selection';
import { formatRate } from '../lib/format.js';

const W = 560;
const H = 420;
const R = 20;

/**
 * Settles a force layout for these currencies once and fixes every node there, so the
 * picture never jumps when rates or the cycle change. Deterministic: the same ids give the
 * same positions (nodes start on a circle; d3-force itself has no randomness here).
 */
export function settleLayout(ids) {
  const n = ids.length;
  const nodes = ids.map((id, i) => ({
    id,
    x: W / 2 + Math.cos((2 * Math.PI * i) / n - Math.PI / 2) * 150,
    y: H / 2 + Math.sin((2 * Math.PI * i) / n - Math.PI / 2) * 150,
  }));
  // a ring held by a radial force, spread by repulsion: every node keeps its slot in the
  // list order, so the same currencies always land in the same places
  const sim = forceSimulation(nodes)
    .force('radial', forceRadial(Math.min(W, H) / 2 - R * 2.2, W / 2, H / 2).strength(0.9))
    .force('charge', forceManyBody().strength(-120))
    .force('collide', forceCollide(R * 1.8))
    .stop();
  for (let i = 0; i < 300; i++) sim.tick();
  const pos = {};
  for (const d of nodes) {
    pos[d.id] = { x: Math.max(R + 4, Math.min(W - R - 4, d.x)), y: Math.max(R + 4, Math.min(H - R - 4, d.y)) };
  }
  return pos;
}

/** One undirected line per currency pair with any known rate. */
function pairEdges(edges) {
  const byKey = new Map();
  for (const e of edges) {
    const key = e.from < e.to ? `${e.from}|${e.to}` : `${e.to}|${e.from}`;
    const cur = byKey.get(key) || { a: e.from < e.to ? e.from : e.to, b: e.from < e.to ? e.to : e.from, stale: true, direct: false, rates: [] };
    cur.stale = cur.stale && e.stale;
    cur.direct = cur.direct || e.direct;
    cur.rates.push(`${e.from}→${e.to} ${formatRate(e.rate)}${e.stale ? ' (stale)' : ''}${e.direct ? '' : ' (via USD)'}`);
    byKey.set(key, cur);
  }
  return [...byKey.values()];
}

export default function CurrencyGraph({ graph, cycle }) {
  const ref = useRef(null);
  const ids = useMemo(() => (graph ? graph.nodes.map((n) => n.id) : []), [graph]);
  const idKey = ids.join(',');
  const pos = useMemo(() => (idKey ? settleLayout(idKey.split(',')) : null), [idKey]);

  useEffect(() => {
    if (!pos || !ref.current) return;
    const svg = select(ref.current);
    const lines = pairEdges(graph.edges);

    svg.select('g.edges')
      .selectAll('line')
      .data(lines, (d) => `${d.a}|${d.b}`)
      .join('line')
      .attr('x1', (d) => pos[d.a].x)
      .attr('y1', (d) => pos[d.a].y)
      .attr('x2', (d) => pos[d.b].x)
      .attr('y2', (d) => pos[d.b].y)
      .attr('class', (d) => `edge${d.stale ? ' edge-stale' : ''}${d.direct ? ' edge-direct' : ' edge-derived'}`)
      .selectAll('title')
      .data((d) => [d])
      .join('title')
      .text((d) => d.rates.join('\n'));

    const legs = [];
    if (cycle && cycle.length > 1) {
      for (let i = 0; i + 1 < cycle.length; i++) {
        if (pos[cycle[i]] && pos[cycle[i + 1]]) legs.push({ from: cycle[i], to: cycle[i + 1], i });
      }
    }
    // a leg ends just short of the next node, so its arrowhead stays visible
    const end = (d, k) => {
      const a = pos[d.from];
      const b = pos[d.to];
      const len = Math.hypot(b.x - a.x, b.y - a.y) || 1;
      return b[k] - ((b[k] - a[k]) / len) * (R + 4);
    };
    const drawLegs = (group, cls, marker) => svg.select(group)
      .selectAll('line')
      .data(legs, (d) => `${d.i}:${d.from}>${d.to}`)
      .join('line')
      .attr('class', cls)
      .attr('marker-end', marker)
      .attr('x1', (d) => pos[d.from].x)
      .attr('y1', (d) => pos[d.from].y)
      .attr('x2', (d) => end(d, 'x'))
      .attr('y2', (d) => end(d, 'y'));
    // underneath: a soft glow and a flowing dash (direction of conversion); on top: the legs
    drawLegs('g.cycle-glow', 'cycle-glow', null);
    drawLegs('g.cycle-flow', 'cycle-flow', null);
    drawLegs('g.cycle', 'cycle-leg', 'url(#arrow)');

    const inCycle = new Set(cycle || []);
    const nodes = svg.select('g.nodes')
      .selectAll('g.node')
      .data(graph.nodes, (d) => d.id)
      .join((enter) => {
        const g = enter.append('g').attr('class', 'node');
        g.append('circle').attr('class', 'node-halo').attr('r', R + 5);
        g.append('circle').attr('class', 'node-disc').attr('r', R);
        g.append('text').attr('text-anchor', 'middle').attr('dominant-baseline', 'central');
        g.append('title');
        return g;
      })
      .attr('transform', (d) => `translate(${pos[d.id].x},${pos[d.id].y})`)
      .attr('class', (d) => `node${d.active ? '' : ' node-inactive'}${inCycle.has(d.id) ? ' node-cycle' : ''}`);
    nodes.select('text').text((d) => d.id);
    nodes.select('title').text((d) => `${d.id}: ${d.active ? 'has a known rate' : 'no known rate'}`);
  }, [graph, cycle, pos]);

  if (!graph) return null;
  const cycleText = cycle && cycle.length ? `Detected cycle ${cycle.join(' to ')}.` : 'No cycle.';
  const active = graph.nodes.filter((n) => n.active).map((n) => n.id);
  return (
    <svg
      ref={ref}
      className="graph"
      viewBox={`0 0 ${W} ${H}`}
      role="img"
      aria-label={`Currency graph: ${active.length} of ${graph.nodes.length} currencies have rates (${active.join(', ') || 'none'}). ${cycleText}`}
    >
      <defs>
        <marker id="arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="4.5" markerHeight="4.5" orient="auto-start-reverse">
          <path d="M0,0 L10,5 L0,10 z" className="arrow" />
        </marker>
      </defs>
      <g className="edges" />
      <g className="cycle-glow" aria-hidden="true" />
      <g className="cycle" />
      <g className="cycle-flow" aria-hidden="true" />
      <g className="nodes" />
    </svg>
  );
}
