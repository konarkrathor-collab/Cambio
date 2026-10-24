#!/usr/bin/env node
// Follows documentation/DEMO.md literally, in a headless Chromium, against `start-all.sh`
// (the whole system on one port, the feed replaying the committed capture). Regression
// tooling for demo day, like golden-path.mjs: NOT part of `npm test`, and Playwright stays
// out of package.json (install it as golden-path.mjs explains).
//
//   node frontend/e2e/demo-script.mjs             # port 8080 (must be free)
//   PORT=8091 node frontend/e2e/demo-script.mjs   # another port
//   SHOTS=/tmp/shots node frontend/e2e/demo-script.mjs   # also keep a screenshot per step
//
// It starts ./start-all.sh with FINNHUB_API_KEY removed from its environment and a temporary
// DATA_DIR, clicks through steps 1-7 of DEMO.md checking what the audience should see,
// then stops start-all.sh with SIGTERM (its Ctrl-C path). Exit code 0 only if every check passed.

import { spawn, execSync } from 'node:child_process';
import { createRequire } from 'node:module';
import { mkdtempSync, mkdirSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, '..', '..');
const PORT = process.env.PORT || '8080';
const BASE = `http://127.0.0.1:${PORT}`;
const SHOTS = process.env.SHOTS || null;
if (SHOTS) mkdirSync(SHOTS, { recursive: true });

async function loadPlaywright() {
  try {
    return await import('playwright');
  } catch {
    const globalRoot = execSync('npm root -g', { encoding: 'utf8' }).trim();
    return createRequire(join(globalRoot, 'noop.js'))('playwright');
  }
}

