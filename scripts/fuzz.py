#!/usr/bin/env python3
"""Stage 6 failure drill 4: seeded fuzz of every endpoint (Python standard library only).

  python3 scripts/fuzz.py --base http://127.0.0.1:PORT [--seed 1] [--requests 20000] [--threads 8]

Sends random JSON, wrong types, huge numbers, unicode and hostile pair names, deep nesting,
oversized and malformed bodies, raw protocol abuse, and concurrent submit/cancel races. Fails
if the server ever answers 500, ever takes longer than the timeout (a hang), or stops
answering. Afterwards it checks two invariants: total cash is conserved (trades only move
cash between accounts; created accounts add theirs), and no order was cancelled twice.
"""

import argparse
import http.client
import json
import random
import socket
import sys
import threading
import time
import urllib.parse

TIMEOUT_S = 15.0
PAIRS = ["EUR/USD", "USD/JPY", "GBP/USD", "EUR/GBP", "AUD/USD"]
HOSTILE_PAIRS = ["", " ", "EUR", "EURUSD", "EUR//USD", "EUR/USD/GBP", "eur-usd", "ÉUR/USD", "EUR/УSD",
                 "欧元/美元", "😀/USD", "EUR/\u0000SD", "EUR/\u202eDSU", "../../etc/passwd", "%2e%2e%2f",
                 "<script>alert(1)</script>", "'; DROP TABLE x;--", "A" * 5000, "\ud83d", "USD/USD",
                 "\\u0045UR/USD", "EUR/USD\n", "EUR /USD", "nan", "null"]
HUGE = [10 ** 19, -10 ** 19, 2 ** 63, 2 ** 63 - 1, -2 ** 63, 10 ** 400, 1e308, -0.0, 0.1 + 0.2,
        1e-400, 123456789012345678901234567890.123456789]
FIELDS = {
    "/api/orders": ["traderId", "pair", "side", "type", "price", "quantity"],
    "/api/accounts": ["traderId", "cash", "marginLimit"],
    "/api/simulate": ["numOrders", "seed", "baseline", "scanEvery", "rateVolatility", "profile"],
    "/api/market/rates": ["source", "ticks", "rates", "base", "asOf", "replay"],
}


class Stats:
    def __init__(self):
        self.lock = threading.Lock()
        self.requests = 0
        self.codes = {}
        self.failures = []
        self.cancel_ok = {}
        self.created_cash = 0
        self.slowest = 0.0
        self.slowest_label = ""

    def record(self, label, code, elapsed):
        with self.lock:
            self.requests += 1
            self.codes[code] = self.codes.get(code, 0) + 1
            if elapsed > self.slowest:
                self.slowest, self.slowest_label = elapsed, label
            if code in (500, "TIMEOUT", "DEAD", "BOUND"):
                if len(self.failures) < 20:
                    self.failures.append(f"{code} on {label}")


def rand_string(r):
    pick = r.random()
    if pick < 0.3:
        return r.choice(HOSTILE_PAIRS)
    if pick < 0.5:
        return "".join(chr(r.randint(0x20, 0x2FFF)) for _ in range(r.randint(0, 40)))
    if pick < 0.6:
        return "".join(chr(r.randint(0, 0x10FFFF)) for _ in range(r.randint(1, 8))).encode(
            "utf-8", "surrogatepass").decode("utf-8", "replace")
    return r.choice(["BUY", "SELL", "LIMIT", "MARKET", "buy", "Limit", "HOLD", "1.08500", "-1", "1e9",
                     "0x10", "Infinity", "NaN", "true", "ORD-1", "finnhub-ws", "frankfurter-fallback"])


