#!/usr/bin/env python3
"""A local stand-in for Finnhub's WebSocket, for drills without a key or the network.

  python3 feed/fake_finnhub.py --port 8765 [--loophole] [--interval 0.2]
  FINNHUB_API_KEY=drill python3 feed/feed.py --ws-url ws://127.0.0.1:8765 --backend ...

Python standard library only (RFC 6455, text frames, no TLS). It accepts any token, reads
the feed's {"type":"subscribe","symbol":...} messages, and every --interval seconds sends
one {"type":"trade","data":[...]} message with a trade for each subscribed symbol it
knows, plus a {"type":"ping"} every 10 s. Prices jitter by up to 0.002% around a
consistent set; with --loophole, EUR/GBP is quoted 0.4% rich, so EUR -> GBP -> USD -> EUR
is a +0.4% cycle. Kill the process to simulate a network loss; start it again on the same
port to simulate recovery.
"""

import argparse
import base64
import hashlib
import json
import random
import socket
import struct
import sys
import threading
import time

GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
PRICES = {
    "OANDA:EUR_USD": 1.08000, "OANDA:GBP_USD": 1.30000, "OANDA:EUR_GBP": 0.830769,
    "OANDA:USD_JPY": 156.870, "OANDA:AUD_USD": 0.66000, "OANDA:USD_CHF": 0.88000,
    "OANDA:USD_CAD": 1.37000, "OANDA:NZD_USD": 0.60000,
}


def handshake(conn):
    data = b""
    while b"\r\n\r\n" not in data:
        chunk = conn.recv(4096)
        if not chunk:
            return False
        data += chunk
        if len(data) > 16384:
            return False
    key = None
    for line in data.decode("latin-1").split("\r\n"):
        if line.lower().startswith("sec-websocket-key:"):
            key = line.split(":", 1)[1].strip()
    if not key:
        return False
    accept = base64.b64encode(hashlib.sha1((key + GUID).encode()).digest()).decode()
    conn.sendall(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                  f"Sec-WebSocket-Accept: {accept}\r\n\r\n").encode())
    return True


def send_text(conn, text):
    payload = text.encode()
    n = len(payload)
    if n < 126:
        header = struct.pack("!BB", 0x81, n)
    elif n < 65536:
        header = struct.pack("!BBH", 0x81, 126, n)
    else:
        header = struct.pack("!BBQ", 0x81, 127, n)
    conn.sendall(header + payload)


def recv_exact(conn, n):
    buf = b""
    while len(buf) < n:
        chunk = conn.recv(n - len(buf))
        if not chunk:
            raise ConnectionError("closed")
        buf += chunk
    return buf


def read_frame(conn):
    """(opcode, payload) of one client frame (clients always mask)."""
    b1, b2 = recv_exact(conn, 2)
    opcode = b1 & 0x0F
    n = b2 & 0x7F
    if n == 126:
        n = struct.unpack("!H", recv_exact(conn, 2))[0]
    elif n == 127:
        n = struct.unpack("!Q", recv_exact(conn, 8))[0]
    mask = recv_exact(conn, 4) if b2 & 0x80 else b"\0\0\0\0"
    data = bytearray(recv_exact(conn, n))
    for i in range(n):
        data[i] ^= mask[i % 4]
    return opcode, bytes(data)


def serve_client(conn, addr, args, stats):
    subscribed = set()
    lock = threading.Lock()
    alive = [True]

    def reader():
        try:
            while alive[0]:
                op, data = read_frame(conn)
                if op == 0x8:  # close
                    break
                if op == 0x9:  # ping -> pong
                    with lock:
                        conn.sendall(struct.pack("!BB", 0x8A, len(data)) + data)
                    continue
                if op == 0x1:
                    try:
                        msg = json.loads(data.decode())
                    except ValueError:
                        continue
                    if msg.get("type") == "subscribe" and isinstance(msg.get("symbol"), str):
                        subscribed.add(msg["symbol"])
        except (ConnectionError, OSError):
            pass
        alive[0] = False

    threading.Thread(target=reader, daemon=True).start()
    rnd = random.Random(addr[1])
    last_ping = time.monotonic()
    stats["clients"] += 1
    try:
        while alive[0]:
            time.sleep(args.interval)
            now_ms = int(time.time() * 1000)
            data = []
            for sym in sorted(subscribed):
                if sym not in PRICES:
                    continue
                p = PRICES[sym] * (1.004 if args.loophole and sym == "OANDA:EUR_GBP" else 1.0)
                data.append({"s": sym, "p": round(p * (1 + rnd.uniform(-2e-5, 2e-5)), 6), "t": now_ms, "v": 0})
            with lock:
                if data:
                    send_text(conn, json.dumps({"type": "trade", "data": data}))
                    stats["trades"] += len(data)
                if time.monotonic() - last_ping >= 10:
                    send_text(conn, json.dumps({"type": "ping"}))
                    last_ping = time.monotonic()
    except OSError:
        pass
    finally:
        alive[0] = False
        conn.close()


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--port", type=int, required=True)
    p.add_argument("--interval", type=float, default=0.2)
    p.add_argument("--loophole", action="store_true")
    args = p.parse_args()
    srv = socket.socket()
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("127.0.0.1", args.port))
    srv.listen(8)
    stats = {"clients": 0, "trades": 0}
    print(f"fake Finnhub socket on ws://127.0.0.1:{args.port}{' (with a +0.4% loophole)' if args.loophole else ''}",
          flush=True)
    while True:
        conn, addr = srv.accept()
        if not handshake(conn):
            conn.close()
            continue
        threading.Thread(target=serve_client, args=(conn, addr, args, stats), daemon=True).start()


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        sys.exit(0)