const results = [];
function check(name, ok, detail = '') {
  results.push({ name, ok });
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? `  (${detail})` : ''}`);
}
const squash = (s) => s.replace(/\s+/g, ' ').trim();
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function waitFor(fn, ms, every = 250) {
  const end = Date.now() + ms;
  while (Date.now() < end) {
    try {
      if (await fn()) return true;
    } catch {
      // not yet
    }
    await sleep(every);
  }
  return false;
}

async function main() {
  if (await fetch(`${BASE}/api/health`).then(() => true, () => false)) {
    throw new Error(`port ${PORT} is in use: stop what runs there, or set PORT`);
  }
  const env = { ...process.env, PORT, DATA_DIR: mkdtempSync(join(tmpdir(), 'ads-demo-')) };
  delete env.FINNHUB_API_KEY; // DEMO.md: "unset FINNHUB_API_KEY"
  const startAll = spawn(join(root, 'start-all.sh'), [], { cwd: root, env, stdio: ['ignore', 'pipe', 'pipe'] });
  let out = '';
  startAll.stdout.on('data', (d) => { out += d; });
  startAll.stderr.on('data', (d) => { out += d; });
  try {
    const up = await waitFor(() => out.includes(`Dashboard and API:  http://127.0.0.1:${PORT}`), 300000, 500);
    check('start-all.sh prints the dashboard URL', up, up ? '' : out.slice(-400));
    if (!up) return;
    check('start-all.sh says it replays the capture, not live', out.includes('REPLAY of the recorded capture'));

    const { chromium } = await loadPlaywright();
    const browser = await chromium.launch();
    const page = await browser.newPage({ viewport: { width: 1280, height: 800 } });
    const consoleLines = [];
    page.on('console', (m) => consoleLines.push(`${m.type()}: ${m.text()}`));
    page.on('pageerror', (e) => consoleLines.push(`pageerror: ${e}`));
    const shot = async (name) => SHOTS && page.screenshot({ path: join(SHOTS, `${name}.png`), fullPage: true });

    // ---- 1. the honesty badge
    await page.goto(BASE);
    await page.waitForSelector('.book-table');
    await waitFor(async () => (await page.textContent('.topbar-items')).includes('open') && (await page.textContent('.badge')).includes('REPLAY'), 15000);
    const bar = squash(await page.textContent('.topbar-items'));
    check('1: the dashboard loads from the same port as the API', true);
    check('1: badge REPLAY (never LIVE), backend ok, stream open, seq', bar.includes('REPLAY') && !bar.includes('LIVE') && bar.includes('ok') && bar.includes('open') && bar.includes('seq'), bar);
    check('1: title and four tabs', (await page.textContent('body')).includes('Order Matching & Arbitrage Detector')
      && (await page.getByRole('tab').allInnerTexts()).join(',') === 'Market,Arbitrage,Simulator,Inspector');
    await shot('1-topbar');

    // ---- 2. the order book
    check('2: the book is EUR/USD', (await page.locator('#book-title').innerText()).toUpperCase().includes('ORDER BOOK · EUR/USD'));
    await page.locator('.demo summary').click();
    check('2: the drawer is labelled "DEMO tools: seed order flow, inject a loophole"', squash(await page.locator('.demo summary').innerText()).includes('DEMO tools: seed order flow, inject a loophole'));
    await page.getByRole('button', { name: 'Seed flow on EUR/USD' }).click();
    await waitFor(async () => (await page.locator('.demo-log li').first().innerText()).includes('Seed flow'), 15000);
    check('2: the seed is logged', (await page.locator('.demo-log li').first().innerText()).includes('Seed flow: 20 limit orders on EUR/USD'));
    await waitFor(async () => (await page.locator('.book-table tbody').innerText()).includes('ASK'), 5000);
    const book = squash(await page.locator('.book-table tbody').innerText());
    check('2: the ladder shows asks and bids with the spread row', book.includes('ASK') && book.includes('BID') && /spread/i.test(book), book.slice(0, 160));
    check('2: the depth chart draws', (await page.locator('.depth svg path').count()) >= 2);
    await shot('2-seeded');

    const ticket = page.locator('.ticket');
    const cash = async () => page.locator('.kv-account dd').nth(1).innerText();
    await ticket.locator('select').selectOption('1');
    await ticket.getByText('BUY', { exact: true }).click();
    await ticket.getByText('LIMIT', { exact: true }).click();
    // "type the lowest ASK price in the ladder": the ask row nearest the spread
    const askRows = await page.locator('.book-table tbody tr').allInnerTexts();
    const asks = askRows.map(squash).filter((r) => r.startsWith('ASK'));
    const lowestAsk = asks[asks.length - 1].split(' ')[1];
    const cashBefore = await cash();
    await ticket.getByLabel(/Price/).fill(lowestAsk);
    await ticket.getByLabel('Quantity').fill('5');
    await ticket.getByRole('button', { name: 'BUY LIMIT' }).click();
    await waitFor(async () => /FILLED|PARTIAL/.test(await ticket.locator('.ticket-result').innerText()), 5000);
    const r1 = squash(await ticket.locator('.ticket-result').innerText());
    check(`2: BUY 5 @ the lowest ask ${lowestAsk} is FILLED or PARTIAL with a fills table`, /FILLED|PARTIAL/.test(r1) && (await ticket.locator('.ticket-result table').count()) === 1, r1.slice(0, 120));
    await waitFor(async () => (await cash()) !== cashBefore, 3000);
    check('2: T1\'s cash drops', (await cash()) !== cashBefore, `${cashBefore} -> ${await cash()}`);
    check('2: the trades feed has rows', (await page.locator('.trades tbody tr').count()) > 0);

    await ticket.getByLabel(/Price/).fill('1.08000');
    await ticket.getByLabel('Quantity').fill('10');
    await ticket.getByRole('button', { name: 'BUY LIMIT' }).click();
    await waitFor(async () => (await ticket.locator('.ticket-result').innerText()).includes('QUEUED'), 5000);
    check('2: BUY 10 @ 1.08000 is QUEUED', (await ticket.locator('.ticket-result').innerText()).includes('QUEUED'));
    const mine = page.locator('section:has(h2:text("My resting orders"))');
    await waitFor(async () => (await mine.getByRole('button', { name: /Cancel ORD-/ }).count()) > 0, 3000);
    check('2: it appears in My resting orders', squash(await mine.innerText()).includes('BUY 1.08000 10 / 10'));
    await mine.getByRole('button', { name: /Cancel ORD-/ }).first().click();
    await waitFor(async () => (await mine.innerText()).includes('cancelled: 10 removed'), 3000);
    check('2: Cancel says "cancelled: 10 removed"', squash(await mine.innerText()).includes('cancelled: 10 removed'));
    await shot('2-ordered');

    // ---- 3. the Inspector
    await page.getByRole('tab', { name: 'Inspector' }).click();
    await waitFor(async () => (await page.locator('.grid-insp').innerText()).includes('B+ tree'), 5000);
    const insp = squash(await page.locator('.grid-insp').innerText());
    const INSP = insp.toUpperCase(); // panel titles and table headers are shown in capitals
    check('3: Asks and Bids panels for EUR/USD with queue heads', INSP.includes('ASKS (SELL SIDE) · EUR/USD') && INSP.includes('BIDS (BUY SIDE) · EUR/USD') && INSP.includes('QUEUE HEAD'));
    const shape = insp.match(/B\+ tree · order 32 · height \d+ · \d+ internal · \d+ leaves · \d+ entries/);
    check('3: the B+ tree shape line', Boolean(shape), shape ? shape[0] : insp.slice(0, 120));
    check('3: the leaf-chain note', insp.includes('leaves are linked left to right; each entry is one price level with its FIFO queue'));
    await shot('3-inspector');

    // ---- 4. the trie
    await page.getByRole('tab', { name: 'Market' }).click();
    const picker = page.locator('.picker input');
    await picker.click();
    await picker.fill('usd/j');
    await waitFor(async () => (await page.locator('.picker-item').count()) > 0, 3000);
    check('4: suggestions with tick sizes (USD/JPY tick 0.001)', squash((await page.locator('.picker-item').allInnerTexts()).join('|')).includes('USD/JPY tick 0.001'));
    const items = (await page.locator('.picker-item').allInnerTexts()).map(squash);
    for (let i = 0; i <= items.findIndex((t) => t.startsWith('USD/JPY')); i++) await picker.press('ArrowDown');
    await picker.press('Enter');
    await waitFor(async () => (await page.locator('#book-title').innerText()).includes('USD/JPY'), 3000);
    check('4: arrow keys + Enter switch the book to USD/JPY', (await page.locator('#book-title').innerText()).includes('USD/JPY'));
    await picker.click();
    await picker.fill('eur/usd');
    await picker.press('Enter');
    await waitFor(async () => (await page.locator('#book-title').innerText()).includes('EUR/USD'), 3000);
    check('4: eur/usd + Enter goes back', (await page.locator('#book-title').innerText()).includes('EUR/USD'));

    // ---- 5. loopholes
    await page.getByRole('tab', { name: 'Arbitrage' }).click();
    await waitFor(async () => (await page.locator('.cycle-box').innerText()).includes('No loophole'), 10000);
    check('5: on replayed real data: "No loophole above 0.02%. Real markets close these within milliseconds, so this is the expected result."',
      squash(await page.locator('.cycle-box').innerText()).includes('No loophole above 0.02%. Real markets close these within milliseconds, so this is the expected result.'));
    check('5: the graph has 12 currencies', (await page.locator('g.node').count()) === 12);
    await waitFor(async () => (await page.locator('g.node:not(.node-inactive)').count()) === 7, 10000);
    const dimmed = (await page.locator('g.node.node-inactive text').allTextContents()).sort().join(',');
    check('5: the 7 streamed currencies are bright; CNY, HKD, INR, NZD, SGD dimmed', dimmed === 'CNY,HKD,INR,NZD,SGD', dimmed);
    const scanPanel = squash(await page.locator('section:has(h2:text("Scanner"))').innerText());
    check('5: the Scanner panel shows threshold, last scan, scans and source', ['Threshold', 'Last scan', 'Scans', 'Source'].every((k) => scanPanel.includes(k)), scanPanel.slice(0, 160));
    await shot('5-real');
    const posBefore = await page.locator('g.node').first().getAttribute('transform');
    await page.getByRole('button', { name: /Inject loophole/ }).click();
    await waitFor(async () => (await page.locator('.cycle-box').innerText()).includes('SGD') && /\+0\.4\d{3}%/.test(await page.locator('.cycle-box').innerText()), 5000);
    const cycle = squash(await page.locator('.cycle-box').innerText());
    check('5: the injection is logged with its pairs, as REPLAY data', (await page.locator('.demo-log li').first().innerText()).includes('USD/SGD 1.3, USD/HKD 7.8, SGD/HKD 6.024 tick set as REPLAY data'));
    check('5: a cycle through SGD and HKD glows with legs, multiplier and about +0.40%', cycle.includes('SGD') && cycle.includes('HKD') && /\+0\.4\d{3}%/.test(cycle) && cycle.includes('Multiplier') && (await page.locator('.cycle-leg').count()) >= 3, cycle.slice(0, 160));
    check('5: the badge still says REPLAY', (await page.textContent('.badge')).includes('REPLAY'));
    await waitFor(async () => (await page.locator('.alerts').innerText()).includes('FOUND'), 3000);
    check('5: a FOUND alert', (await page.locator('.alerts').innerText()).includes('FOUND'));
    await shot('5-injected');
    await sleep(10000);
    check('5: still shown 10 s later: the replay feed does not overwrite it (F3)', /\+0\.4\d{3}%/.test(squash(await page.locator('.cycle-box').innerText())));
    const cleared = await waitFor(async () => (await page.locator('.alerts').innerText()).includes('CLEARED'), 30000);
    check('5: about 30 s after the injection a CLEARED alert appears', cleared);
    check('5: the message returns', squash(await page.locator('.cycle-box').innerText()).includes('No loophole above 0.02%'));
    check('5: the graph did not move', posBefore === (await page.locator('g.node').first().getAttribute('transform')));

    // ---- 6. deep versus shallow
    await page.getByRole('tab', { name: 'Simulator' }).click();
    await page.getByRole('button', { name: '5,000' }).click();
    await page.getByLabel('Profile').selectOption('shallow');
    check('6: Naive baseline is ticked', await page.getByRole('checkbox', { name: 'Naive baseline' }).isChecked());
    await page.getByRole('button', { name: 'Run simulation' }).click();
    await waitFor(async () => (await page.locator('.sim-results').innerText()).includes('profile shallow'), 60000);
    let sim = squash(await page.locator('.sim-results').innerText());
    const speed = (s) => Number((s.match(/Speedup (\d+\.\d+)×/) || [])[1]);
    check('6: shallow 5,000: checksumMatch ✓ match and a speedup', sim.includes('checksumMatch ✓ match') && speed(sim) > 0, `speedup ${speed(sim)}×`);
    check('6: the Engine vs naive baseline panel', (await page.locator('section:has(h2:text("Engine vs naive baseline"))').count()) === 1);
    await page.getByLabel('Profile').selectOption('deep');
    await page.getByRole('button', { name: '50,000' }).click();
    await page.getByRole('button', { name: 'Run simulation' }).click();
    await waitFor(async () => (await page.locator('.sim-results').innerText()).includes('profile deep'), 120000);
    sim = squash(await page.locator('.sim-results').innerText());
    check('6: deep 50,000: checksumMatch ✓ match and a speedup well above 1', sim.includes('checksumMatch ✓ match') && speed(sim) > 2, `speedup ${speed(sim)}×`);
    const targets = squash(await page.locator('section:has(h2:text("Engine vs naive baseline"))').innerText());
    check('6: Doc 04 targets panel lists throughput, cancel and scan, marked met or missed', targets.includes('50,000') && targets.includes('1 µs') && targets.includes('100 µs') && /met|missed/i.test(targets), targets.slice(0, 200));
    await shot('6-deep');

    // ---- 7. accounts
    await page.getByRole('tab', { name: 'Market' }).click();
    const acct = squash(await page.locator('section:has(h2:text("Account"))').innerText());
    check('7: Account panel: Cash and Margin limit in simulation units', acct.includes('Cash') && acct.includes('Margin limit') && acct.includes('simulation units'), acct.slice(0, 120));

    const appLines = consoleLines.filter((l) => !/React DevTools/.test(l));
    check('no browser console line during the demo', appLines.length === 0, JSON.stringify(appLines.slice(0, 3)));
    await browser.close();
  } finally {
    startAll.kill('SIGTERM'); // start-all.sh's Ctrl-C path: stops the backend and the feed
    await waitFor(async () => !(await fetch(`${BASE}/api/health`).then(() => true, () => false)), 15000);
    check('end: SIGTERM to start-all.sh stops everything', !(await fetch(`${BASE}/api/health`).then(() => true, () => false)));
  }
}

main()
  .catch((e) => check('demo script ran', false, String(e)))
  .finally(() => {
    const failed = results.filter((r) => !r.ok).length;
    console.log(`\ndemo script: ${results.length - failed} passed, ${failed} failed`);
    process.exit(failed === 0 ? 0 : 1);
  });