def rand_value(r, depth=0):
    pick = r.random()
    if depth > 3 or pick < 0.25:
        return r.choice([None, True, False, 0, -1, 1, r.randint(-10 ** 6, 10 ** 6), r.choice(HUGE),
                         r.uniform(-1e6, 1e6), rand_string(r)])
    if pick < 0.5:
        return rand_string(r)
    if pick < 0.75:
        return [rand_value(r, depth + 1) for _ in range(r.randint(0, 4))]
    return {rand_string(r) if r.random() < 0.3 else r.choice(sum(FIELDS.values(), [])): rand_value(r, depth + 1)
            for _ in range(r.randint(0, 6))}


def mutated_body(r, path):
    """A body near the valid shape for path, with one or more fields broken."""
    if path == "/api/orders":
        body = {"traderId": r.randint(1, 13), "pair": r.choice(PAIRS), "side": r.choice(["BUY", "SELL"]),
                "type": r.choice(["LIMIT", "MARKET"]), "price": r.choice(["1.08500", 1.085, "156.870"]),
                "quantity": r.randint(1, 50)}
    elif path == "/api/accounts":
        body = {"traderId": r.randint(1000, 10 ** 6), "cash": r.randint(0, 10 ** 9), "marginLimit": r.randint(0, 10 ** 9)}
    elif path == "/api/simulate":
        body = {"numOrders": r.randint(1, 300), "seed": r.randint(-10, 10), "baseline": r.random() < 0.5,
                "scanEvery": r.randint(0, 50), "rateVolatility": r.choice([0, 0.01, 1]),
                "profile": r.choice(["shallow", "deep"])}
    else:
        body = {"source": "finnhub-ws", "replay": True,
                "ticks": [{"symbol": "OANDA:EUR_USD", "price": 1.08, "ts": int(time.time() * 1000)}]}
    for _ in range(r.randint(0, 3)):
        k = r.choice(FIELDS[path] + ["extra", "qty", ""])
        action = r.random()
        if action < 0.3:
            body.pop(k, None)
        elif action < 0.6:
            body[k] = rand_value(r)
        else:
            body[k] = r.choice(HUGE + HOSTILE_PAIRS[:6] + [None, [], {}])
    return body


def deep(n):
    return "[" * n + "]" * n


