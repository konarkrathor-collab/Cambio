"""Feed tests: a fake socket and a fake clock drive the real Feed loop (no network, no key).

Run from the repository root:  feed/test.sh   (or: python3 -m unittest discover -s feed/tests)
"""

import io
import json
import logging
import os
import sys
import tempfile
import unittest
import urllib.error
from unittest import mock

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
import feed  # noqa: E402

SYMBOLS = ["OANDA:EUR_USD", "OANDA:USD_JPY", "OANDA:EUR_GBP"]
SAMPLE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "samples",
                      "finnhub_ticks_2026-10-23.jsonl")


class FakeClock:
    def __init__(self):
        self.t = 1000.0

    def __call__(self):
        return self.t

    def sleep(self, dt):
        self.t += dt


class FakeSocket:
    """recv() plays a script; each call takes `step` seconds. When the script runs out it
    either keeps timing out (quiet socket) or raises (connection dropped)."""

    def __init__(self, clock, script=(), step=0.05, then="drop"):
        self.clock, self.script, self.step, self.then = clock, list(script), step, then
        self.sent, self.closed = [], False

    def send(self, text):
        self.sent.append(json.loads(text))

    def recv(self):
        self.clock.t += self.step
        if self.script:
            item = self.script.pop(0)
            if isinstance(item, BaseException):
                raise item
            return item
        if self.then == "drop":
            self.dropped_at = self.clock.t - self.step  # when the failing recv began
            raise ConnectionResetError("connection reset by peer")
        raise TimeoutError()

    def close(self):
        self.closed = True


class FakeConnector:
    """Each call takes the next outcome: a FakeSocket, or an exception to raise."""

    def __init__(self, clock, outcomes):
        self.clock, self.outcomes, self.attempts, self.sockets = clock, list(outcomes), [], []

    def __call__(self):
        self.attempts.append(self.clock.t)
        outcome = self.outcomes.pop(0) if self.outcomes else OSError("network unreachable")
        if isinstance(outcome, BaseException):
            raise outcome
        self.sockets.append(outcome)
        return outcome


class Recorder:
    def __init__(self, clock, fail=False):
        self.clock, self.fail, self.payloads, self.times = clock, fail, [], []

    def __call__(self, payload):
        if self.fail:
            raise urllib.error.URLError("connection refused")
        self.payloads.append(payload)
        self.times.append(self.clock.t)


def trade(*ticks):
    return json.dumps({"type": "trade",
                       "data": [{"s": s, "p": p, "t": t, "v": 0} for s, p, t in ticks]})


def make_feed(clock, connector, post=None, fallback=None):
    post = post or Recorder(clock)
    fallback = fallback or (lambda: {"source": "frankfurter-fallback", "base": "USD",
                                     "asOf": "2026-10-23", "rates": {"EUR": 0.92}})
    f = feed.Feed(SYMBOLS, connector, post, fallback, clock=clock, sleep=clock.sleep,
                  timeouts=(TimeoutError,))
    return f, post


class QuietLogs(unittest.TestCase):
    def setUp(self):
        self.log_stream = io.StringIO()
        feed.setup_logging(secret="sk-TEST-SECRET-123", stream=self.log_stream)


class ReconnectTest(QuietLogs):
    def test_backoff_is_1_2_4_8_10_10_and_every_reconnect_resubscribes(self):
        clock = FakeClock()
        first = FakeSocket(clock, [trade(("OANDA:EUR_USD", 1.08, 1))], then="drop")
        second = FakeSocket(clock, [], then="quiet")
        fails = [OSError("refused")] * 6
        conn = FakeConnector(clock, fails + [first] + [OSError("refused")] * 2 + [second])
        f, post = make_feed(clock, conn)
        f.run(until=clock.t + 60)
        gaps = [round(b - a, 3) for a, b in zip(conn.attempts, conn.attempts[1:])]
        # 6 failures: 1,2,4,8,10,10 -> connect; drop -> retry after 1, then 2, then success
        self.assertEqual(gaps[:6], [1, 2, 4, 8, 10, 10])
        # the socket drops on its 2nd recv, which starts 0.05 s after connecting; the retry is
        # 1 s after that, then 2, then 4
        self.assertAlmostEqual(gaps[6], 0.05 + 1.0, places=6)
        self.assertEqual([round(g, 6) for g in gaps[7:9]], [2, 4])
        for sock in (first, second):
            self.assertEqual([m["symbol"] for m in sock.sent], SYMBOLS)
            self.assertTrue(all(m["type"] == "subscribe" for m in sock.sent))
        self.assertTrue(first.closed)
        self.assertEqual(f.stats["connects"], 2)
        self.assertEqual(f.stats["disconnects"], 1)
        live = [p for p in post.payloads if p["source"] == "finnhub-ws" and p["ticks"]]
        self.assertEqual(live[0]["ticks"], [{"symbol": "OANDA:EUR_USD", "price": 1.08, "ts": 1}])

    def test_connecting_sends_a_heartbeat_so_the_backend_knows_the_feed_is_up(self):
        clock = FakeClock()
        sock = FakeSocket(clock, ['{"type":"ping"}'] * 3, step=1.0, then="quiet")
        f, post = make_feed(clock, FakeConnector(clock, [sock]))
        f.run(until=clock.t + 12)
        beats = [t for p, t in zip(post.payloads, post.times) if p == {"source": "finnhub-ws", "ticks": []}]
        self.assertGreaterEqual(len(beats), 3)  # on connect, then every 5 s while quiet
        self.assertTrue(all(b - a >= feed.HEARTBEAT_S for a, b in zip(beats, beats[1:])))
        self.assertEqual(f.stats["pings"], 3)


