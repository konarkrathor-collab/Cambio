import { useEffect, useId, useRef, useState } from 'react';
import { api } from '../lib/api.js';
import { formatTickSize, normalizePair } from '../lib/format.js';
import { pairOnEnter, visibleItems } from '../lib/picker.js';
import { describeError, useStore } from '../app/runtime.js';

export const DEBOUNCE_MS = 150;

/**
 * Pair search over the Trie (GET /api/tickers?prefix=), debounced, keyboard driven. The
 * suggestions belong to the prefix they were fetched for: until the current text's answer
 * arrives they are hidden and cannot be chosen, so a fast "type, Enter" never picks the
 * previous query's pair. Enter on a complete listed pair chooses it at once.
 */
export default function PairPicker({ value, onSelect }) {
  const tickers = useStore((s) => s.tickers);
  const [text, setText] = useState(value);
  const [open, setOpen] = useState(false);
  const [result, setResult] = useState({ prefix: null, items: [] });
  const [active, setActive] = useState(-1);
  const [error, setError] = useState(null);
  const listId = useId();
  const seqRef = useRef(0);

  useEffect(() => setText(value), [value]);

  useEffect(() => {
    if (!open) return undefined;
    const prefix = normalizePair(text);
    const mySeq = ++seqRef.current;
    const t = setTimeout(async () => {
      try {
        const r = await api.tickers(prefix);
        if (mySeq !== seqRef.current) return; // a newer query is on its way
        setResult({ prefix, items: r.tickers.slice(0, 12) });
        setActive(r.tickers.length ? 0 : -1);
        setError(null);
      } catch (e) {
        if (mySeq === seqRef.current) setError(describeError(e));
      }
    }, DEBOUNCE_MS);
    return () => clearTimeout(t);
  }, [text, open]);

  const choose = (pair) => {
    setOpen(false);
    setText(pair);
    onSelect(pair);
  };

  const items = visibleItems(text, result);

  const onKeyDown = (e) => {
    if (e.key === 'ArrowDown') {
      e.preventDefault();
      setOpen(true);
      setActive((a) => Math.min(a + 1, items.length - 1));
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      setActive((a) => Math.max(a - 1, 0));
    } else if (e.key === 'Enter') {
      e.preventDefault();
      const pair = pairOnEnter(text, result, open ? active : -1, tickers);
      if (pair) choose(pair);
    } else if (e.key === 'Escape') {
      setOpen(false);
      setText(value);
    }
  };

  const optionId = (i) => `${listId}-opt-${i}`;
  return (
    <div className="picker">
      <label htmlFor={`${listId}-input`} className="field-label">Pair</label>
      <input
        id={`${listId}-input`}
        className="input num"
        role="combobox"
        aria-expanded={open && items.length > 0}
        aria-controls={listId}
        aria-autocomplete="list"
        aria-activedescendant={open && items[active] ? optionId(active) : undefined}
        autoComplete="off"
        spellCheck={false}
        value={text}
        onChange={(e) => {
          setText(e.target.value);
          setOpen(true);
        }}
        onFocus={(e) => {
          e.target.select();
          setOpen(true);
        }}
        onBlur={() => setTimeout(() => setOpen(false), 120)}
        onKeyDown={onKeyDown}
      />
      {open && (items.length > 0 || error) ? (
        <ul id={listId} role="listbox" className="picker-list">
          {error ? <li className="picker-error" role="option" aria-selected="false" aria-disabled="true">{error}</li> : null}
          {items.map((t, i) => (
            <li
              key={t.pair}
              id={optionId(i)}
              role="option"
              aria-selected={i === active}
              className={i === active ? 'picker-item active' : 'picker-item'}
              onMouseDown={(e) => {
                e.preventDefault();
                choose(t.pair);
              }}
              onMouseEnter={() => setActive(i)}
            >
              <span className="num">{t.pair}</span>
              <span className="muted num">tick {formatTickSize(t.tickSize)}</span>
            </li>
          ))}
        </ul>
      ) : null}
    </div>
  );
}
