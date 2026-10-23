#!/usr/bin/env python3
"""Stage 6 load generator (Python standard library only; macOS and Linux).

  load.py orders --base URL --rate N --duration S [--report-every S] [--json FILE]
      POST /api/orders at a steady N per second over one keep-alive connection and time
      every request (send to last byte of the response). A crossing mix on four pairs from
      demo traders 1-11; every fifth operation cancels one of its own resting orders, and past
      400 of them it cancels the oldest, so the books reach a steady size. Prints p50 / p99 / max per interval and overall.

  load.py ticks --base URL --rate N --duration S [--batch K]
      POST /api/market/rates with N ticks per second in payloads of K ticks (source
      finnhub-ws, replay: true, prices jittered around a consistent set).

Requests are paced to a schedule (open loop): a slow response does not lower the offered
rate; if the client falls behind, it sends at once and says so in the report.
"""

import argparse
import http.client
import json
import random
import sys
import time
import urllib.parse

PAIRS = [("EUR/USD", 108500, 5), ("USD/JPY", 156870, 3), ("GBP/USD", 130000, 5), ("EUR/GBP", 83400, 5)]
SYMBOLS = [("OANDA:EUR_USD", 1.08500), ("OANDA:GBP_USD", 1.30000), ("OANDA:EUR_GBP", 0.83462),
           ("OANDA:USD_JPY", 156.870), ("OANDA:AUD_USD", 0.66000), ("OANDA:USD_CHF", 0.88000),
           ("OANDA:USD_CAD", 1.37000)]


def connect(base):
    u = urllib.parse.urlparse(base)
    return http.client.HTTPConnection(u.hostname, u.port, timeout=10)


def percentile(sorted_values, p):
    """Nearest rank, like the simulator: the sample of rank ceil(p/100 * n)."""
    if not sorted_values:
        return 0.0
    rank = max(1, -(-p * len(sorted_values) // 100))
    return sorted_values[int(rank) - 1]


def summary(samples_ms):
    s = sorted(samples_ms)
    return {"count": len(s), "p50_ms": round(percentile(s, 50), 3), "p99_ms": round(percentile(s, 99), 3),
            "max_ms": round(s[-1], 3) if s else 0.0}


def fmt(label, st):
    return f"{label}: n={st['count']} p50={st['p50_ms']:.3f} ms p99={st['p99_ms']:.3f} ms max={st['max_ms']:.3f} ms"


def request(conn_box, base, method, path, body):
    """One request on the keep-alive connection (reconnecting once if it was closed)."""
    data = None if body is None else json.dumps(body).encode()
    headers = {"Content-Type": "application/json"} if data is not None else {}
    for attempt in (0, 1):
        try:
            t0 = time.perf_counter()
            conn_box[0].request(method, path, body=data, headers=headers)
            resp = conn_box[0].getresponse()
            payload = resp.read()
            return (time.perf_counter() - t0) * 1000.0, resp.status, payload
        except (http.client.HTTPException, OSError):
            conn_box[0].close()
            conn_box[0] = connect(base)
            if attempt:
                raise
    raise RuntimeError("unreachable")


def run_orders(a):
    rnd = random.Random(a.seed)
    box = [connect(a.base)]
    resting = []
    all_ms, window_ms, statuses = [], [], {}
    interval = 1.0 / a.rate
    start = time.perf_counter()
    next_at = start
    next_report = start + a.report_every
    behind = 0
    i = 0
    reports = []
    while True:
        now = time.perf_counter()
        if now - start >= a.duration:
            break
        if now < next_at:
            time.sleep(next_at - now)
        elif now - next_at > interval:
            behind += 1
        next_at += interval
        if resting and (i % 5 == 4 or len(resting) > 400):
            # every fifth operation cancels a random resting order of ours; past 400 resting
            # it cancels the oldest instead, so the books reach a steady size
            oid = resting.pop(0) if len(resting) > 400 else resting.pop(rnd.randrange(len(resting)))
            ms, status, _ = request(box, a.base, "DELETE", f"/api/orders/{oid}", None)
        else:
            pair, mid, dec = PAIRS[rnd.randrange(len(PAIRS))]
            buy = rnd.random() < 0.5
            tick = mid + rnd.randint(-4, 4)
            price = f"{tick / 10 ** dec:.{dec}f}"
            body = {"traderId": rnd.randint(1, 11), "pair": pair, "side": "BUY" if buy else "SELL",
                    "type": "LIMIT", "price": price, "quantity": rnd.randint(1, 20)}
            ms, status, payload = request(box, a.base, "POST", "/api/orders", body)
            if status == 200:
                r = json.loads(payload)
                if r.get("restingQuantity", 0) > 0:
                    resting.append(r["orderId"])
        statuses[status] = statuses.get(status, 0) + 1
        all_ms.append(ms)
        window_ms.append(ms)
        i += 1
        if time.perf_counter() >= next_report:
            st = summary(window_ms)
            st["t_s"] = round(time.perf_counter() - start)
            reports.append(st)
            print(fmt(f"  t={st['t_s']:>4}s", st), flush=True)
            window_ms = []
            next_report += a.report_every
    total = summary(all_ms)
    elapsed = time.perf_counter() - start
    print(fmt(f"orders total ({i / elapsed:.1f}/s offered {a.rate}/s, behind schedule {behind} times)", total), flush=True)
    print(f"  HTTP statuses: {statuses}", flush=True)
    if a.json:
        with open(a.json, "w") as f:
            json.dump({"total": total, "intervals": reports, "statuses": statuses, "rate": i / elapsed}, f)
    return 0 if set(statuses) <= {200, 404} else 1


def run_ticks(a):
    rnd = random.Random(a.seed)
    box = [connect(a.base)]
    payloads_per_s = a.rate / a.batch
    interval = 1.0 / payloads_per_s
    start = time.perf_counter()
    next_at = start
    sent = 0
    errors = 0
    while time.perf_counter() - start < a.duration:
        now = time.perf_counter()
        if now < next_at:
            time.sleep(next_at - now)
        next_at += interval
        ts = int(time.time() * 1000)
        ticks = []
        for _ in range(a.batch):
            sym, px = SYMBOLS[rnd.randrange(len(SYMBOLS))]
            ticks.append({"symbol": sym, "price": round(px * (1 + rnd.uniform(-2e-4, 2e-4)), 6), "ts": ts})
        _, status, _ = request(box, a.base, "POST", "/api/market/rates",
                               {"source": "finnhub-ws", "replay": True, "ticks": ticks})
        sent += a.batch
        if status != 200:
            errors += 1
    elapsed = time.perf_counter() - start
    print(f"ticks: {sent} sent in {elapsed:.1f} s ({sent / elapsed:.1f}/s), non-200 payloads: {errors}", flush=True)
    return 0 if errors == 0 else 1


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="mode", required=True)
    o = sub.add_parser("orders")
    t = sub.add_parser("ticks")
    for q in (o, t):
        q.add_argument("--base", required=True)
        q.add_argument("--rate", type=float, required=True)
        q.add_argument("--duration", type=float, required=True)
        q.add_argument("--seed", type=int, default=1)
    o.add_argument("--report-every", type=float, default=10.0)
    o.add_argument("--json")
    t.add_argument("--batch", type=int, default=2)
    a = p.parse_args()
    return run_orders(a) if a.mode == "orders" else run_ticks(a)


if __name__ == "__main__":
    sys.exit(main())