class FallbackTest(QuietLogs):
    def test_fallback_starts_after_15s_down_polls_hourly_and_stops_on_reconnect(self):
        clock = FakeClock()
        start = clock.t
        sock = FakeSocket(clock, [trade(("OANDA:EUR_USD", 1.08, 5))], then="quiet")
        conn = FakeConnector(clock, [])  # always fails...
        f, post = make_feed(clock, conn)
        f.run(until=start + 2 * 3600 + 60)
        fb = [t - start for p, t in zip(post.payloads, post.times) if p["source"] == "frankfurter-fallback"]
        self.assertEqual(len(fb), 3, fb)  # at ~16 s, +1 h, +2 h
        self.assertGreater(fb[0], feed.FALLBACK_TRIGGER_S)
        self.assertLessEqual(fb[0], feed.FALLBACK_TRIGGER_S + 1.0)
        self.assertAlmostEqual(fb[1] - fb[0], feed.FALLBACK_POLL_S, delta=1.0)
        self.assertTrue(f.fallback_active)
        conn.outcomes = [sock]  # ...until the network comes back
        f.run(until=clock.t + 30)
        self.assertFalse(f.fallback_active)
        after = post.payloads[len(fb) + 0:]
        self.assertTrue(any(p["source"] == "finnhub-ws" and p["ticks"] for p in after))
        self.assertEqual(sum(p["source"] == "frankfurter-fallback" for p in post.payloads), 3)

    def test_no_fallback_for_a_short_blip(self):
        clock = FakeClock()
        sock1 = FakeSocket(clock, [], then="drop")
        sock2 = FakeSocket(clock, [], then="quiet")
        conn = FakeConnector(clock, [sock1, OSError("x"), OSError("x"), sock2])  # down ~7 s
        f, post = make_feed(clock, conn)
        f.run(until=clock.t + 60)
        self.assertFalse(any(p["source"] == "frankfurter-fallback" for p in post.payloads))

    def test_a_second_outage_falls_back_15s_after_it_starts(self):
        clock = FakeClock()
        start = clock.t
        # down 25 s (one fallback at ~16 s), then connected for ~16 s, then down again
        live = FakeSocket(clock, [TimeoutError()] * 16, step=1.0, then="drop")
        conn = FakeConnector(clock, [OSError("x")] * 5 + [live])
        f, post = make_feed(clock, conn)
        f.run(until=start + 90)
        drop_at = live.dropped_at  # connected at 25 s; the 17th recv drops (~41 s)
        fb = [t for p, t in zip(post.payloads, post.times) if p["source"] == "frankfurter-fallback"]
        self.assertEqual(len(fb), 2, [t - start for t in fb])
        self.assertGreater(fb[1] - drop_at, feed.FALLBACK_TRIGGER_S)
        self.assertLess(fb[1] - drop_at, feed.FALLBACK_TRIGGER_S + 1.5)

    def test_a_failing_fallback_fetch_is_logged_and_retried_without_crashing(self):
        clock = FakeClock()
        calls = []

        def broken():
            calls.append(clock.t)
            raise urllib.error.URLError("frankfurter unreachable")

        f, post = make_feed(clock, FakeConnector(clock, []), fallback=broken)
        f.run(until=clock.t + 200)
        self.assertGreaterEqual(len(calls), 3)  # ~16 s, then every 60 s
        self.assertAlmostEqual(calls[1] - calls[0], feed.FALLBACK_RETRY_S, delta=1.0)
        self.assertIn("Frankfurter fallback unavailable", self.log_stream.getvalue())


