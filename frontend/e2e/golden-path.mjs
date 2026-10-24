#!/usr/bin/env node
// Dashboard golden path in a real (headless) Chromium, against the real backend.
// Regression tooling for demo day: NOT part of `npm test`, and Playwright stays out of
// package.json. It uses an already-installed Playwright (global, or anywhere Node finds it):
//
//   npm install -g playwright && npx playwright install chromium   # once, if you have neither
//   node frontend/e2e/golden-path.mjs              # against `npm run dev` (port 5173)
//   node frontend/e2e/golden-path.mjs --preview    # against the production build (port 4173)
//   SHOTS=/tmp/shots node frontend/e2e/golden-path.mjs   # also keep screenshots
//
// It starts a seeded backend on 127.0.0.1:8080 with a temporary data directory and the
// dashboard server itself (ports 8080 and 5173/4173 must be free), walks the golden path,
// kills and restarts the backend for the outage check, and stops everything at the end.
// Exit code 0 only if every step passed.

import { spawn, execSync } from 'node:child_process';
import { createRequire } from 'node:module';
import { mkdtempSync, rmSync, mkdirSync, existsSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, '..', '..');
const preview = process.argv.includes('--preview');
const BASE = preview ? 'http://127.0.0.1:4173' : 'http://127.0.0.1:5173';
const SHOTS = process.env.SHOTS || null;
if (SHOTS) mkdirSync(SHOTS, { recursive: true });

// ---------------------------------------------------------------- Playwright, from outside package.json
async function loadPlaywright() {
  try {
    return await import('playwright');
  } catch {
    // ES modules ignore NODE_PATH and global installs: look in npm's global root
    const globalRoot = execSync('npm root -g', { encoding: 'utf8' }).trim();
    const req = createRequire(join(globalRoot, 'noop.js'));
    return req('playwright');
  }
}

// ---------------------------------------------------------------- processes
const children = [];
function start(cmd, args, opts = {}) {
  const p = spawn(cmd, args, { cwd: opts.cwd || root, detached: true, stdio: ['ignore', 'ignore', 'ignore'] });
  children.push(p);
  return p;
}
function stop(p, signal = 'SIGTERM') {
  try {
    process.kill(-p.pid, signal); // the whole process group (npx -> node -> vite)
  } catch {
    // already gone
  }
}
async function waitFor(url, ms = 30000) {
  const end = Date.now() + ms;
  while (Date.now() < end) {
    try {
      const r = await fetch(url);
      if (r.ok) return true;
    } catch {
      // not up yet
    }
    await new Promise((r) => setTimeout(r, 200));
  }
  throw new Error(`${url} did not come up within ${ms} ms`);
}

const dataDir = mkdtempSync(join(tmpdir(), 'ads-golden-'));
const startBackend = () =>
  start(join(root, 'backend', 'run.sh'), ['serve', '--port', '8080', '--data-dir', dataDir, '--seed-demo-accounts']);

