// The one store. Stream events are queued and applied in a single reducer pass per animation
// frame, so a burst of events costs one render, not one per event.

import { applyEvents } from './reducers.js';

const defaultSchedule = (fn) =>
  typeof requestAnimationFrame === 'function' ? requestAnimationFrame(fn) : setTimeout(fn, 16);

export function createStore(initial, { schedule = defaultSchedule } = {}) {
  let state = initial;
  let queue = [];
  let scheduled = false;
  const listeners = new Set();

  const notify = () => {
    for (const l of listeners) l();
  };

  const flush = () => {
    scheduled = false;
    if (queue.length === 0) return;
    const batch = queue;
    queue = [];
    const next = applyEvents(state, batch);
    if (next !== state) {
      state = next;
      notify();
    }
  };

  return {
    getState: () => state,
    subscribe(listener) {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
    /** Queues a stream event; applied with the rest of this frame's events. */
    dispatchEvent(event) {
      queue.push(event);
      if (!scheduled) {
        scheduled = true;
        schedule(flush);
      }
    },
    /** Applies a reducer now (snapshots, order responses), after any queued events. */
    update(reducer, ...args) {
      if (queue.length > 0) {
        const batch = queue;
        queue = [];
        state = applyEvents(state, batch);
      }
      const next = reducer(state, ...args);
      if (next !== state) {
        state = next;
        notify();
      }
    },
    flush,
  };
}