class MalformedTest(QuietLogs):
    def test_garbage_is_logged_and_skipped_and_good_ticks_still_flow(self):
        clock = FakeClock()
        garbage = [
            "not json", "", "[]", "42", "null", '{"type":"trade"}', '{"type":"trade","data":"x"}',
            '{"type":"trade","data":[1,"a",null]}', "\x00\xff\xfe", "{" * 100000,
            "[" * 100000, '{"type":"error","msg":"Invalid symbol"}', '{"type":"news"}',
            '{"type":"ping"}',
            '{"type":"trade","data":[{"s":"OANDA:EUR_USD","p":"1.1","t":1},'
            '{"s":"OANDA:EUR_USD","p":true,"t":1},{"s":"OANDA:EUR_USD","p":-1,"t":1},'
            '{"s":"OANDA:EUR_USD","p":NaN,"t":1},{"s":"OANDA:EUR_USD","p":Infinity,"t":1},'
            '{"s":"OANDA:EUR_USD","p":1.1,"t":1.5},{"s":"","p":1.1,"t":1},{"p":1.1,"t":1},'
            '{"s":"OANDA:EUR_USD","p":1.0852,"t":1792783468459}]}',
            trade(("OANDA:USD_JPY", 156.87, 1792783468500)),
        ]
        sock = FakeSocket(clock, garbage, then="quiet")
        f, post = make_feed(clock, FakeConnector(clock, [sock]))
        f.run(until=clock.t + 5)
        ticks = [t for p in post.payloads for t in p.get("ticks", [])]
        self.assertEqual(ticks, [
            {"symbol": "OANDA:EUR_USD", "price": 1.0852, "ts": 1792783468459},
            {"symbol": "OANDA:USD_JPY", "price": 156.87, "ts": 1792783468500}])
        self.assertEqual(f.stats["malformed"], 9)  # the empty frame is not a message
        self.assertEqual(f.stats["rejected_ticks"], 11)
        self.assertEqual(f.stats["pings"], 1)
        self.assertIn("skipping malformed message", self.log_stream.getvalue())
        self.assertEqual(f.stats["connects"], 1)  # never dropped the connection over it

    def test_backend_down_drops_batches_but_keeps_running(self):
        clock = FakeClock()
        sock = FakeSocket(clock, [trade(("OANDA:EUR_USD", 1.08, i)) for i in range(1, 30)],
                          then="quiet")
        f, post = make_feed(clock, FakeConnector(clock, [sock]), post=Recorder(clock, fail=True))
        f.run(until=clock.t + 10)
        self.assertGreater(f.stats["post_failures"], 3)
        self.assertEqual(f.stats["ticks"], 29)
        self.assertEqual(f.stats["connects"], 1)


class BatchingTest(QuietLogs):
    def test_ticks_are_forwarded_in_batches_of_at_most_250ms(self):
        clock = FakeClock()
        msgs = [trade(("OANDA:EUR_USD", 1.08 + i * 1e-5, 1792783468000 + i * 100)) for i in range(40)]
        sock = FakeSocket(clock, msgs, step=0.1, then="quiet")
        f, post = make_feed(clock, FakeConnector(clock, [sock]))
        f.run(until=clock.t + 6)
        batches = [p["ticks"] for p in post.payloads if p["ticks"]]
        self.assertEqual(sum(len(b) for b in batches), 40)
        for b in batches:
            self.assertLessEqual(b[-1]["ts"] - b[0]["ts"], 300)  # 250 ms + one 100 ms step
        self.assertGreaterEqual(len(batches), 12)


