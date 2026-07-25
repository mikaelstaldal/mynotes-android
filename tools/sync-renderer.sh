#!/usr/bin/env bash
#
# Refreshes app/src/main/assets/renderer/ — the vendored MyNotes render kit that
# NoteDetailScreen loads in a WebView to display notes.
#
# The kit *is* the web client's Markdown pipeline (markdown-it → DOMPurify, plus
# Mermaid, AsciiMath, inline Lucide icons, emoji shortcodes, callouts, wikilinks)
# and its stylesheet, packaged as a static page exposing globalThis.MyNotesRender.
# Vendoring it verbatim is what keeps this app at feature parity with the web UI
# without a second implementation of the dialect: there is nothing here to keep
# in sync by hand.
#
# This supersedes the old gen-mermaid.sh / gen-lucide-icons.sh / gen-emoji.sh,
# which vendored three pieces of that pipeline for the Kotlin renderer.
#
# The mynotes repo owns the file list, so this just delegates to its
# tools/dist-renderer.sh. That script is a plain copy of build output — run
# ./build.sh in the mynotes repo first.
#
# Usage: tools/sync-renderer.sh [path-to-mynotes-repo]
#   Defaults to ../mynotes relative to this repo's root.
#
# Commit the result: the app must render offline, without the server.
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
mynotes="${1:-$repo_root/../mynotes}"
dist="$mynotes/tools/dist-renderer.sh"
dest="$repo_root/app/src/main/assets/renderer"

if [[ ! -x "$dist" ]]; then
    echo "error: $dist not found or not executable (pass the mynotes repo path as \$1)" >&2
    exit 1
fi

"$dist" "$dest"

echo "Vendored the render kit into $dest — commit the result."
