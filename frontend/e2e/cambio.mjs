#!/usr/bin/env node
// The Cambio consumer app in a real (headless) Chromium, against `start-all.sh` (the whole system
// on one port, the feed replaying the committed capture). Like demo-script.mjs it is regression
// tooling, NOT part of `npm test`; Playwright stays out of package.json (see pw.mjs).
//
//   node frontend/e2e/cambio.mjs                 # port 8080 (must be free)
//   PORT=8091 node frontend/e2e/cambio.mjs       # another port
//   SHOTS=/tmp/shots node frontend/e2e/cambio.mjs   # also keep a screenshot per screen
//
// It starts ./start-all.sh with an empty FINNHUB_API_KEY (forces REPLAY, even with a .env file)
// and a temporary DATA_DIR, walks Markets, Trade, Portfolio and Opportunities as a user would,
// checks the consumer app shows no data-source jargon, checks reduced motion, then stops
// everything. Exit code 0 only if every check passed.

import { spawn } from 'node:child_process';
import { mkdtempSync, mkdirSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { launchChromium } from './pw.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, '..', '..');
const PORT = process.env.PORT || '8080';
const BASE = `http://127.0.0.1:${PORT}`;
const SHOTS = process.env.SHOTS || null;
if (SHOTS) mkdirSync(SHOTS, { recursive: true });

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
const alive = () => fetch(`${BASE}/api/health`).then(() => true, () => false);

async function main() {
  if (await alive()) throw new Error(`port ${PORT} is in use: stop what runs there, or set PORT`);
  const env = { ...process.env, PORT, DATA_DIR: mkdtempSync(join(tmpdir(), 'ads-cambio-')), FINNHUB_API_KEY: '' };
  const startAll = spawn(join(root, 'start-all.sh'), [], { cwd: root, env, stdio: ['ignore', 'pipe', 'pipe'] });
  let out = '';
  startAll.stdout.on('data', (d) => { out += d; });
  startAll.stderr.on('data', (d) => { out += d; });
  try {
    const up = await waitFor(() => out.includes(`Dashboard and API:  http://127.0.0.1:${PORT}`), 300000, 500);
    check('start-all.sh prints the URL', up, up ? '' : out.slice(-400));
    if (!up) return;
    check('the feed replays the capture', out.includes('REPLAY of the recorded capture'));

    const browser = await launchChromium();
    const page = await browser.newPage({ viewport: { width: 1440, height: 932 } });
    const lines = [];
    page.on('console', (m) => lines.push(`${m.type()}: ${m.text()}`));
    page.on('pageerror', (e) => lines.push(`pageerror: ${e}`));
    const shot = async (name) => SHOTS && page.screenshot({ path: join(SHOTS, `cambio-${name}.png`), fullPage: true });
    const body = async () => squash(await page.textContent('body'));

    // ---- Markets
    await page.goto(`${BASE}/`);
    await page.waitForSelector('.mrow', { timeout: 30000 });
    check('Markets: the page is called Markets · Cambio', (await page.title()) === 'Markets · Cambio', await page.title());
    check('Markets: four nav pills', (await page.locator('.nav-pill').allTextContents()).join(',') === 'Markets,Trade,Portfolio,Opportunities');
    check('Markets: practice account assigned, 50,000.00', squash(await page.locator('.practice-chip').innerText()).includes('50,000.00'));
    await waitFor(async () => (await page.locator('.mrow').count()) >= 3, 30000);
    check('Markets: at least three pairs listed with a price', (await page.locator('.mrow').count()) >= 3);
    const text = await body();
    const jargon = text.match(/\b(LIVE|REPLAY|backend|stream|seq)\b/i);
    check('Markets: no data-source jargon (LIVE, REPLAY, backend, stream, seq)', !jargon, jargon ? jargon[0] : '');
    check('Markets: says it is a practice account with simulated money', text.includes('Simulated money') && text.includes('Practice account'));
    await shot('markets');

    // ---- Trade
    await page.locator('.mrow-link').first().click();
    await page.waitForSelector('.trade-head');
    const h1 = await page.locator('h1').first().innerText();
    check('Trade: opens on the pair that was clicked', /^Trade [A-Z]{3}\/[A-Z]{3}$/.test(h1), h1);
    const seed = page.getByRole('button', { name: 'Add practice orders' });
    await waitFor(async () => (await seed.count()) > 0 || (await page.locator('.lv-asks .lv').count()) > 0, 10000);
    if (await seed.count()) {
      check('Trade: an empty book offers practice orders, clearly labelled', (await page.locator('.ladder-empty').innerText()).includes('simulated traders'));
      await seed.click();
    }
    await waitFor(async () => (await page.locator('.lv-asks .lv').count()) > 0 && (await page.locator('.lv-bids .lv').count()) > 0, 30000);
    check('Trade: the book shows sell and buy orders', (await page.locator('.lv-asks .lv').count()) > 0 && (await page.locator('.lv-bids .lv').count()) > 0);
    check("Trade: seeded orders are not listed as the user's own", (await page.locator('.orow').count()) === 0);

    await page.getByLabel(/Amount/).fill('0');
    await sleep(300);
    const msg = await page.locator('.msg').innerText();
    check('Trade: an amount of 0 is explained before sending', /Check this|Heads up/.test(msg), msg);
    await page.getByLabel(/Amount/).fill('5');
    await page.getByRole('button', { name: 'Best price now' }).click();
    const summary = await page.locator('.summary').innerText();
    check('Trade: the ticket says what will happen', /^You buy 5 [A-Z]{3} at the best price available/.test(summary), summary);
    const before = squash(await page.locator('.practice-amount').innerText());
    await page.getByRole('button', { name: /^Buy [A-Z]{3}$/ }).click();
    await page.waitForSelector('.result');
    const result = squash(await page.locator('.result').innerText());
    check('Trade: a market buy is confirmed in plain words', /bought 5/.test(result), result);
    await waitFor(async () => squash(await page.locator('.practice-amount').innerText()) !== before, 8000);
    const after = squash(await page.locator('.practice-amount').innerText());
    check('Trade: the practice balance went down by the cost', Number(after.replace(/,/g, '')) < Number(before.replace(/,/g, '')), `${before} -> ${after}`);

    await page.getByRole('button', { name: 'Sell', exact: true }).click();
    await page.getByRole('button', { name: 'Choose a price' }).click();
    await page.getByLabel(/Amount/).fill('3');
    // far above whatever pair this is (JPY pairs trade near 150, CHF near 0.8), so it must rest
    const live = Number(await page.getByLabel(/^Price/).inputValue());
    await page.getByLabel(/^Price/).fill(`${Math.max(2, Math.round(live * 3))}.00000`);
    await page.getByRole('button', { name: /^Sell [A-Z]{3}$/ }).click();
    await waitFor(async () => (await page.locator('.orow').count()) === 1, 8000);
    check('Trade: a sell far above the market rests and is listed under "Your open orders"', (await page.locator('.orow').count()) === 1);
    await page.getByRole('button', { name: /^Cancel/ }).click();
    await waitFor(async () => (await page.locator('.orow').count()) === 0, 8000);
    check('Trade: cancelling removes it', (await page.locator('.orow').count()) === 0);
    await page.getByRole('button', { name: 'Depth' }).click();
    check('Trade: the depth chart draws', (await page.locator('.depth-ask, .depth-bid').count()) > 0);
    await shot('trade');

    // ---- Portfolio
    await page.locator('.nav-pill', { hasText: 'Portfolio' }).click();
    await page.waitForSelector('.pf-head');
    check('Portfolio: page title', (await page.title()) === 'Portfolio · Cambio');
    const fills = squash(await page.locator('.fills-card').innerText());
    const bought = [...fills.matchAll(/Bought (\d+)/g)].reduce((n, m) => n + Number(m[1]), 0);
    check('Portfolio: the 5 bought appear in "Your trades" (a fill may be split in two)', bought === 5, `${bought}: ${fills.slice(0, 100)}`);
    check('Portfolio: since you started is negative after buying', /−/.test(squash(await page.locator('.pf-stats').innerText())));
    await shot('portfolio');

    // ---- Opportunities
    await page.locator('.nav-pill', { hasText: 'Opportunities' }).click();
    await page.waitForSelector('.loopmap');
    check('Opportunities: replay data has no loop, and says so', (await body()).includes('No loops right now'));
    await page.getByRole('button', { name: 'Show an example loop' }).click();
    await waitFor(async () => (await page.locator('.found-path').first().innerText()).includes('→'), 15000);
    const path = await page.locator('.found-path').first().innerText();
    check('Opportunities: the example loop runs through SGD and HKD', path.includes('SGD') && path.includes('HKD'), path);
    check('Opportunities: the loop is drawn on the map', (await page.locator('.map-leg').count()) >= 3);
    await page.getByRole('button', { name: 'Try it on paper' }).click();
    await page.waitForSelector('.step-end', { timeout: 8000 });
    await sleep(1500);
    const end = squash(await page.locator('.step-end').innerText());
    check('Opportunities: the paper run ends about 0.4% up', /\+0\.4\d*%/.test(end), end);
    check('Opportunities: says it is on paper, with no fees', (await page.locator('.paper').innerText()).includes('On paper only'));
    await shot('opportunities');

    // ---- skip link, demo route
    await page.goto(`${BASE}/#/markets`);
    await page.reload();
    await page.waitForSelector('.mrow');
    await page.keyboard.press('Tab');
    check('Keyboard: the first Tab stop is a skip link', (await page.evaluate(() => document.activeElement?.textContent)) === 'Skip to content');
    await page.goto(`${BASE}/#/demo`); // the app reloads itself when the hash crosses between Cambio and the demo
    await page.waitForSelector('.book-table', { timeout: 15000 });
    const demoBg = await page.evaluate(() => getComputedStyle(document.body).backgroundColor);
    check('Demo: it carries its own (dark) styles in the production build', /^rgb\((\d{1,2}|1[0-4]\d), (\d{1,2}|1[0-4]\d), (\d{1,2}|1[0-4]\d)\)$/.test(demoBg), demoBg);
    check('Demo: the terminal dashboard still lives at #/demo', (await page.getByRole('tab').allTextContents()).join(',') === 'Market,Arbitrage,Simulator,Inspector');

    // ---- reduced motion
    const rm = await browser.newPage({ viewport: { width: 1440, height: 932 }, reducedMotion: 'reduce' });
    for (const s of ['markets', 'trade', 'portfolio', 'opportunities']) {
      await rm.goto(`${BASE}/#/${s}`);
      await rm.reload();
      await sleep(1500);
      const n = await rm.evaluate(() => document.getAnimations().filter((a) => a.playState === 'running').length);
      check(`Reduced motion: no animation runs on ${s}`, n === 0, String(n));
    }

    const app = lines.filter((l) => !/React DevTools/.test(l));
    check('no browser console line during the whole walk', app.length === 0, JSON.stringify(app.slice(0, 3)));
    await browser.close();
  } finally {
    startAll.kill('SIGTERM');
    await waitFor(async () => !(await alive()), 15000);
    check('end: SIGTERM to start-all.sh stops everything', !(await alive()));
  }
}

main()
  .catch((e) => check('cambio script ran', false, String(e)))
  .finally(() => {
    const failed = results.filter((r) => !r.ok).length;
    console.log(`\ncambio: ${results.length - failed} passed, ${failed} failed`);
    process.exit(failed === 0 ? 0 : 1);
  });
