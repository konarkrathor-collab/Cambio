// The doc 04 performance targets next to a simulation's measured values. A miss is always
// shown as a miss; a value that was not measured says so.

export const TARGETS = {
  throughput: 50_000, // orders per second
  scanUs: 100, // per scan, on a 20-currency graph
  cancelUs: 1, // per cancel
};

/** [{id, target, measured, met, note}] for one simulation result (contract §3.12). */
export function evaluateTargets(result) {
  if (!result) return [];
  const out = [];
  out.push({
    id: 'throughput',
    target: '≥ 50,000 orders/s',
    measured: result.ordersPerSec,
    unit: 'orders/s',
    met: result.ordersPerSec >= TARGETS.throughput,
    note: 'All operations, cancels and invalid orders included, timed inside the engine.',
  });
  const cancel = result.latencyUs?.cancel;
  if (cancel && cancel.count > 0) {
    out.push({
      id: 'cancel',
      target: '< 1 µs per cancel',
      measured: cancel.p99,
      unit: 'µs (p99)',
      met: cancel.p99 < TARGETS.cancelUs,
      note: `p50 ${cancel.p50.toFixed(3)} µs, p99 ${cancel.p99.toFixed(3)} µs, max ${cancel.max.toFixed(3)} µs`
        + (cancel.max >= TARGETS.cancelUs ? ' (the maximum is above 1 µs)' : '') + '.',
    });
  } else {
    out.push({ id: 'cancel', target: '< 1 µs per cancel', measured: null, unit: 'µs', met: null, note: 'No cancels in this run.' });
  }
  const scans = result.scans;
  if (scans && scans.count > 0) {
    out.push({
      id: 'scan',
      target: '< 100 µs per scan (20-currency graph)',
      measured: scans.p99Us,
      unit: 'µs (p99)',
      met: scans.p99Us < TARGETS.scanUs,
      note: `p50 ${scans.p50Us.toFixed(3)} µs over ${scans.count} scans of the simulator's 12-currency synthetic graph.`
        + ' The 20-currency figure itself comes from backend/test.sh --scan-timing.',
    });
  } else {
    out.push({ id: 'scan', target: '< 100 µs per scan (20-currency graph)', measured: null, unit: 'µs', met: null, note: 'No scans in this run (scanEvery 0).' });
  }
  return out;
}