class SecretTest(unittest.TestCase):
    def test_missing_key_exits_with_a_clear_message(self):
        err = io.StringIO()
        with mock.patch("sys.stderr", err):
            code = feed.main([], environ={})
        self.assertEqual(code, 2)
        self.assertIn("FINNHUB_API_KEY is not set", err.getvalue())

    def test_the_key_never_reaches_the_log(self):
        secret = "sk-TEST-SECRET-123"
        stream = io.StringIO()
        feed.setup_logging(secret=secret, stream=stream)
        clock = FakeClock()
        conn = FakeConnector(clock, [Exception("handshake failed: wss://ws.finnhub.io?token=" + secret)])
        f, _ = make_feed(clock, conn)
        f.run(until=clock.t + 3)
        feed.log.warning("explicit %s", secret)
        text = stream.getvalue()
        self.assertNotIn(secret, text)
        self.assertIn("token=***", text)

    def test_discover_sends_the_key_as_a_header_and_never_writes_it(self):
        secret = "sk-TEST-SECRET-123"
        listing = [{"symbol": s, "displaySymbol": s[6:].replace("_", "/")} for s in
                   ["OANDA:EUR_USD", "OANDA:USD_JPY", "OANDA:EUR_GBP", "OANDA:XAU_USD",
                    "OANDA:GBP_USD", "OANDA:USD_CHF"]]
        seen = {}

        class Resp(io.BytesIO):
            def __enter__(self):
                return self

            def __exit__(self, *a):
                return False

        def fake_urlopen(req, timeout=None):
            seen["url"] = req.full_url
            seen["header"] = req.get_header("X-finnhub-token")
            return Resp(json.dumps(listing).encode())

        with tempfile.TemporaryDirectory() as d, mock.patch("urllib.request.urlopen", fake_urlopen):
            out = os.path.join(d, "symbols.json")
            doc = feed.discover(secret, out)
            with open(out) as fh:
                text = fh.read()
        self.assertNotIn(secret, seen["url"])
        self.assertEqual(seen["header"], secret)
        self.assertNotIn(secret, text)
        self.assertEqual(doc["subscribe"], ["OANDA:EUR_USD", "OANDA:GBP_USD", "OANDA:USD_JPY",
                                            "OANDA:USD_CHF", "OANDA:EUR_GBP"])
        self.assertIn("USD/INR", doc["notStreamedDirectly"])
        self.assertIn("EUR/JPY", doc["notStreamedDirectly"])
        self.assertEqual(len(doc["streamedPairs"]) + len(doc["notStreamedDirectly"]), 28)


class FrankfurterTest(unittest.TestCase):
    def test_fallback_fetch_names_itself_and_builds_the_appendix_b_payload(self):
        seen = {}

        class Resp(io.BytesIO):
            def __enter__(self):
                return self

            def __exit__(self, *a):
                return False

        def fake_urlopen(req, timeout=None):
            seen["agent"] = req.get_header("User-agent")
            return Resp(json.dumps({"amount": 1.0, "base": "USD", "date": "2026-10-23",
                                    "rates": {"EUR": 0.92, "JPY": 156.9}}).encode())

        with mock.patch("urllib.request.urlopen", fake_urlopen):
            payload = feed.fetch_frankfurter()
        self.assertTrue(seen["agent"] and not seen["agent"].startswith("Python-urllib"))
        self.assertEqual(payload, {"source": "frankfurter-fallback", "base": "USD",
                                   "asOf": "2026-10-23", "rates": {"EUR": 0.92, "JPY": 156.9}})


class ReplayTest(QuietLogs):
    def test_replay_batches_by_recorded_time_flags_replay_and_skips_bad_lines(self):
        lines = [trade(("OANDA:EUR_USD", 1.08, 1000)), trade(("OANDA:EUR_USD", 1.081, 1100)),
                 "garbage", '{"type":"ping"}', trade(("OANDA:USD_JPY", 156.0, 1400)),
                 trade(("OANDA:EUR_USD", 1.082, 3000), ("OANDA:EUR_GBP", 0.86, 3050))]
        with tempfile.NamedTemporaryFile("w", suffix=".jsonl", delete=False) as tmp:
            tmp.write("\n".join(lines) + "\n\n")
        try:
            got, sleeps = [], []
            stats = feed.replay(tmp.name, got.append, speed=1.0, sleep=sleeps.append)
        finally:
            os.unlink(tmp.name)
        self.assertEqual([len(p["ticks"]) for p in got], [2, 1, 2])
        self.assertTrue(all(p["replay"] is True and p["source"] == "finnhub-ws" for p in got))
        self.assertEqual(stats["malformed"], 1)
        self.assertEqual(stats["ticks"], 5)
        self.assertEqual([round(s, 3) for s in sleeps], [0.4, 1.6])

    @unittest.skipUnless(os.path.exists(SAMPLE), "no committed capture")
    def test_the_committed_live_capture_parses_cleanly(self):
        ticks = bad = 0
        with open(SAMPLE, encoding="utf-8") as f:
            for line in f:
                kind, t, rejected = feed.parse_message(line)
                self.assertEqual(kind, "trade")
                ticks += len(t)
                bad += rejected
        self.assertEqual(bad, 0)
        self.assertGreater(ticks, 1000)


if __name__ == "__main__":
    logging.disable(logging.NOTSET)
    unittest.main()
