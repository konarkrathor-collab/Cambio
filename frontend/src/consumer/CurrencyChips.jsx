import { CURRENCIES } from './currencies.js';

/** Two overlapping discs, one per currency: the base in front. Symbols, not flags or emoji. */
export default function CurrencyChips({ base, quote }) {
  const disc = (code, cls) => (
    <span className={`chip ${cls}`} aria-hidden="true">
      {CURRENCIES[code]?.symbol ?? code.slice(0, 1)}
    </span>
  );
  return (
    <span className="chips">
      {disc(quote, 'chip-back')}
      {disc(base, 'chip-front')}
    </span>
  );
}
