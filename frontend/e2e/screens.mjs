#!/usr/bin/env node
// Screenshots of every tab against an already running system (normally ./start-all.sh).
// Regression tooling like golden-path.mjs: NOT part of `npm test`, Playwright stays out of
// package.json.
//
//   node frontend/e2e/screens.mjs OUT_DIR [--base http://127.0.0.1:8080] [--width 1440] [--height 900] [--prefix after]
//
// It seeds order flow with the DEMO drawer, injects the (replay) loophole and runs a deep
// 50,000-order simulation, so every tab has data, then saves OUT_DIR/<prefix>-<tab>-<width>.png.
// Selectors are roles and visible text only, so the same script works on any styling.

import { execSync } from 'node:child_process';
import { createRequire } from 'node:module';
import { mkdirSync } from 'node:fs';
import { join } from 'node:path';

const args = process.argv.slice(2);
const opt = (name, def) => {
  const i = args.indexOf(`--${name}`);
  return i >= 0 ? args[i + 1] : def;
};
const out = args[0];
if (!out || out.startsWith('--')) {
  console.error('usage: node frontend/e2e/screens.mjs OUT_DIR [--base URL] [--width N] [--height N] [--prefix NAME]');
  process.exit(2);
}
const BASE = opt('base', 'http://127.0.0.1:8080');
const W = Number(opt('width', '1440'));
const H = Number(opt('height', '900'));
const prefix = opt('prefix', 'shot');
mkdirSync(out, { recursive: true });

async function loadPlaywright() {
  try {
    return await import('playwright');
  } catch {
    const globalRoot = execSync('npm root -g', { encoding: 'utf8' }).trim();
    return createRequire(join(globalRoot, 'noop.js'))('playwright');
  }
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const { chromium } = await loadPlaywright();
const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: W, height: H } });
const lines = [];
page.on('console', (m) => lines.push(`${m.type()}: ${m.text()}`));
page.on('pageerror', (e) => lines.push(`pageerror: ${e}`));
await page.goto(`${BASE}/#/demo`);
await page.getByRole('tab', { name: 'Market' }).waitFor();
await sleep(2500); // any intro finishes and the first snapshots arrive

const drawer = page.locator('details').filter({ hasText: 'DEMO' }).first();
if (!(await drawer.evaluate((d) => d.open))) await drawer.locator('summary').click();
await page.getByRole('button', { name: /Seed flow on/ }).click();
await sleep(1500);
await page.getByRole('button', { name: /Seed flow on/ }).click();
await sleep(1500);
await drawer.locator('summary').click(); // close it again for the Market shot
await sleep(800);
await page.screenshot({ path: join(out, `${prefix}-market-${W}.png`) });

await page.getByRole('tab', { name: 'Inspector' }).click();
await sleep(2500);
await page.screenshot({ path: join(out, `${prefix}-inspector-${W}.png`) });

await page.getByRole('tab', { name: 'Arbitrage' }).click();
await drawer.locator('summary').click();
await page.getByRole('button', { name: /Inject loophole/ }).click();
await drawer.locator('summary').click();
await sleep(2500);
await page.screenshot({ path: join(out, `${prefix}-arbitrage-${W}.png`) });

await page.getByRole('tab', { name: 'Simulator' }).click();
await page.getByLabel('Profile').selectOption('deep');
await page.getByRole('button', { name: '50,000' }).click();
await page.getByRole('button', { name: 'Run simulation' }).click();
await page.getByRole('button', { name: /Running/ }).waitFor({ timeout: 5000 }).catch(() => {});
await page.getByRole('button', { name: 'Run simulation' }).waitFor({ timeout: 120000 }); // finished
await page.getByText('profile deep').first().waitFor({ timeout: 5000 });
await sleep(1200);
await page.screenshot({ path: join(out, `${prefix}-simulator-${W}.png`) });

await drawer.locator('summary').click();
await sleep(600);
await page.screenshot({ path: join(out, `${prefix}-demo-drawer-${W}.png`) });

await browser.close();
console.log(`saved 5 screenshots to ${out}`);
if (lines.length) console.log(`console lines:\n${lines.join('\n')}`);
process.exit(lines.length ? 1 : 0);
