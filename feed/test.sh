#!/usr/bin/env bash
# Feed unit tests: a fake socket and fake clock drive the real feed loop. No key, no network.
set -euo pipefail
cd "$(dirname "$0")"
exec python3 -m unittest discover -s tests -v "$@"
