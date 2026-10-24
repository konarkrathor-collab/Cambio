# Backend

Java 17 (plain `javac --release 17`; any JDK 17 or newer runs it), no build tool, no external
jars. The matching engine, the six data structures, the arbitrage scanner, the account Vault,
the Huffman Logbook and the HTTP/SSE gateway all live in `src/ads/`.

```sh
backend/build.sh                 # compile into backend/out/classes
backend/test.sh                  # the whole backend suite (parity gates, engine, API, drills' unit tests)
backend/test.sh --scan-timing    # ... then the scanner's per-scan time at V = 7, 12 and 20
backend/run.sh serve --seed-demo-accounts      # the API on http://127.0.0.1:8080
backend/scripts/smoke.sh         # every endpoint once, curl only
```

## `run.sh serve` options

| Option | Default | Meaning |
| :--- | :--- | :--- |
| `--host H` | `127.0.0.1` | Address to bind. |
| `--port N` | `8080` | Port; `0` picks a free one. |
| `--port-file F` | | Write the bound port to F (scripts use it with `--port 0`). |
| `--data-dir D` | `data` | Accounts (`accounts.jsonl`), id counters (`counters.json`) and the Logbook archive (`logbook/`). |
| `--seed-demo-accounts` | off | Create the demo's 12 traders when the accounts file is empty. |
| `--min-profit P` | `0.02` | Loophole threshold in percent (doc 06 §5); also `MIN_PROFIT_PERCENT`. |
| `--static-dir D` | off | Serve the built dashboard (`frontend/dist`) on every path outside `/api`, so a demo needs one port. The server refuses to start if D has no `index.html`. |

### `--static-dir`

`backend/run.sh serve --static-dir ../frontend/dist` (a relative path is resolved from
`backend/`, so prefer an absolute one; `start-all.sh` passes one) serves the dashboard with:

- `Content-Type` by extension (html, js, css, svg, json, ico, png, txt, woff2; anything else
  `application/octet-stream`) and `X-Content-Type-Options: nosniff`;
- `Cache-Control: no-cache` for `index.html`, and `public, max-age=31536000, immutable` for
  the content-hashed files under `assets/`;
- `index.html` for every non-`/api` path that is not a file, so the dashboard's own paths load;
- **path-traversal protection.** The path is decoded once. A NUL byte, a backslash, or a `.`
  or `..` segment (however it was encoded) is 400. The real path, with symlinks followed,
  must stay inside the directory, so a symlink that leaves it is never served;
- GET and HEAD only (405 otherwise).

`/api/...` is untouched: an unknown API path is still the JSON 404 of the contract.
`StaticTests` in the backend suite covers each case. That includes 25 traversal attempts:
encoded, doubled and overlong encodings, absolute paths, backslashes, NUL bytes, and
symlinks out of the directory.

The API is specified in [`documentation/09_api_contract.md`](../documentation/09_api_contract.md).
Bugs found while building it, and their fixes, are in [`BUGS.md`](BUGS.md).

## The `--add-opens` flag

`run.sh` and `test.sh` always start the JVM with

```
--add-opens jdk.httpserver/sun.net.httpserver=ALL-UNNAMED
```

The event-stream hub needs it to cut off a client that stopped reading (`BUGS.md` B16).
The JDK's built-in HTTP server has no write timeout and no public way to close a
connection. When such a client is dropped, the hub closes its socket channel directly, and
that channel is an internal field of the JDK's server. **Without the flag** the server still
runs and everything else works. It falls back to the old behaviour and logs one warning:
`cannot cut off a stalled event-stream client (…); start the JVM with --add-opens …`.
With the old behaviour, a dropped client that never reads again keeps one HTTP thread and
one socket until TCP gives up on it. So launch the server with `run.sh` (or pass the flag
yourself), not with a bare `java -cp …`. `JAVA_OPTS` adds further JVM flags, e.g.
`JAVA_OPTS="-Xlog:gc:file=gc.log" backend/run.sh serve`.
