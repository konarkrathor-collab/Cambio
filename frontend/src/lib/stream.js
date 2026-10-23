// The one shared EventSource('/api/stream'). It reports its state (connecting, open,
// closed), parses each named event, and calls onOpen on every (re)open so the caller can
// refetch the snapshots: missed events are never replayed (contract §4).

import { EVENT_TYPES } from './reducers.js';

export const RECONNECT_MS = 3000;

export function createStream({
  url = '/api/stream',
  onEvent,
  onState = () => {},
  onOpen = () => {},
  onError = () => {},
  EventSourceImpl = globalThis.EventSource,
  setTimer = (fn, ms) => setTimeout(fn, ms),
  clearTimer = (t) => clearTimeout(t),
} = {}) {
  let es = null;
  let timer = null;
  let stopped = false;
  let state = null;

  const setState = (s) => {
    if (s !== state) {
      state = s;
      onState(s);
    }
  };

  const connect = () => {
    timer = null;
    if (stopped) return;
    setState('connecting');
    es = new EventSourceImpl(url);
    es.onopen = () => {
      setState('open');
      onOpen();
    };
    es.onerror = () => {
      if (stopped) return;
      // The browser retries by itself while readyState is CONNECTING (after `retry:`).
      // A CLOSED source (for example a 503 or a proxy error page) never retries: replace it.
      if (es.readyState === 2) {
        es.close();
        setState('closed');
        if (timer === null) timer = setTimer(connect, RECONNECT_MS);
      } else {
        setState('connecting');
      }
    };
    for (const type of EVENT_TYPES) {
      es.addEventListener(type, (msg) => {
        let data;
        try {
          data = JSON.parse(msg.data);
        } catch {
          onError(new Error(`unparseable ${type} event`));
          return;
        }
        onEvent({ type, id: msg.lastEventId, data });
      });
    }
  };

  connect();
  return {
    state: () => state,
    close() {
      stopped = true;
      if (timer !== null) clearTimer(timer);
      if (es) es.close();
      setState('closed');
    },
  };
}

/** Parses an SSE transcript (as recorded in contract §7) into {id, event, data} frames. */
export function parseSseTranscript(text) {
  const frames = [];
  for (const block of text.split(/\r?\n\r?\n/)) {
    let id = null;
    let event = 'message';
    const data = [];
    let hasField = false;
    for (const line of block.split(/\r?\n/)) {
      if (line === '' || line.startsWith(':')) continue;
      const i = line.indexOf(':');
      const field = i < 0 ? line : line.slice(0, i);
      let value = i < 0 ? '' : line.slice(i + 1);
      if (value.startsWith(' ')) value = value.slice(1);
      if (field === 'id') id = value;
      else if (field === 'event') event = value;
      else if (field === 'data') data.push(value);
      else continue;
      hasField = true;
    }
    if (hasField && data.length > 0) frames.push({ id, event, data: data.join('\n') });
  }
  return frames;
}
