// The pair picker's Enter key. Suggestions answer one prefix; while the typed text's own
// answer has not arrived they must not be chosen (a fast "type, Enter" would otherwise pick
// the previous query's pair). A complete listed pair is chosen directly.

import { normalizePair } from './format.js';

/** The suggestions to show for the current text: only those fetched for it. */
export function visibleItems(text, result) {
  return result.prefix === normalizePair(text) ? result.items : [];
}

/** The pair Enter selects, or null to wait. */
export function pairOnEnter(text, result, active, tickers) {
  const items = visibleItems(text, result);
  if (active >= 0 && items[active]) return items[active].pair;
  const prefix = normalizePair(text);
  const exact = (tickers || []).find((t) => t.pair === prefix);
  return exact ? exact.pair : null;
}
