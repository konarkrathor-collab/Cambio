import { start } from './app/runtime.js';

// Two front ends share one runtime (store, API client, event stream). The consumer app is the
// default; #/demo is the terminal-style dashboard. Their stylesheets are global, so switching
// between them reloads the page rather than mixing the two.
const isDemo = () => window.location.hash.startsWith('#/demo');
const wantsDemo = isDemo();

start();
if (wantsDemo) import('./demo-entry.jsx').then((m) => m.default());
else import('./consumer/entry.jsx').then((m) => m.default());

window.addEventListener('hashchange', () => {
  if (isDemo() !== wantsDemo) window.location.reload();
});
