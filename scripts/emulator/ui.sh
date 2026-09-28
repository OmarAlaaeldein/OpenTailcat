#!/usr/bin/env bash
# Print the visible UI nodes as "bounds | text | content-desc" (tokens redacted).
set -euo pipefail
source "$(dirname "$0")/lib.sh"
ui_nodes
