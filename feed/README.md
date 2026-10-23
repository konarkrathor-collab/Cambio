# Market-data feed (doc 08 Stage 2)

Fetches and forwards only. Finnhub WebSocket ticks, or a Frankfurter snapshot while the socket has been down for more than 15 s, are POSTed to the backend's `POST /api/market/rates` (doc 08 Appendix B). Cross-rates, the graph, staleness and arbitrage are all computed in Java.

```bash
python3 -m venv feed/.venv && feed/.venv/bin/pip install -r feed/requirements.txt
export FINNHUB_API_KEY=...          # never commit it; it is never printed or logged
backend/run.sh serve &               # 127.0.0.1:8080

feed/.venv/bin/python feed/feed.py --discover   # once: writes feed/symbols.json
feed/.venv/bin/python feed/feed.py              # live
feed/.venv/bin/python feed/feed.py --capture samples/finnhub_ticks_$(date -u +%F).jsonl --duration 300
python3 feed/feed.py --replay feed/samples/finnhub_ticks_2026-10-23.jsonl --speed 20   # no key needed
```

- `feed/test.sh` runs the unit tests. A fake socket and fake clock drive the real loop (reconnect backoff, resubscription, fallback, batching, malformed input, key redaction). No key or network is needed.
- `feed/replay_check.sh` runs the Stage 2 gate end to end. It starts the backend, replays the committed capture, shows the status endpoint and sends garbage input.
- `feed/live_check.sh` needs the key and an open market (Sunday evening to Friday evening UTC). It runs the feed live, then simulates an outage (switches to the Frankfurter fallback after 15 s), then recovers.
- `feed/fake_finnhub.py` is a local stand-in for Finnhub's socket (standard library only; `--loophole` streams a +0.4% EUR/GBP cycle). Use it to run live mode with no key and no market: `FINNHUB_API_KEY=placeholder python3 feed/feed.py --ws-url ws://127.0.0.1:PORT`. `scripts/drill_feed.sh` uses it for the Stage 6 feed-kill and network-loss drills.

`symbols.json` (from Finnhub, 2026-10-23): 21 of the 28 roster pairs are streamed directly. OANDA lists no INR pair at all, including USD/INR, so INR has no live rate and gets one only from the Frankfurter fallback.
