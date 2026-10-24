/** A signed change with an arrow and a word for screen readers: colour is never the only signal. */
export default function Change({ percent }) {
  if (percent === null || percent === undefined) return <span className="change change-flat">—</span>;
  const rounded = Math.round(percent * 1000) / 1000;
  if (rounded === 0) return <span className="change change-flat"><span aria-hidden="true">●</span> 0.000%</span>;
  const up = rounded > 0;
  return (
    <span className={`change ${up ? 'change-up' : 'change-down'}`}>
      <span aria-hidden="true">{up ? '▲' : '▼'}</span>
      {`${up ? '+' : '−'}${Math.abs(rounded).toFixed(3)}%`}
      <span className="visually-hidden">{up ? ' up' : ' down'}</span>
    </span>
  );
}
