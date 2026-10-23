// The data-source badge (doc 08 Stage 5): what the rates behind the scanner are, from
// GET /api/market/rates/status. Only fresh, non-replay live data is shown as LIVE in green;
// fallback, replay and stale data are amber, and a demo injection (replay: true) is REPLAY.

export function sourceBadge({ backendUp, status }) {
  if (!backendUp) {
    return { label: 'DISCONNECTED', tone: 'down', detail: 'The backend is not reachable.' };
  }
  if (!status) return { label: 'WAITING', tone: 'idle', detail: 'No feed status yet.' };
  const stale = Array.isArray(status.staleSymbols) ? status.staleSymbols.length : 0;
  if (status.replay) {
    return {
      label: 'REPLAY',
      tone: 'warn',
      detail: 'Recorded or injected ticks (replay), not the live feed.',
    };
  }
  switch (status.state) {
    case 'live':
      if (stale > 0) {
        return { label: 'LIVE · STALE', tone: 'warn', detail: `${stale} symbol${stale === 1 ? '' : 's'} stale (no tick for 30 s).` };
      }
      return { label: 'LIVE', tone: 'ok', detail: 'Live ticks from the Finnhub feed.' };
    case 'fallback':
      return {
        label: 'FALLBACK',
        tone: 'warn',
        detail: `Frankfurter daily snapshot${status.fallbackAsOf ? ` as of ${status.fallbackAsOf}` : ''}, not live prices.`,
      };
    case 'connected, no ticks':
      return { label: 'WAITING', tone: 'idle', detail: 'The feed is connected but no ticks have arrived (markets may be closed).' };
    case 'waiting':
      return { label: 'WAITING', tone: 'idle', detail: 'No feed data has arrived yet.' };
    case 'disconnected':
      return {
        label: 'DISCONNECTED',
        tone: 'down',
        detail: status.source === 'cached' ? 'The feed is disconnected; the scanner holds cached rates.' : 'The feed is disconnected.',
      };
    default:
      return { label: String(status.state || 'UNKNOWN').toUpperCase(), tone: 'warn', detail: 'Unrecognised feed state.' };
  }
}