// ---------------------------------------------------------------- steps
const results = [];
let phase = 'normal';
function step(name, ok, detail = '') {
  results.push({ name, ok, detail });
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? `  (${detail})` : ''}`);
}
const squash = (s) => s.replace(/\s+/g, ' ').trim();

async function main() {
  try {
    await fetch('http://127.0.0.1:8080/api/health');
    throw new Error('port 8080 is in use: stop the running backend first');
  } catch (e) {
    if (e.message.startsWith('port 8080')) throw e;
  }
  execSync(join(root, 'backend', 'build.sh'), { stdio: 'ignore' });
  let backend = startBackend();
  if (preview) execSync('npx vite build', { cwd: join(root, 'frontend'), stdio: 'ignore' });
  start('npx', preview ? ['vite', 'preview'] : ['vite', '--strictPort'], { cwd: join(root, 'frontend') });
  await waitFor('http://127.0.0.1:8080/api/health');
  await waitFor(BASE);

  const { chromium } = await loadPlaywright();
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 1280, height: 720 } });
  const consoleLines = [];
  const tooling = /^\[vite\]|React DevTools/; // the dev server's and React's own lines, not the app's
  page.on('console', (m) => { if (!tooling.test(m.text())) consoleLines.push({ phase, type: m.type(), text: m.text() }); });
  page.on('pageerror', (e) => consoleLines.push({ phase, type: 'pageerror', text: String(e) }));
  const shot = async (name) => SHOTS && page.screenshot({ path: join(SHOTS, `${name}.png`) });

  await page.goto(BASE);
  await page.waitForSelector('.book-table');
  await page.waitForFunction(() => document.querySelector('.topbar-items').innerText.includes('open'));
  step('page loads; stream open; backend ok', (await page.textContent('.topbar-items')).includes('ok'));
  await page.waitForTimeout(2500); // the Simulator's health-gated load and the first polls
  step('fresh server: no console line from the app', consoleLines.length === 0, JSON.stringify(consoleLines));
  await shot('01-market-empty');

  // ---------------------------------------------------------------- orders
  const ticket = page.locator('.ticket');
  async function order({ trader, pair, side, type, price, qty }) {
    await ticket.locator('select').selectOption(String(trader));
    if (pair) await ticket.getByLabel('Pair').fill(pair);
    await ticket.getByText(side, { exact: true }).click();
    await ticket.getByText(type, { exact: true }).click();
    if (type === 'LIMIT') await ticket.getByLabel(/Price/).fill(price);
    await ticket.getByLabel('Quantity').fill(String(qty));
    await ticket.locator('button[type=submit]').click();
    await page.waitForFunction(() => !document.querySelector('.ticket button[type=submit]').disabled);
    await page.waitForTimeout(250);
    return squash(await ticket.locator('.ticket-result').innerText());
  }
  const cashOf = async () => page.locator('.kv-account dd').nth(1).innerText();
  const cashBefore = await cashOf();
  let r = await order({ trader: 2, side: 'SELL', type: 'LIMIT', price: '1.08500', qty: 30 });
  step('SELL 30 @ 1.08500 rests', r.includes('ORD-1 QUEUED'), r);
  r = await order({ trader: 4, side: 'SELL', type: 'LIMIT', price: '1.08510', qty: 20 });
  step('SELL 20 @ 1.08510 rests', r.includes('ORD-2 QUEUED'), r);
  r = await order({ trader: 1, side: 'BUY', type: 'LIMIT', price: '1.08510', qty: 40 });
  step('BUY 40 @ 1.08510 fills against both, best price first',
    r.includes('ORD-3 FILLED') && r.includes('1.08500 30') && r.includes('1.08510 10'), r);
  await page.waitForTimeout(800);
  const cashAfter = await cashOf();
  // 30 x 108500 + 10 x 108510 = 4,340,100
  step('the buyer\'s cash falls by the notional', cashBefore === '5,000,000,000' && cashAfter === '4,995,659,900', `${cashBefore} -> ${cashAfter}`);
  const trades = await page.locator('.trades tbody tr').count();
  step('trades feed shows both fills, newest first', trades === 2 && squash(await page.locator('.trades tbody tr').first().innerText()).startsWith('2 BUY 1.08510 10'));
  const book = squash(await page.locator('.book-table tbody').innerText());
  step('book: the partly filled ask remains', book.includes('ASK 1.08510 10 1 10'), book.slice(0, 120));
  await shot('02-market-traded');

  r = await order({ trader: 5, side: 'BUY', type: 'LIMIT', price: '1.08400', qty: 10 });
  const mine = page.locator('section:has(h2:text("My resting orders"))');
  const mineText = squash(await mine.innerText());
  step('my resting orders: the new bid and the partly filled ask', mineText.includes('ORD-4 EUR/USD BUY 1.08400 10 / 10') && mineText.includes('ORD-2 EUR/USD SELL 1.08510 10 / 20'), mineText);
  await mine.getByRole('button', { name: /Cancel ORD-4\b/ }).click();
  await page.waitForTimeout(500);
  step('cancel removes it', squash(await mine.innerText()).includes('ORD-4 cancelled: 10 removed'));

  // ---------------------------------------------------------------- every rejection
  await ticket.getByLabel(/Price/).fill('1.085051');
  step('a non-tick price is warned about before sending', (await ticket.locator('.field-warn').allInnerTexts()).join(' ').includes('BAD_PRICE'));
  const rejections = [
    ['BAD_PRICE', { trader: 1, side: 'BUY', type: 'LIMIT', price: '1.085051', qty: 10 }],
    ['BAD_QUANTITY', { trader: 1, side: 'BUY', type: 'LIMIT', price: '1.08500', qty: 0 }],
    ['MARGIN', { trader: 12, side: 'BUY', type: 'LIMIT', price: '1.08000', qty: 1000 }],
    ['UNKNOWN_PAIR', { trader: 1, pair: 'EUR/XXX', side: 'BUY', type: 'LIMIT', price: '1.08000', qty: 10 }],
    ['NO_LIQUIDITY', { trader: 1, pair: 'USD/JPY', side: 'BUY', type: 'MARKET', qty: 10 }],
  ];
  for (const [reason, o] of rejections) {
    r = await order(o);
    step(`${reason}: REJECTED with the reason in plain words`, r.includes('REJECTED') && r.includes(`${reason}:`), r);
  }
  await ticket.getByText('LIMIT', { exact: true }).click();
  await ticket.getByLabel(/Price/).fill('abc');
  step('malformed input is blocked (would be a 400)', (await ticket.locator('.field-error').allInnerTexts()).join(' ').includes('decimal number'));
  await shot('03-market-rejections');

  // ---------------------------------------------------------------- pair picker
  const picker = page.locator('.picker input');
  await picker.click();
  await picker.fill('usd/j');
  await page.waitForSelector('.picker-item');
  step('picker suggests from the Trie with the tick size', squash((await page.locator('.picker-item').allInnerTexts()).join('|')).includes('USD/JPY tick 0.001'));
  await picker.press('ArrowDown');
  await picker.press('Enter');
  await page.waitForTimeout(400);
  step('keyboard selection switches the book', (await page.locator('#book-title').innerText()).includes('USD/JPY'));
  await picker.click();
  await picker.fill('gbp/usd');
  await picker.press('Enter');
  await page.waitForTimeout(300);
  step('fast type + Enter on a complete pair selects it (F1)', (await page.locator('#book-title').innerText()).includes('GBP/USD'));
  await picker.click();
  await picker.fill('aud/n');
  await picker.press('Enter');
  await page.waitForTimeout(300);
  step('fast type + Enter on a partial pair never picks a stale suggestion (F1)', (await page.locator('#book-title').innerText()).includes('GBP/USD'));
  await picker.press('Escape');
  await picker.click();
  await picker.fill('eur/usd');
  await picker.press('Enter');
  await page.waitForTimeout(400);
  step('back to EUR/USD', (await page.locator('#book-title').innerText()).includes('EUR/USD'));

  // ---------------------------------------------------------------- demo seed flow
  await page.locator('.demo summary').click();
  await page.getByRole('button', { name: /Seed flow/ }).click();
  await page.waitForFunction(() => {
    const li = document.querySelector('.demo-log li');
    return li && !li.classList.contains('muted');
  }, null, { timeout: 20000 });
  const seedLog = await page.locator('.demo-log li').first().innerText();
  step('DEMO seed flow posts 20 orders and logs it', seedLog.includes('Seed flow: 20 limit orders on EUR/USD'), seedLog);
  await shot('04-market-seeded');

  // ---------------------------------------------------------------- arbitrage
  await page.getByRole('tab', { name: 'Arbitrage' }).click();
  await page.waitForSelector('g.node');
  step('no loophole yet: the expected-result message', (await page.locator('.cycle-box').innerText()).includes('No loophole above 0.02%'));
  await page.getByRole('button', { name: /Inject loophole/ }).click();
  await page.waitForSelector('.cycle-legs', { timeout: 10000 });
  await page.waitForTimeout(2600);
  const cycle = squash(await page.locator('.cycle-box').innerText());
  step('injected loophole glows: legs, multiplier and profit', cycle.includes('USD → SGD → HKD → USD') && cycle.includes('+0.4000%') && (await page.locator('.cycle-leg').count()) === 3, cycle);
  step('the badge shows REPLAY, never LIVE', (await page.textContent('.badge')).includes('REPLAY'));
  step('a FOUND alert is listed', (await page.locator('.alerts').innerText()).includes('FOUND'));
  await shot('05-arbitrage-found');
  const posBefore = await page.locator('g.node').first().getAttribute('transform');
  await page.waitForSelector('.cycle-none', { timeout: 45000 });
  const posAfter = await page.locator('g.node').first().getAttribute('transform');
  step('the loophole clears by itself (quotes expire after 30 s)', (await page.locator('.alerts').innerText()).includes('CLEARED'));
  step('the graph layout did not move', posBefore === posAfter);
  await shot('06-arbitrage-cleared');

  // ---------------------------------------------------------------- simulator
  await page.getByRole('tab', { name: 'Simulator' }).click();
  await page.waitForTimeout(500);
  step('simulator on a fresh server: no result yet, and no 404 requested', (await page.locator('section:has(h2:text("Engine vs naive baseline"))').innerText()).includes('No simulation has run'));
  await page.getByRole('button', { name: 'Run simulation' }).click();
  await page.waitForSelector('.sim-results', { timeout: 60000 });
  let sim = squash(await page.locator('.sim-results').innerText());
  step('shallow 5,000: engine vs baseline, checksumMatch, targets', sim.includes('profile shallow') && sim.includes('checksumMatch ✓ match') && sim.includes('DOC 04 TARGETS'), sim.slice(0, 120));
  await shot('07-sim-shallow');
  await page.getByLabel('Profile').selectOption('deep');
  await page.getByRole('button', { name: '1,000,000' }).click();
  step('deep 1,000,000 with the baseline is blocked in the form (contract cap)', (await page.getByRole('button', { name: 'Run simulation' }).isDisabled()) && (await page.locator('.field-error').allInnerTexts()).join(' ').includes('200,000'));
  await page.getByRole('button', { name: '50,000' }).click();
  await page.getByRole('button', { name: 'Run simulation' }).click();
  await page.waitForFunction(() => document.querySelector('.sim-results')?.innerText.includes('profile deep'), null, { timeout: 120000 });
  sim = squash(await page.locator('.sim-results').innerText());
  step('deep 50,000: checksumMatch and a speedup above 1', sim.includes('checksumMatch ✓ match') && /Speedup (\d+\.\d+)×/.test(sim) && Number(sim.match(/Speedup (\d+\.\d+)×/)[1]) > 1, (sim.match(/Speedup [^ ]+/) || [''])[0]);
  await shot('08-sim-deep');
  const other = await browser.newContext();
  const p2 = await other.newPage();
  await p2.goto(`${BASE}/api/health`);
  const slow = p2.evaluate(() => fetch('/api/simulate', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ numOrders: 200000, profile: 'deep' }) }).then((x) => x.status));
  await page.waitForTimeout(600);
  await page.getByRole('button', { name: '5,000' }).click();
  await page.getByLabel('Profile').selectOption('shallow');
  phase = 'expected-409';
  await page.getByRole('button', { name: 'Run simulation' }).click();
  await page.waitForSelector('.result-error', { timeout: 20000 });
  step('a concurrent simulation shows the 409 visibly', (await page.locator('.result-error').innerText()).includes('409 SIMULATION_RUNNING'));
  step('the other client\'s simulation finished', (await slow) === 200);
  await other.close();
  phase = 'normal';

  // ---------------------------------------------------------------- inspector, keyboard
  await page.getByRole('tab', { name: 'Inspector' }).click();
  await page.waitForSelector('.tree', { timeout: 10000 });
  const insp = squash(await page.locator('.grid-insp').innerText());
  step('inspector: levels, queue heads and the B+ tree shape', insp.includes('B+ tree · order 32') && insp.toUpperCase().includes('QUEUE HEAD'), insp.slice(0, 100));
  await shot('09-inspector');
  await page.getByRole('tab', { name: 'Inspector' }).focus();
  await page.keyboard.press('ArrowLeft');
  step('arrow keys move between tabs', (await page.evaluate(() => document.activeElement.textContent)) === 'Simulator');

  // ---------------------------------------------------------------- 1024 x 768 and below
  for (const tab of ['Market', 'Arbitrage', 'Simulator', 'Inspector']) {
    await page.setViewportSize({ width: 1024, height: 768 });
    await page.getByRole('tab', { name: tab }).click();
    await page.waitForTimeout(400);
    const m = await page.evaluate(() => {
      const vw = document.documentElement.clientWidth;
      let clipped = 0;
      for (const el of document.querySelectorAll('.panel, .topbar-items, .tabs')) {
        const b = el.getBoundingClientRect();
        if (b.right > vw + 0.5 || b.left < -0.5) clipped++;
      }
      return { hscroll: document.documentElement.scrollWidth > vw, clipped };
    });
    step(`1024 x 768, ${tab}: no horizontal scroll, nothing clipped`, !m.hscroll && m.clipped === 0, JSON.stringify(m));
  }
  await page.setViewportSize({ width: 1000, height: 768 });
  step('below 1024 px: one plain notice instead of the layout', await page.locator('.narrow-notice').isVisible() && !(await page.locator('.app').isVisible()));
  await page.setViewportSize({ width: 1280, height: 720 });
  await page.getByRole('tab', { name: 'Market' }).click();
  const appLines = consoleLines.filter((l) => l.phase === 'normal');
  step('golden path: no console line from the app', appLines.length === 0, JSON.stringify(appLines));

  // ---------------------------------------------------------------- outage and recovery
  phase = 'outage';
  stop(backend, 'SIGKILL');
  await page.waitForTimeout(5000);
  const bar = squash(await page.locator('.topbar-items').innerText());
  step('backend killed: DISCONNECTED, backend down, stream closed', bar.includes('DISCONNECTED') && bar.includes('down') && bar.includes('closed'), bar);
  step('an outage notice says the data is the last received', (await page.locator('.outage').innerText()).includes('not reachable'));
  step('panels keep their last data', squash(await page.locator('.book-table tbody').innerText()).includes('ASK'));
  await shot('10-backend-down');
  backend = startBackend();
  phase = 'recovered';
  await page.waitForFunction(() => {
    const t = document.querySelector('.topbar-items').innerText;
    return t.includes('ok') && t.includes('open');
  }, null, { timeout: 40000 });
  await page.waitForTimeout(1500);
  step('restarted: backend ok and stream open again, no page reload', true);
  step('snapshots refetched after the restart (books are per session)', squash(await page.locator('.book-table tbody').innerText()).includes('empty book'));
  step('the outage notice is gone', (await page.locator('.outage').count()) === 0);
  await shot('11-recovered');

  // ---------------------------------------------------------------- reduced motion
  const rm = await browser.newPage({ viewport: { width: 1280, height: 720 }, reducedMotion: 'reduce' });
  await rm.goto(BASE);
  await rm.waitForSelector('.book-table');
  const anim = await rm.evaluate(() => {
    const el = document.createElement('div');
    el.className = 'cycle-leg';
    document.body.appendChild(el);
    return getComputedStyle(el).animationName;
  });
  step('prefers-reduced-motion turns the pulse off', anim === 'none', anim);
  await browser.close();
}

let exitCode = 0;
try {
  await main();
} catch (e) {
  console.error('golden path aborted:', e && e.stack ? e.stack : e);
  exitCode = 1;
} finally {
  for (const c of children) stop(c, 'SIGKILL');
  if (existsSync(dataDir)) rmSync(dataDir, { recursive: true, force: true });
}
const failed = results.filter((x) => !x.ok).length;
console.log(`\ngolden path: ${results.length - failed} passed, ${failed} failed${preview ? ' (production build)' : ' (dev server)'}`);
process.exit(exitCode || (failed ? 1 : 0));
