// Hash routes: no server support needed. #/demo is handled in main.jsx (a different bundle).
import { useSyncExternalStore } from 'react';

export const SCREENS = [
  { id: 'markets', label: 'Markets' },
  { id: 'trade', label: 'Trade' },
  { id: 'portfolio', label: 'Portfolio' },
  { id: 'opportunities', label: 'Opportunities' },
];

export function currentScreen() {
  const id = window.location.hash.replace(/^#\/?/, '').split('/')[0];
  return SCREENS.some((s) => s.id === id) ? id : 'markets';
}

const subscribe = (l) => {
  window.addEventListener('hashchange', l);
  return () => window.removeEventListener('hashchange', l);
};

export const useScreen = () => useSyncExternalStore(subscribe, currentScreen);
export const href = (id) => `#/${id}`;
