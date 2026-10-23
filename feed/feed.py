#!/usr/bin/env python3
"""Market-data feed (doc 08 Stage 2, doc 06).

Fetches and forwards, nothing else: Finnhub WebSocket ticks (and, while the socket has been
down for more than 15 s, an hourly Frankfurter snapshot) are POSTed to the Java backend's
POST /api/market/rates in the Appendix B shapes. Cross-rates, the currency graph, staleness
and arbitrage are all decided in Java.

Modes:
  feed.py --discover               call Finnhub /forex/symbol?exchange=oanda once and write
                                   feed/symbols.json (pairs that exist for the 8-currency
                                   roster, and which crosses are not streamed directly)
  feed.py                          live: stream feed/symbols.json's pairs to the backend
  feed.py --capture FILE           live, also appending each raw trade message as a line
  feed.py --replay FILE            forward a recorded capture (no key, no network needed)

The API key is read from FINNHUB_API_KEY only. It is never printed, logged or written: REST
calls send it as a header, and every log record passes through a redacting filter.
"""

import argparse
import datetime
import json
import logging
import math
import os
import socket
import sys
import time
import urllib.error
import urllib.request

ROSTER = ["USD", "EUR", "GBP", "JPY", "AUD", "CAD", "CHF", "INR"]
FINNHUB_WS = "wss://ws.finnhub.io"
FINNHUB_REST = "https://finnhub.io/api/v1"
FRANKFURTER_URL = "https://api.frankfurter.dev/v1/latest?base=USD"
DEFAULT_BACKEND = "http://127.0.0.1:8080/api/market/rates"
HERE = os.path.dirname(os.path.abspath(__file__))
SYMBOLS_FILE = os.path.join(HERE, "symbols.json")

# doc 06 §5 / doc 08 Stage 2
BATCH_S = 0.25                        # forward ticks at least every 250 ms
RECONNECT_BACKOFF_S = (1, 2, 4, 8, 10)  # then 10 s (cap) for every further attempt
FALLBACK_TRIGGER_S = 15               # socket down this long -> Frankfurter fallback
FALLBACK_POLL_S = 3600                # Frankfurter is a daily snapshot: hourly is plenty
FALLBACK_RETRY_S = 60                 # a failed fallback fetch is retried sooner
HEARTBEAT_S = 5                       # empty finnhub-ws batch while connected (weekends:
                                      # lets the backend report "connected, no ticks")
RECV_TIMEOUT_S = 0.25
HTTP_TIMEOUT_S = 10
# Frankfurter's CDN answers 403 to urllib's default "Python-urllib/x.y" agent (BUGS.md B5),
# so every request names this program instead.
USER_AGENT = "ads-feed/1.0 (+https://github.com/lifeknife10A/order-matching-arbitrage-detector)"

log = logging.getLogger("feed")


class RedactingFilter(logging.Filter):
    """Replaces the API key wherever it appears in a log record (message or arguments)."""

    def __init__(self, secret):
        super().__init__()
        self.secret = secret

    def filter(self, record):
        if self.secret:
            msg = record.getMessage()
            if self.secret in msg:
                record.msg = msg.replace(self.secret, "***")
                record.args = None
        return True


def setup_logging(secret=None, stream=None):
    handler = logging.StreamHandler(stream or sys.stderr)
    handler.setFormatter(logging.Formatter("%(asctime)s %(levelname)s %(message)s"))
    handler.addFilter(RedactingFilter(secret))
    log.handlers[:] = [handler]
    log.setLevel(logging.INFO)
    log.propagate = False


# ---------------------------------------------------------------- parsing

def parse_message(text):
    """Parses one Finnhub WebSocket message.

    Returns (kind, ticks, rejected): kind is "trade", "ping", "error", "other" or "malformed";
    ticks are Appendix B dicts {"symbol", "price", "ts"} for every well-formed trade entry;
    rejected counts trade entries that were skipped. Never raises.
    """
    try:
        msg = json.loads(text)
    except (ValueError, TypeError, RecursionError):  # deep nesting raises RecursionError
        return "malformed", [], 0
    if not isinstance(msg, dict):
        return "malformed", [], 0
    kind = msg.get("type")
    if kind == "ping":
        return "ping", [], 0
    if kind == "error":
        return "error", [], 0
    if kind != "trade":
        return "other", [], 0
    data = msg.get("data")
    if not isinstance(data, list):
        return "malformed", [], 0
    ticks = []
    rejected = 0
    for item in data:
        tick = _tick(item)
        if tick is None:
            rejected += 1
        else:
            ticks.append(tick)
    return "trade", ticks, rejected


