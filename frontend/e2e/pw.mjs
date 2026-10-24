// One way for every e2e script to find Playwright and a browser. Playwright is deliberately not in
// package.json. Lookup order: PLAYWRIGHT_MODULE (a path to playwright or playwright-core), an
// installed `playwright`, then npm's global root. CHROMIUM_PATH points at a browser binary when the
// one Playwright expects is not the one installed (a version mismatch).

import { execSync } from 'node:child_process';
import { createRequire } from 'node:module';
import { join } from 'node:path';

export async function loadPlaywright() {
  if (process.env.PLAYWRIGHT_MODULE) return createRequire(import.meta.url)(process.env.PLAYWRIGHT_MODULE);
  try {
    return await import('playwright');
  } catch {
    // ES modules ignore NODE_PATH and global installs: look in npm's global root
    const globalRoot = execSync('npm root -g', { encoding: 'utf8' }).trim();
    return createRequire(join(globalRoot, 'noop.js'))('playwright');
  }
}

export async function launchChromium() {
  const { chromium } = await loadPlaywright();
  return chromium.launch(process.env.CHROMIUM_PATH ? { executablePath: process.env.CHROMIUM_PATH } : {});
}
