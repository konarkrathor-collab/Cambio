// A rolling record of each pair's recent rates, built in the browser from the RATE_TICK data the
// runtime store already holds. It starts empty when the page opens, so "change" means change since
// the app was opened. Nothing here talks to the backend.

import { useSyncExternalStore } from 'react';
import { store } from '../app/runtime.js';

export const MAX_POINTS = 90;

const histories = new Map(); // pair -> { first, points: number[] }
const listeners = new Set();
let version = 0;
let lastRates = null;

export function record(pair, rate) {
  if (!(rate > 0)) return false;
  let h = histories.get(pair);
  if (!h) {
    h = { first: rate, points: [] };
    histories.set(pair, h);
  }
  if (h.points.length > 0 && h.points[h.points.length - 1] === rate) return false;
  h.points.push(rate);
  if (h.points.length > MAX_POINTS) h.points.shift();
  return true;
}

function onStore() {
  const rates = store.getState().rates;
  if (rates === lastRates) return;
  lastRates = rates;
  let changed = false;
  for (const [pair, v] of Object.entries(rates)) changed = record(pair, v.rate) || changed;
  if (changed) {
    version += 1;
    for (const l of listeners) l();
  }
}

let started = false;
export function startHistory() {
  if (started) return;
  started = true;
  store.subscribe(onStore);
  onStore();
}

export function getHistory(pair) {
  return histories.get(pair) ?? null;
}

/** Percent change from the first rate seen to the latest, or null with fewer than two distinct rates. */
export function changePercent(h) {
  if (!h || h.points.length < 1) return null;
  const last = h.points[h.points.length - 1];
  return ((last - h.first) / h.first) * 100;
}

/** Re-renders when any pair's history changes. */
export function useHistoryVersion() {
  return useSyncExternalStore(
    (l) => {
      listeners.add(l);
      return () => listeners.delete(l);
    },
    () => version,
  );
}
