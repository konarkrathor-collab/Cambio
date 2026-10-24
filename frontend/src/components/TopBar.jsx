import { useEffect, useState } from 'react';
import { selectPair, useStore } from '../app/runtime.js';
import { sourceBadge } from '../lib/badge.js';
import { formatInt } from '../lib/format.js';
import PairPicker from './PairPicker.jsx';

const STREAM_TEXT = { connecting: 'connecting', open: 'open', closed: 'closed' };

/** Date.now(), refreshed every `ms` while `active`. */
function useNow(active, ms) {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    if (!active) return undefined;
    const t = setInterval(() => setNow(Date.now()), ms);
    return () => clearInterval(t);
  }, [active, ms]);
  return now;
}

/** The mark: a three-leg loop (the loophole) over a ladder of levels (the book). */
function BrandMark() {
  return (
    <svg className="brand-mark" viewBox="0 0 24 24" aria-hidden="true">
      <path className="bars" d="M4 19h6M4 15.5h9M4 12h5" />
      <polyline points="14,5 21,17 9,17 14,5" />
      <circle cx="14" cy="5" r="1.9" />
    </svg>
  );
}

/** A small UTC clock: the time every timestamp in the dashboard is shown in. */
function Clock() {
  const now = useNow(true, 1000);
  return (
    <span className="topbar-item" title="Coordinated Universal Time: every time in the dashboard is UTC">
      <span className="num clock">{new Date(now).toISOString().slice(11, 19)}</span>
      <span className="k">UTC</span>
    </span>
  );
}

export default function TopBar({ tabs }) {
  const health = useStore((s) => s.health);
  const status = useStore((s) => s.status);
  const streamState = useStore((s) => s.streamState);
  const seq = useStore((s) => s.seq);
  const pair = useStore((s) => s.pair);
  const now = useNow(health.up === false, 500);

  const badge = sourceBadge({ backendUp: health.up !== false, status });
  let healthText = 'checking…';
  let healthTone = 'idle';
  if (health.up === true) {
    healthText = 'ok';
    healthTone = 'ok';
  } else if (health.up === false) {
    const secs = Math.max(0, Math.ceil((health.retryAt - now) / 1000));
    healthText = `down · retry in ${secs}s`;
    healthTone = 'down';
  }
  const streamTone = streamState === 'open' ? 'ok' : streamState === 'connecting' ? 'warn' : 'down';

  return (
    <header className="topbar">
      <div className="brand-block">
        <BrandMark />
        <h1 className="brand">Order Matching &amp; Arbitrage Detector</h1>
      </div>
      {tabs}
      <div className="topbar-context">
        <PairPicker value={pair} onSelect={selectPair} />
      </div>
      <div className="topbar-items">
        <span className={`badge badge-${badge.tone}`} title={badge.detail} aria-label={`Data source: ${badge.label}. ${badge.detail}`}>
          <span className="badge-dot" aria-hidden="true" />
          {badge.label}
        </span>
        <span className={`topbar-item state-${healthTone}`} title={health.error || 'GET /api/health every 2 s'}>
          <span className="state-dot" aria-hidden="true" />
          <span className="k">backend</span> <span className={`tone-${healthTone}`}>{healthText}</span>
        </span>
        <span className={`topbar-item state-${streamTone}`} title="GET /api/stream (Server-Sent Events)">
          <span className="state-dot" aria-hidden="true" />
          <span className="k">stream</span> <span className={`tone-${streamTone}`}>{STREAM_TEXT[streamState] || streamState}</span>
        </span>
        <span className="topbar-item" title="The engine's sequence number">
          <span className="k">seq</span> <span className="num">{seq === null ? '—' : formatInt(seq)}</span>
        </span>
        <Clock />
      </div>
      <p className="sr-only" aria-live="polite">
        {health.up === false ? 'The backend is not reachable. Retrying.' : ''}
      </p>
    </header>
  );
}
