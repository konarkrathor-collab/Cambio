// Display facts about the twelve currencies the backend knows. Names and symbols only; no rates.

export const CURRENCIES = {
  USD: { symbol: '$', name: 'US Dollar' },
  EUR: { symbol: '€', name: 'Euro' },
  GBP: { symbol: '£', name: 'British Pound' },
  JPY: { symbol: '¥', name: 'Japanese Yen' },
  AUD: { symbol: 'A$', name: 'Australian Dollar' },
  CAD: { symbol: 'C$', name: 'Canadian Dollar' },
  CHF: { symbol: 'Fr', name: 'Swiss Franc' },
  INR: { symbol: '₹', name: 'Indian Rupee' },
  CNY: { symbol: '元', name: 'Chinese Yuan' },
  HKD: { symbol: 'HK$', name: 'Hong Kong Dollar' },
  SGD: { symbol: 'S$', name: 'Singapore Dollar' },
  NZD: { symbol: 'NZ$', name: 'New Zealand Dollar' },
};

/** The order a first-time visitor expects: the majors first, then the rest alphabetically. */
const MAJORS = ['EUR/USD', 'GBP/USD', 'USD/JPY', 'USD/CHF', 'AUD/USD', 'USD/CAD', 'NZD/USD', 'EUR/GBP', 'EUR/JPY', 'GBP/JPY'];

export function splitPair(pair) {
  const [base, quote] = pair.split('/');
  return { base, quote };
}

export function currencyName(code) {
  return CURRENCIES[code]?.name ?? code;
}

export function comparePairs(a, b) {
  const ia = MAJORS.indexOf(a);
  const ib = MAJORS.indexOf(b);
  if (ia !== -1 || ib !== -1) return (ia === -1 ? 99 : ia) - (ib === -1 ? 99 : ib);
  return a.localeCompare(b);
}
