#!/usr/bin/env bash
#
# Regenerates app/src/main/resources/lucide/lucide-icon-nodes.json — the vendored
# Lucide icon geometry the app inlines into rendered notes (see LucideIcons.kt).
#
# The geometry is embedded only once, in the mynotes web bundle
# (web/static/vendor/lucide.js, LUCIDE_ICON_NODES), which is generated from
# lucide-static by web/ts/vendor/gen-lucide.mjs (regenerate there via
# web/ts/vendor/rebuild.sh). This script extracts that same object verbatim so the
# app's inlined icons can never drift from the server-served SVGs or the web
# previews — mirroring how the Go server reconstructs its icons from the identical
# source (mynotes/internal/icons/icons.go).
#
# Usage: tools/gen-lucide-icons.sh [path-to-mynotes-repo]
#   Defaults to ../mynotes relative to this repo's root.
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
mynotes="${1:-$repo_root/../mynotes}"
src="$mynotes/web/static/vendor/lucide.js"
dest="$repo_root/app/src/main/resources/lucide/lucide-icon-nodes.json"
marker="export const LUCIDE_ICON_NODES = "

if [[ ! -f "$src" ]]; then
    echo "error: $src not found (pass the mynotes repo path as \$1)" >&2
    exit 1
fi

mkdir -p "$(dirname "$dest")"

# Match the marker line and emit the object verbatim (strip the prefix and the
# trailing semicolon), exactly as mynotes/internal/icons/icons.go parseNodes does.
line="$(grep -m1 -F "$marker" "$src")"
json="${line#"$marker"}"
json="${json%;}"
printf '%s\n' "$json" > "$dest"

# Sanity check: valid JSON, non-empty.
count="$(python3 -c 'import json,sys; print(len(json.load(sys.stdin)))' < "$dest")"
echo "Wrote $count icons to $dest"
