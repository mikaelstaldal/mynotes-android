#!/usr/bin/env bash
#
# Regenerates app/src/main/resources/mermaid/mermaid.min.js — the vendored Mermaid
# engine the note WebView loads to render ```mermaid diagrams offline (see
# MermaidRenderer.kt).
#
# The web client renders the same diagrams in the browser from the identical
# Mermaid package (mynotes/web/ts/vendor/node_modules/mermaid, pinned in its
# package-lock.json). This script copies that package's prebuilt global bundle
# (dist/mermaid.min.js, which assigns globalThis["mermaid"]) verbatim, so the
# app's engine can never drift from the version the web previews use.
#
# Usage: tools/gen-mermaid.sh [path-to-mynotes-repo]
#   Defaults to ../mynotes relative to this repo's root.
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
mynotes="${1:-$repo_root/../mynotes}"
src="$mynotes/web/ts/vendor/node_modules/mermaid/dist/mermaid.min.js"
dest="$repo_root/app/src/main/resources/mermaid/mermaid.min.js"

if [[ ! -f "$src" ]]; then
    echo "error: $src not found (run 'npm install' in mynotes/web/ts/vendor, or pass the mynotes repo path as \$1)" >&2
    exit 1
fi

# The dist bundle must be the global/IIFE build (it ends by assigning
# globalThis["mermaid"]) so a classic <script> tag exposes window.mermaid; the
# ESM builds (mermaid.core.mjs) would not. Guard against a future package layout
# change silently vendoring the wrong file.
if ! grep -q 'globalThis\["mermaid"\]' "$src"; then
    echo "error: $src is not the global build (no globalThis[\"mermaid\"] assignment)" >&2
    exit 1
fi

mkdir -p "$(dirname "$dest")"
cp "$src" "$dest"

version="$(grep -m1 '"version"' "$mynotes/web/ts/vendor/node_modules/mermaid/package.json" | sed -E 's/.*"version": *"([^"]+)".*/\1/')"
echo "Wrote Mermaid ${version:-?} ($(wc -c < "$dest") bytes) to $dest"