class Client:
    def __init__(self, base, stats):
        u = urllib.parse.urlparse(base)
        self.host, self.port = u.hostname, u.port
        self.stats = stats
        self.conn = None

    def send(self, method, path, body=None, raw=None, headers=None, label=None):
        data = raw if raw is not None else (None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8", "surrogatepass"))
        hdrs = dict(headers or {})
        if data is not None and "Content-Type" not in hdrs:
            hdrs["Content-Type"] = "application/json"
        label = label or f"{method} {path[:80]}"
        for attempt in (0, 1):
            if self.conn is None:
                self.conn = http.client.HTTPConnection(self.host, self.port, timeout=TIMEOUT_S)
            t0 = time.perf_counter()
            try:
                self.conn.request(method, path, body=data, headers=hdrs)
                resp = self.conn.getresponse()
                payload = resp.read()
                elapsed = time.perf_counter() - t0
                if resp.getheader("Connection", "").lower() == "close":
                    self.conn.close()
                    self.conn = None
                self.stats.record(label, resp.status, elapsed)
                return resp.status, payload
            except socket.timeout:
                self.stats.record(label, "TIMEOUT", time.perf_counter() - t0)
                self.conn.close()
                self.conn = None
                return "TIMEOUT", b""
            except (http.client.HTTPException, ConnectionError, OSError):
                # the server may close a connection after a malformed request; one retry on a
                # fresh connection tells "closed this connection" from "dead"
                self.conn.close()
                self.conn = None
                if attempt:
                    self.stats.record(label, "DEAD", time.perf_counter() - t0)
                    return "DEAD", b""
        return "DEAD", b""


def quote(s):
    """Percent-encodes s; a lone surrogate goes as its raw (invalid UTF-8) bytes."""
    return urllib.parse.quote(s.encode("utf-8", "surrogatepass"), safe="")


def random_request(r, c):
    pick = r.randrange(16)
    if pick == 0:
        c.send("POST", "/api/orders", mutated_body(r, "/api/orders"))
    elif pick == 1:
        c.send("POST", "/api/orders", rand_value(r))
    elif pick == 2:
        c.send("DELETE", "/api/orders/" + quote(r.choice(["ORD-1", "1", "ORD-", "-1", "ORD-99999999999999999999",
                                                           "0", "ORD-1e3", rand_string(r)])))
    elif pick == 3:
        c.send("GET", f"/api/orderbook/{quote(r.choice(PAIRS + HOSTILE_PAIRS).replace('/', '-'))}?depth="
               + quote(str(r.choice([1, 10, 50, 51, 0, -1, "x", 10 ** 30, ""]))))
    elif pick == 4:
        c.send("GET", "/api/inspector/" + quote(r.choice(PAIRS + HOSTILE_PAIRS).replace("/", "-")))
    elif pick == 5:
        c.send("GET", r.choice(["/api/arbitrage/scan", "/api/arbitrage/graph", "/api/market/rates/status",
                                "/api/health", "/api/accounts", "/api/simulate/last"]))
    elif pick == 6:
        c.send("POST", "/api/market/rates", mutated_body(r, "/api/market/rates") if r.random() < 0.6 else rand_value(r))
    elif pick == 7:
        c.send("GET", "/api/tickers?prefix=" + quote(rand_string(r)))
    elif pick == 8:
        b = mutated_body(r, "/api/accounts")
        status, _ = c.send("POST", "/api/accounts", b)
        if status == 201:
            with c.stats.lock:
                c.stats.created_cash += b["cash"]
    elif pick == 9:
        c.send("GET", "/api/accounts/" + quote(r.choice(["1", "12", "0", "-5", "x", "9" * 40, rand_string(r)])))
    elif pick == 10:
        c.send("GET", "/api/trades?" + r.choice(["", "pair=" + quote(rand_string(r)), "limit=" + quote(rand_string(r)),
                                                 "limit=500&pair=EUR-USD", "limit=0", "pair=&limit=&x=y"]))
    elif pick == 11:
        if r.random() < 0.3:
            c.send("POST", "/api/simulate", mutated_body(r, "/api/simulate"))
        else:
            c.send("POST", "/api/simulate", rand_value(r))
    elif pick == 12:  # malformed bodies
        raw = r.choice([b"{", b"}", b"{\"a\":", deep(r.choice([10, 63, 64, 65, 200, 5000])).encode(),
                        b"\xff\xfe\x00garbage", b"{\"traderId\":1,\"pair\":\"\xc3\x28\"}", b"null", b"[]",
                        b"\"str\"", b"1e999999999", b"{\"price\":1e999999999}", b" " * 1000, b""])
        c.send("POST", r.choice(["/api/orders", "/api/accounts", "/api/simulate", "/api/market/rates"]), raw=raw,
               label="POST malformed body")
    elif pick == 13:  # oversized
        size = r.choice([1024 * 1024 - 10, 1024 * 1024 + 1, 3 * 1024 * 1024])
        c.send("POST", "/api/orders", raw=b"{\"pad\":\"" + b"x" * size + b"\"}", label=f"POST oversized {size}")
    elif pick == 14:  # odd methods, paths, headers
        c.send(r.choice(["PUT", "PATCH", "HEAD", "OPTIONS", "TRACE", "DELETE", "GET"]),
               r.choice(["/api/orders", "/api/nope", "/", "/api/", "/api/orders/", "//api//health",
                         "/api/orderbook/", "/api/../api/health", "/api/health?" + "a=" * 2000,
                         "/api/%ff%fe", "/api/orders/%00"]),
               headers={"Origin": r.choice(["http://localhost:5173", "http://evil.example", "null"])})
    else:
        c.send("GET", "/api/health")


def raw_abuse(r, base, stats):
    """Protocol-level abuse over raw sockets: lies about Content-Length, slowloris, garbage."""
    u = urllib.parse.urlparse(base)
    cases = [
        b"POST /api/orders HTTP/1.1\r\nHost: x\r\nContent-Type: application/json\r\nContent-Length: 500\r\n\r\n{\"a\":1}",
        b"GARBAGE\r\n\r\n",
        b"\x16\x03\x01\x00\xa5\x01\x00\x00\xa1\x03\x03" + bytes(range(64)),  # a TLS ClientHello
        b"GET /api/health HTTP/1.1\r\nHost: x\r\n" + b"X-Long: " + b"a" * 70000 + b"\r\n\r\n",
        b"POST /api/orders HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\n\r\nzz\r\n",
        b"GET /api/stream HTTP/1.1\r\nHost: x\r\n\r\n",  # opened and dropped at once
    ]
    for data in cases:
        s = socket.create_connection((u.hostname, u.port), timeout=5)
        try:
            s.sendall(data)
            try:
                s.recv(256)
            except socket.timeout:
                pass  # the server may wait for the promised body; we close
        except OSError:
            pass
        finally:
            s.close()
        stats.record("raw socket abuse", "raw", 0)


def race(base, stats, seed, threads=8, rounds=200):
    """Concurrent submit and cancel: many threads cancel the same resting orders."""
    r = random.Random(seed)
    c = Client(base, stats)
    ids = []
    for i in range(rounds):
        s, payload = c.send("POST", "/api/orders", {"traderId": 1 + i % 11, "pair": "EUR/USD", "side": "SELL",
                                                   "type": "LIMIT", "price": f"1.{86000 + r.randint(0, 50):05d}",
                                                   "quantity": 5})
        if s == 200:
            ids.append(json.loads(payload)["orderId"])

    def worker(k):
        wr = random.Random(seed * 100 + k)
        wc = Client(base, stats)
        for _ in range(rounds):
            oid = wr.choice(ids)
            if wr.random() < 0.7:
                st, _ = wc.send("DELETE", "/api/orders/" + oid, label="race cancel")
                if st == 200:
                    with stats.lock:
                        stats.cancel_ok[oid] = stats.cancel_ok.get(oid, 0) + 1
            else:
                wc.send("POST", "/api/orders", {"traderId": 1 + wr.randrange(11), "pair": "EUR/USD", "side": "BUY",
                                               "type": wr.choice(["LIMIT", "MARKET"]), "price": "1.86050",
                                               "quantity": wr.randint(1, 8)}, label="race submit")

    ts = [threading.Thread(target=worker, args=(k,)) for k in range(threads)]
    for t in ts:
        t.start()
    for t in ts:
        t.join()
    return len(ids)


def huge_balances(base, stats, seed):
    """Balance bounds (Stage 6 addendum, B14): an account above 10^15 must be refused with
    400; accounts opened at exactly 10^15 then trade as makers and takers at high prices."""
    r = random.Random(seed)
    c = Client(base, stats)
    big = []
    for k, cash in enumerate([2 ** 63 - 1, 10 ** 18, 10 ** 15 + 1]):
        st, _ = c.send("POST", "/api/accounts", {"traderId": 9_100_000 + seed * 10 + k, "cash": cash,
                                                 "marginLimit": 10 ** 15}, label="over-bound account")
        if st != 400:
            stats.record(f"over-bound account accepted with {st}", "BOUND", 0)
    for k in range(6):
        tid = 9_000_000 + seed * 10 + k
        st, _ = c.send("POST", "/api/accounts", {"traderId": tid, "cash": 10 ** 15, "marginLimit": 10 ** 15},
                       label="max account")
        if st == 201:
            with stats.lock:
                stats.created_cash += 10 ** 15
            big.append(tid)
    for i in range(300):
        rich = r.choice(big)
        normal = r.randint(1, 11)
        px = r.choice(["9999.99999", "1.08500", "5000.00000"])
        qty = r.randint(1, 10)
        if r.random() < 0.5:  # the rich account rests, a normal one crosses
            c.send("POST", "/api/orders", {"traderId": rich, "pair": "EUR/USD", "side": "SELL", "type": "LIMIT",
                                           "price": px, "quantity": qty}, label="max maker")
            c.send("POST", "/api/orders", {"traderId": normal, "pair": "EUR/USD", "side": "BUY",
                                           "type": r.choice(["LIMIT", "MARKET"]), "price": px, "quantity": qty},
                   label="cross a max maker")
        else:  # a normal account rests, the rich one crosses
            c.send("POST", "/api/orders", {"traderId": normal, "pair": "EUR/USD", "side": "BUY", "type": "LIMIT",
                                           "price": "1.08500", "quantity": qty}, label="normal maker")
            c.send("POST", "/api/orders", {"traderId": rich, "pair": "EUR/USD", "side": r.choice(["SELL", "BUY"]),
                                           "type": r.choice(["LIMIT", "MARKET"]), "price": "1.08500", "quantity": qty},
                   label="max taker")
    return len(big)


def total_cash(base):
    c = Client(base, Stats())
    _, payload = c.send("GET", "/api/accounts")
    return sum(a["cash"] for a in json.loads(payload)["accounts"])


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--base", required=True)
    p.add_argument("--seed", type=int, default=1)
    p.add_argument("--requests", type=int, default=20000)
    p.add_argument("--threads", type=int, default=8)
    a = p.parse_args()
    stats = Stats()
    cash0 = total_cash(a.base)
    t0 = time.time()

    def fuzz_worker(k, n):
        r = random.Random(a.seed * 1000 + k)
        c = Client(a.base, stats)
        for _ in range(n):
            random_request(r, c)

    per = a.requests // a.threads
    workers = [threading.Thread(target=fuzz_worker, args=(k, per)) for k in range(a.threads)]
    for w in workers:
        w.start()
    raw_abuse(random.Random(a.seed), a.base, stats)
    for w in workers:
        w.join()
    placed = race(a.base, stats, a.seed, threads=a.threads)
    rich = huge_balances(a.base, stats, a.seed)
    elapsed = time.time() - t0

    alive = Client(a.base, Stats()).send("GET", "/api/health")[0] == 200
    cash1 = total_cash(a.base)
    double = {k: v for k, v in stats.cancel_ok.items() if v > 1}
    print(f"fuzz seed {a.seed}: {stats.requests} requests in {elapsed:.1f} s ({a.threads} threads), "
          f"slowest {stats.slowest * 1000:.0f} ms ({stats.slowest_label})")
    print(f"  status codes: {dict(sorted(stats.codes.items(), key=lambda kv: str(kv[0])))}")
    print(f"  race: {placed} resting orders, {sum(stats.cancel_ok.values())} successful cancels, "
          f"{len(double)} orders cancelled twice")
    print(f"  balance bounds: 3 over-bound accounts refused (400); {rich} accounts at 10^15 traded 600 orders")
    print(f"  cash: start {cash0} + created {stats.created_cash} = {cash0 + stats.created_cash}, end {cash1}")
    ok = True
    for name, cond in [("no 500", stats.codes.get(500, 0) == 0), ("no hang", stats.codes.get("TIMEOUT", 0) == 0),
                       ("never dead", stats.codes.get("DEAD", 0) == 0), ("alive at the end", alive),
                       ("cash conserved", cash1 == cash0 + stats.created_cash), ("no double cancel", not double),
                       ("account bounds enforced", stats.codes.get("BOUND", 0) == 0)]:
        print(f"{'PASS' if cond else 'FAIL'}  {name}")
        ok = ok and cond
    for f in stats.failures:
        print("   ", f)
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
