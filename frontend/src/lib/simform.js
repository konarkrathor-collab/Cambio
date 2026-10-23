// The simulator form's checks, mirroring contract §3.12 (the server checks again).

const MAX_ORDERS = 1_000_000;
const DEEP_BASELINE_MAX = 200_000;
const INT = /^-?\d+$/;

/** {errors, body}: body is the POST /api/simulate request, or null. */
export function validateSimulation(f) {
  const errors = {};
  if (!INT.test(f.numOrders) || Number(f.numOrders) < 1 || Number(f.numOrders) > MAX_ORDERS) errors.numOrders = 'From 1 to 1,000,000.';
  if (!INT.test(f.seed) || !Number.isSafeInteger(Number(f.seed))) errors.seed = 'A whole number.';
  if (!INT.test(f.scanEvery) || Number(f.scanEvery) < 0 || Number(f.scanEvery) > MAX_ORDERS) errors.scanEvery = 'From 0 (no scans) to 1,000,000.';
  const vol = Number(f.rateVolatility);
  if (f.rateVolatility.trim() === '' || !Number.isFinite(vol) || vol < 0 || vol > 1) errors.rateVolatility = 'A percentage from 0 to 1.';
  if (f.profile === 'deep' && f.baseline && Number(f.numOrders) > DEEP_BASELINE_MAX) {
    errors.numOrders = 'A deep run with the baseline is capped at 200,000 orders (the baseline costs O(resting orders) per operation). Lower it or turn the baseline off.';
  }
  if (Object.keys(errors).length) return { errors, body: null };
  return {
    errors,
    body: {
      numOrders: Number(f.numOrders),
      seed: Number(f.seed),
      baseline: f.baseline,
      profile: f.profile,
      scanEvery: Number(f.scanEvery),
      rateVolatility: vol,
    },
  };
}
