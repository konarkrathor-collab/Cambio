import { line } from 'd3-shape';
import { scaleLinear } from 'd3-scale';

/** A tiny line of recent rates. Decorative: the price and change beside it carry the meaning. */
export default function Sparkline({ points, width = 112, height = 36, tone = 'flat' }) {
  if (!points || points.length < 2) {
    return <svg className="spark" width={width} height={height} aria-hidden="true"><line x1="0" x2={width} y1={height / 2} y2={height / 2} className="spark-empty" /></svg>;
  }
  const lo = Math.min(...points);
  const hi = Math.max(...points);
  const x = scaleLinear().domain([0, points.length - 1]).range([2, width - 2]);
  const y = scaleLinear().domain([lo, hi === lo ? lo + 1 : hi]).range([height - 4, 4]);
  const d = line().x((_, i) => x(i)).y((p) => y(p))(points);
  const last = points.length - 1;
  return (
    <svg className={`spark spark-${tone}`} width={width} height={height} aria-hidden="true">
      <path d={d} fill="none" strokeWidth="2" strokeLinejoin="round" strokeLinecap="round" />
      <circle cx={x(last)} cy={y(points[last])} r="3" />
    </svg>
  );
}
