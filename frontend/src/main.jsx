import { start } from './app/runtime.js';

// Two front ends share one runtime (store, API client, event stream). The consumer app is the
// default; #/demo is the terminal-style dashboard. Their stylesheets are global, so switching
// between them reloads the page rather than mixing the two.
const isDemo = () => window.location.hash.startsWith('#/demo');
const wantsDemo = isDemo();

// Both loaders are plain arrow functions so the bundler treats the two imports alike and preloads
// each chunk's stylesheet with it (an import written as a bare statement lost its CSS in the build).
const loaders = {
  demo: () => import('./demo-entry.jsx'),
  cambio: () => import('./consumer/entry.jsx'),
};

start();
loaders[wantsDemo ? 'demo' : 'cambio']().then((m) => m.default());

window.addEventListener('hashchange', () => {
  if (isDemo() !== wantsDemo) window.location.reload();
});
