import { describe, expect, it } from 'vitest';
import { settleLayout } from './CurrencyGraph.jsx';

const IDS = ['USD', 'EUR', 'GBP', 'JPY', 'AUD', 'CAD', 'CHF', 'INR', 'CNY', 'HKD', 'SGD', 'NZD'];

describe('currency graph layout', () => {
  it('settles once, deterministically: the same ids give the same positions', () => {
    expect(settleLayout(IDS)).toEqual(settleLayout(IDS));
  });
  it('keeps every node inside the picture and apart from the others', () => {
    const pos = settleLayout(IDS);
    const pts = IDS.map((id) => pos[id]);
    for (const p of pts) {
      expect(p.x).toBeGreaterThanOrEqual(24);
      expect(p.x).toBeLessThanOrEqual(560 - 24);
      expect(p.y).toBeGreaterThanOrEqual(24);
      expect(p.y).toBeLessThanOrEqual(420 - 24);
    }
    let min = Infinity;
    for (let i = 0; i < pts.length; i++) {
      for (let j = i + 1; j < pts.length; j++) min = Math.min(min, Math.hypot(pts[i].x - pts[j].x, pts[i].y - pts[j].y));
    }
    expect(min).toBeGreaterThan(2 * 20 + 10); // radius 20: circles never touch
  });
});