def _tick(item):
    if not isinstance(item, dict):
        return None
    symbol, price, ts = item.get("s"), item.get("p"), item.get("t")
    if not isinstance(symbol, str) or not symbol:
        return None
    if isinstance(price, bool) or not isinstance(price, (int, float)):
        return None
    if not math.isfinite(price) or price <= 0:
        return None
    if isinstance(ts, bool) or not isinstance(ts, int) or ts <= 0:
        return None
    return {"symbol": symbol, "price": float(price), "ts": ts}


# ---------------------------------------------------------------- I/O helpers

def http_post_json(url, payload):
    body = json.dumps(payload, separators=(",", ":")).encode("utf-8")
    req = urllib.request.Request(url, data=body, method="POST",
                                 headers={"Content-Type": "application/json",
                                          "User-Agent": USER_AGENT})
    with urllib.request.urlopen(req, timeout=HTTP_TIMEOUT_S) as resp:
        return resp.status


def fetch_frankfurter():
    """The keyless fallback snapshot, as the Appendix B frankfurter-fallback payload."""
    req = urllib.request.Request(FRANKFURTER_URL, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(req, timeout=HTTP_TIMEOUT_S) as resp:
        data = json.loads(resp.read().decode("utf-8"))
    rates = data.get("rates")
    if data.get("base") != "USD" or not isinstance(rates, dict):
        raise ValueError("unexpected Frankfurter response shape")
    return {"source": "frankfurter-fallback", "base": "USD", "asOf": data.get("date"),
            "rates": rates}


def websocket_connector(api_key, url=FINNHUB_WS):
    """Returns a function that opens a Finnhub socket with a short receive timeout."""
    import websocket  # websocket-client; imported lazily so replay and tests need no install

    def connect():
        ws = websocket.create_connection(url + "?token=" + api_key, timeout=HTTP_TIMEOUT_S)
        ws.settimeout(RECV_TIMEOUT_S)
        return ws

    return connect


def timeout_errors():
    errors = [TimeoutError, socket.timeout]
    try:
        import websocket
        errors.append(websocket.WebSocketTimeoutException)
    except ImportError:
        pass
    return tuple(errors)


# ---------------------------------------------------------------- live feed

class Feed:
    """The live loop: one socket, reconnect with backoff, 250 ms batches, fallback.

    Everything that touches the outside world is injected (connect, post, fetch_fallback,
    clock, sleep), so tests drive it with a fake socket and a fake clock.
    """

    def __init__(self, symbols, connect, post, fetch_fallback, clock=time.monotonic,
                 sleep=time.sleep, capture=None, timeouts=None):
        self.symbols = list(symbols)
        self.connect = connect
        self.post = post
        self.fetch_fallback = fetch_fallback
        self.clock = clock
        self.sleep = sleep
        self.capture = capture
        self.timeouts = timeouts or timeout_errors()
        self.ws = None
        self.attempt = 0
        self.next_attempt = 0.0
        self.disconnected_since = clock()
        self.fallback_active = False
        self.last_fallback = None
        self.batch = []
        self.batch_started = None
        self.last_post = None
        self.stopped = False
        self.stats = {"messages": 0, "ticks": 0, "malformed": 0, "rejected_ticks": 0,
                      "pings": 0, "connects": 0, "disconnects": 0, "posts": 0,
                      "post_failures": 0, "fallback_posts": 0}

    def stop(self):
        self.stopped = True

    def run(self, until=None):
        """Runs until stop() or until the clock reaches `until` (seconds, same clock)."""
        while not self.stopped and (until is None or self.clock() < until):
            self.step()
        self.flush()
        if self.ws is not None:
            self._close()

    def step(self):
        now = self.clock()
        if self.ws is None:
            self._maybe_fallback(now)
            if now < self.next_attempt:
                self.sleep(min(self.next_attempt - now, 1.0))
                return
            self._try_connect(now)
            return
        try:
            text = self.ws.recv()
        except self.timeouts:
            text = None
        except Exception as e:  # socket closed, network gone, protocol error...
            self._disconnected(now, e)
            return
        if text:
            self.on_message(text, now)
        self._maybe_flush(self.clock())

    def _try_connect(self, now):
        try:
            ws = self.connect()
            for symbol in self.symbols:  # Finnhub forgets subscriptions across connections
                ws.send(json.dumps({"type": "subscribe", "symbol": symbol}))
        except Exception as e:
            delay = RECONNECT_BACKOFF_S[min(self.attempt, len(RECONNECT_BACKOFF_S) - 1)]
            self.attempt += 1
            self.next_attempt = now + delay
            log.warning("connect failed (%s); retrying in %ss", _describe(e), delay)
            return
        self.ws = ws
        self.attempt = 0
        self.stats["connects"] += 1
        if self.fallback_active:
            log.info("live feed back after %.1fs; leaving the Frankfurter fallback",
                     now - self.disconnected_since)
        self.fallback_active = False
        self.last_fallback = None  # a later outage falls back 15 s after IT starts
        self.disconnected_since = None
        log.info("connected; subscribed to %d symbols", len(self.symbols))
        self._post({"source": "finnhub-ws", "ticks": []})  # tell the backend we're live

    def _disconnected(self, now, err):
        log.warning("socket lost (%s); reconnecting", _describe(err))
        self.flush()
        self._close()
        self.stats["disconnects"] += 1
        self.disconnected_since = now
        self.next_attempt = now + RECONNECT_BACKOFF_S[0]  # first retry after 1 s,
        self.attempt = 1                                  # then 2, 4, 8, 10, 10...

    def _close(self):
        try:
            self.ws.close()
        except Exception:
            pass
        self.ws = None

    def on_message(self, text, now):
        self.stats["messages"] += 1
        kind, ticks, rejected = parse_message(text)
        self.stats["rejected_ticks"] += rejected
        if kind == "malformed":
            self.stats["malformed"] += 1
            log.warning("skipping malformed message: %r", text[:200])
            return
        if kind == "ping":
            self.stats["pings"] += 1
            return
        if kind == "error":
            log.warning("Finnhub error message: %r", text[:200])
            return
        if rejected:
            log.warning("skipped %d malformed trade entries", rejected)
        if not ticks:
            return
        if self.capture is not None:
            self.capture.write(text.strip() + "\n")
            self.capture.flush()
        if not self.batch:
            self.batch_started = now
        self.batch.extend(ticks)
        self.stats["ticks"] += len(ticks)

    def _maybe_flush(self, now):
        if self.batch and now - self.batch_started >= BATCH_S:
            self.flush()
        elif not self.batch and self.ws is not None and (
                self.last_post is None or now - self.last_post >= HEARTBEAT_S):
            self._post({"source": "finnhub-ws", "ticks": []})

    def flush(self):
        if not self.batch:
            return
        ticks, self.batch, self.batch_started = self.batch, [], None
        self._post({"source": "finnhub-ws", "ticks": ticks})

    def _maybe_fallback(self, now):
        if self.disconnected_since is None or now - self.disconnected_since <= FALLBACK_TRIGGER_S:
            return
        if self.last_fallback is not None:
            wait = FALLBACK_POLL_S if self.fallback_active else FALLBACK_RETRY_S
            if now - self.last_fallback < wait:
                return
        self.last_fallback = now
        try:
            payload = self.fetch_fallback()
        except Exception as e:
            log.warning("Frankfurter fallback unavailable (%s); keeping last rates", _describe(e))
            return
        if not self.fallback_active:
            log.info("socket down %.1fs: switching to the Frankfurter fallback",
                     now - self.disconnected_since)
        self.fallback_active = True
        if self._post(payload):
            self.stats["fallback_posts"] += 1

    def _post(self, payload):
        self.last_post = self.clock()
        try:
            self.post(payload)
        except Exception as e:  # backend down or rejecting: log, drop, keep running
            self.stats["post_failures"] += 1
            log.warning("backend post failed (%s); dropped %d ticks", _describe(e),
                        len(payload.get("ticks", [])))
            return False
        self.stats["posts"] += 1
        return True


def _describe(err):
    return type(err).__name__ + (": " + str(err)[:200] if str(err) else "")


# ---------------------------------------------------------------- replay

def replay(path, post, speed=1.0, sleep=time.sleep):
    """Forwards a capture file (one raw Finnhub message per line) as 250 ms batches.

    Batches follow the recorded tick timestamps; `speed` scales the pauses between them
    (2.0 = twice as fast, 0 = no pauses). Payloads carry "replay": true so the backend never
    presents a replay as the live feed. Malformed lines are counted and skipped.
    """
    stats = {"lines": 0, "ticks": 0, "malformed": 0, "posts": 0, "post_failures": 0}
    batch, batch_start_ts, last_sent_ts = [], None, None

    def send():
        nonlocal batch, last_sent_ts
        if not batch:
            return
        if speed > 0 and last_sent_ts is not None:
            gap = (batch[0]["ts"] - last_sent_ts) / 1000.0 / speed
            if gap > 0:
                sleep(min(gap, 5.0))
        last_sent_ts = batch[0]["ts"]
        try:
            post({"source": "finnhub-ws", "replay": True, "ticks": batch})
            stats["posts"] += 1
        except Exception as e:
            stats["post_failures"] += 1
            log.warning("backend post failed (%s)", _describe(e))
        batch = []

    with open(path, "r", encoding="utf-8") as f:
        for line in f:
            if not line.strip():
                continue
            stats["lines"] += 1
            kind, ticks, rejected = parse_message(line)
            if kind == "malformed" or rejected:
                stats["malformed"] += 1 if kind == "malformed" else rejected
            for t in ticks:
                if batch and t["ts"] - batch_start_ts >= BATCH_S * 1000:
                    send()
                if not batch:
                    batch_start_ts = t["ts"]
                batch.append(t)
                stats["ticks"] += 1
    send()
    return stats


# ---------------------------------------------------------------- discovery

def discover(api_key, out_path=SYMBOLS_FILE):
    """Calls /forex/symbol?exchange=oanda once and records roster coverage."""
    req = urllib.request.Request(FINNHUB_REST + "/forex/symbol?exchange=oanda",
                                 headers={"X-Finnhub-Token": api_key, "User-Agent": USER_AGENT})
    with urllib.request.urlopen(req, timeout=HTTP_TIMEOUT_S) as resp:
        listing = json.loads(resp.read().decode("utf-8"))
    if not isinstance(listing, list):
        raise ValueError("unexpected /forex/symbol response shape")
    available = {item.get("symbol") for item in listing if isinstance(item, dict)}
    subscribe, streamed, not_streamed = [], [], []
    for i, a in enumerate(ROSTER):
        for b in ROSTER[i + 1:]:
            direct = [s for s in ("OANDA:%s_%s" % (a, b), "OANDA:%s_%s" % (b, a)) if s in available]
            if direct:
                subscribe.extend(direct)
                streamed.append(direct[0].split(":")[1].replace("_", "/"))
            else:
                not_streamed.append("%s/%s" % (a, b))
    doc = {
        "endpoint": "GET https://finnhub.io/api/v1/forex/symbol?exchange=oanda",
        "fetchedAt": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "oandaSymbols": len(available),
        "roster": ROSTER,
        "subscribe": subscribe,
        "streamedPairs": streamed,
        "notStreamedDirectly": not_streamed,
        "note": "Pairs in notStreamedDirectly are derived through USD by the Java backend.",
    }
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(doc, f, indent=2)
        f.write("\n")
    return doc


def load_symbols(path=SYMBOLS_FILE):
    with open(path, "r", encoding="utf-8") as f:
        symbols = json.load(f).get("subscribe")
    if not isinstance(symbols, list) or not symbols:
        raise ValueError("%s has no 'subscribe' list" % path)
    return symbols


# ---------------------------------------------------------------- main

def main(argv=None, environ=None):
    environ = os.environ if environ is None else environ
    p = argparse.ArgumentParser(description="Finnhub/Frankfurter feed -> Java backend")
    mode = p.add_mutually_exclusive_group()
    mode.add_argument("--discover", action="store_true", help="write feed/symbols.json")
    mode.add_argument("--replay", metavar="FILE", help="forward a recorded capture")
    p.add_argument("--capture", metavar="FILE", help="live mode: also record raw trade messages")
    p.add_argument("--backend", default=DEFAULT_BACKEND, help="POST /api/market/rates URL")
    p.add_argument("--no-forward", action="store_true", help="live mode: do not post (capture only)")
    p.add_argument("--duration", type=float, help="live mode: stop after this many seconds")
    p.add_argument("--speed", type=float, default=1.0, help="replay pacing (0 = no pauses)")
    p.add_argument("--ws-url", default=FINNHUB_WS,
                   help="WebSocket URL (testing: point at a dead port to exercise the fallback)")
    args = p.parse_args(argv)

    key = environ.get("FINNHUB_API_KEY", "")
    setup_logging(secret=key or None)

    def post(payload):
        return http_post_json(args.backend, payload)

    if args.replay:
        stats = replay(args.replay, post, speed=args.speed)
        log.info("replay done: %s", json.dumps(stats))
        return 0 if stats["posts"] > 0 and stats["post_failures"] == 0 else 1

    if not key:
        print("FINNHUB_API_KEY is not set. Export it in your shell (never commit it); "
              "--replay works without it.", file=sys.stderr)
        return 2

    if args.discover:
        doc = discover(key)
        log.info("wrote %s: %d OANDA symbols, %d roster pairs streamed, %d not streamed",
                 SYMBOLS_FILE, doc["oandaSymbols"], len(doc["streamedPairs"]),
                 len(doc["notStreamedDirectly"]))
        return 0

    try:
        symbols = load_symbols()
    except (OSError, ValueError) as e:
        print("cannot load %s (%s); run feed.py --discover first" % (SYMBOLS_FILE, e),
              file=sys.stderr)
        return 2
    capture = open(args.capture, "a", encoding="utf-8") if args.capture else None
    post_fn = (lambda payload: None) if args.no_forward else post
    feed = Feed(symbols, websocket_connector(key, args.ws_url), post_fn, fetch_frankfurter,
                capture=capture)
    until = time.monotonic() + args.duration if args.duration else None
    try:
        feed.run(until=until)
    except KeyboardInterrupt:
        feed.flush()
    finally:
        if capture is not None:
            capture.close()
    log.info("stopped: %s", json.dumps(feed.stats))
    return 0


if __name__ == "__main__":
    sys.exit(main())
