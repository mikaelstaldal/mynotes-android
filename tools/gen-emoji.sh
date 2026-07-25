#!/usr/bin/env bash
#
# Regenerates app/src/main/resources/emoji/emoji-shortcodes.json — the vendored
# GitHub-shortcode -> emoji map the app uses for the `:shortcode:` render-time
# transform (see EmojiShortcodes.kt).
#
# The map is embedded in the mynotes web bundle
# (web/static/vendor/emoji-<version>.js, EMOJI_SHORTCODES), which is generated
# from emojibase-data by web/ts/vendor/gen-emoji.mjs (regenerate there via
# web/ts/vendor/rebuild.sh). This script extracts that same object verbatim so the
# app's shortcodes can never drift from the web client's — mirroring how
# gen-lucide-icons.sh vendors the icon geometry.
#
# Usage: tools/gen-emoji.sh [path-to-mynotes-repo]
#   Defaults to ../mynotes relative to this repo's root.
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
mynotes="${1:-$repo_root/../mynotes}"
dest="$repo_root/app/src/main/resources/emoji/emoji-shortcodes.json"
marker="export const EMOJI_SHORTCODES = "

# The web bundle is versioned in its filename (emoji-<version>.js); pick the newest.
shopt -s nullglob
candidates=("$mynotes"/web/static/vendor/emoji-*.js)
shopt -u nullglob
if [[ ${#candidates[@]} -eq 0 ]]; then
    echo "error: no emoji-*.js found under $mynotes/web/static/vendor (pass the mynotes repo path as \$1)" >&2
    exit 1
fi
mapfile -t candidates < <(printf '%s\n' "${candidates[@]}" | sort -V)
src="${candidates[-1]}"

mkdir -p "$(dirname "$dest")"

# Match the marker line and emit the object verbatim (strip the prefix and the
# trailing semicolon), exactly as gen-lucide-icons.sh does for the icon geometry.
line="$(grep -m1 -F "$marker" "$src")"
json="${line#"$marker"}"
json="${json%;}"
printf '%s\n' "$json" > "$dest"

# Sanity check: valid JSON, non-empty.
count="$(python3 -c 'import json,sys; print(len(json.load(sys.stdin)))' < "$dest")"
echo "Wrote $count shortcodes to $dest (from $(basename "$src"))"
