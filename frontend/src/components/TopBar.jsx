import { useEffect, useState } from 'react';
import { useStore } from '../app/runtime.js';
import { sourceBadge } from '../lib/badge.js';
import { formatInt } from '../lib/format.js';

const STREAM_TEXT = { connecting: 'connecting', open: 'open', closed: 'closed' };

function useNow(active) {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    if (!active) return undefined;
    const t = setInterval(() => setNow(Date.now()), 500);
    return () => clearInterval(t);
  }, [active]);
  return now;
}

export default function TopBar() {
  const health = useStore((s) => s.health);
  const status = useStore((s) => s.status);
  const streamState = useStore((s) => s.streamState);
  const seq = useStore((s) => s.seq);
  const now = useNow(health.up === false);

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
      <h1 className="brand">Order Matching &amp; Arbitrage Detector</h1>
      <div className="topbar-items">
        <span className={`badge badge-${badge.tone}`} title={badge.detail} aria-label={`Data source: ${badge.label}. ${badge.detail}`}>
          <span className="badge-dot" aria-hidden="true" />
          {badge.label}
        </span>
        <span className="topbar-item" title={health.error || 'GET /api/health every 2 s'}>
          <span className="muted">backend</span> <span className={`tone-${healthTone}`}>{healthText}</span>
        </span>
        <span className="topbar-item" title="GET /api/stream (Server-Sent Events)">
          <span className="muted">stream</span> <span className={`tone-${streamTone}`}>{STREAM_TEXT[streamState] || streamState}</span>
        </span>
        <span className="topbar-item" title="The engine's sequence number">
          <span className="muted">seq</span> <span className="num">{seq === null ? '—' : formatInt(seq)}</span>
        </span>
      </div>
      <p className="sr-only" aria-live="polite">
        {health.up === false ? 'The backend is not reachable. Retrying.' : ''}
      </p>
    </header>
  );
}
