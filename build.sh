#!/bin/bash
# Keep one Unix distribution path so release checks cannot drift.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec bash "$SCRIPT_DIR/build-all.sh" "$@"
