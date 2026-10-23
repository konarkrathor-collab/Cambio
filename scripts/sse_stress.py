#!/usr/bin/env python3
"""Stage 6 failure drill 5: stress GET /api/stream (Python standard library only).

  python3 scripts/sse_stress.py --base URL --duration S [--readers 30]

Opens READERS streams that read every event, one stream that never reads a byte after its
request (its server-side queue must fill and the server must drop it), and one client that
opens a stream, reads for a moment, closes it and reconnects, in a loop. At the end it prints
how many events each reader received (they should agree: nobody slow is holding them back),
the reconnect loop's count, and any 503 TOO_MANY_STREAMS it met.
"""

import argparse
import socket
import sys
import threading
import time
import urllib.parse


def open_stream(host, port, timeout=5.0):
    s = socket.create_connection((host, port), timeout=timeout)
    s.sendall(b"GET /api/stream HTTP/1.1\r\nHost: x\r\nAccept: text/event-stream\r\n\r\n")
    return s


def reader(host, port, stop, out, idx):
    events = 0
    closed_by_server = False
    try:
        s = open_stream(host, port)
        s.settimeout(1.0)
        buf = b""
        while not stop.is_set():
            try:
                chunk = s.recv(65536)
            except socket.timeout:
                continue
            if not chunk:
                closed_by_server = True
                break
            buf += chunk
            events += buf.count(b"\nevent: ") + (1 if buf.startswith(b"event: ") else 0)
            buf = buf[buf.rfind(b"\n") + 1:]
        s.close()
    except OSError:
        closed_by_server = True
    out[idx] = (events, closed_by_server)


def never_reads(host, port, stop, out):
    """Sends its request, then reads nothing until the end. The server's queue for it fills
    once the kernel buffers between them are full (several MB on loopback, so this takes a
    while); the server must then drop it. At the end it drains what was buffered and looks
    for the end of the stream (EOF): the sign that the server closed it."""
    s = open_stream(host, port)
    stop.wait()
    s.settimeout(3.0)
    dropped, received = False, 0
    try:
        while True:
            chunk = s.recv(1 << 20)
            if not chunk:
                dropped = True
                break
            received += len(chunk)
    except socket.timeout:
        dropped = False
    except OSError:
        dropped = True
    s.close()
    out["never_reads_dropped"] = dropped
    out["never_reads_buffered"] = received


def reconnect_loop(host, port, stop, out):
    n, refused = 0, 0
    while not stop.is_set():
        try:
            s = open_stream(host, port)
            s.settimeout(1.0)
            head = s.recv(4096)
            if b" 503 " in head.split(b"\r\n", 1)[0]:
                refused += 1
            else:
                n += 1
            time.sleep(0.2)
            s.close()
        except OSError:
            refused += 1
        time.sleep(0.05)
    out["reconnects"] = n
    out["refused"] = refused


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--base", required=True)
    p.add_argument("--duration", type=float, required=True)
    p.add_argument("--readers", type=int, default=30)
    a = p.parse_args()
    u = urllib.parse.urlparse(a.base)
    stop = threading.Event()
    results = {}
    out = {}
    threads = [threading.Thread(target=reader, args=(u.hostname, u.port, stop, results, i)) for i in range(a.readers)]
    threads.append(threading.Thread(target=never_reads, args=(u.hostname, u.port, stop, out)))
    threads.append(threading.Thread(target=reconnect_loop, args=(u.hostname, u.port, stop, out)))
    for t in threads:
        t.start()
    time.sleep(a.duration)
    stop.set()
    for t in threads:
        t.join()
    counts = [results[i][0] for i in range(a.readers)]
    cut = sum(1 for i in range(a.readers) if results[i][1])
    print(f"readers: {a.readers}, events each: min {min(counts)}, max {max(counts)}; closed by the server: {cut}")
    print(f"never-reading client dropped by the server: {out.get('never_reads_dropped')} "
          f"({out.get('never_reads_buffered')} bytes were still buffered in the kernel when it read)")
    print(f"reconnect loop: {out.get('reconnects')} streams opened and closed, {out.get('refused')} refused")
    return 0


if __name__ == "__main__":
    sys.exit(main())
