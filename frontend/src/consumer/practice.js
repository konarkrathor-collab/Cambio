// The practice account: assigned automatically, remembered in this browser, no sign-up.
// Account amounts are simulation units (tick × quantity); one price unit is 1e5 ticks, so
// dividing by 1e5 gives the balance in the quote currency's units a person can read.

import { useStore } from '../app/runtime.js';

const KEY = 'cambio-trader';
const DEFAULT_TRADER = 10;
export const UNITS_PER_BALANCE = 1e5;

export function practiceTraderId() {
  try {
    const saved = Number(window.localStorage.getItem(KEY));
    if (Number.isInteger(saved) && saved >= 1 && saved <= 11) return saved;
    window.localStorage.setItem(KEY, String(DEFAULT_TRADER));
  } catch {
    // storage unavailable: the default account is used for this visit
  }
  return DEFAULT_TRADER;
}

export function usePracticeAccount() {
  const accounts = useStore((s) => s.accounts);
  const traderId = practiceTraderId();
  const a = accounts ? accounts.find((x) => x.traderId === traderId) : null;
  return { traderId, balance: a ? a.cash / UNITS_PER_BALANCE : null };
}

/** 50000 -> { whole: '50,000', cents: '.00' } */
export function splitBalance(balance) {
  const [w, c] = Math.abs(balance).toFixed(2).split('.');
  return { whole: `${balance < 0 ? '-' : ''}${Number(w).toLocaleString('en-US')}`, cents: `.${c}` };
}
