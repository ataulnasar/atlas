#!/usr/bin/env bash
# Downloads every document listed in corpus/manifest.json into corpus/files/.
# Idempotent: a file that already exists, is non-empty, and starts with the PDF
# magic bytes is left alone and reported as "skipped" rather than re-fetched.
#
# Requires: curl, jq.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MANIFEST="${SCRIPT_DIR}/manifest.json"
FILES_DIR="${SCRIPT_DIR}/files"

if ! command -v jq >/dev/null 2>&1; then
  echo "error: jq is required but not found on PATH" >&2
  exit 1
fi

if [ ! -f "$MANIFEST" ]; then
  echo "error: manifest not found at $MANIFEST" >&2
  exit 1
fi

mkdir -p "$FILES_DIR"

# id, filename, source_url — tab-separated, one row per document.
rows=$(jq -r '.documents[] | [.id, .filename, .source_url] | @tsv' "$MANIFEST")

declare -a SUMMARY_ID SUMMARY_STATUS SUMMARY_SIZE

is_valid_pdf() {
  local path="$1"
  [ -s "$path" ] || return 1
  # First 4 bytes must be the PDF magic string "%PDF".
  local magic
  magic=$(head -c 4 "$path" 2>/dev/null)
  [ "$magic" = "%PDF" ]
}

while IFS=$'\t' read -r id filename url; do
  [ -z "$id" ] && continue
  target="${FILES_DIR}/${filename}"

  if is_valid_pdf "$target"; then
    size=$(wc -c < "$target" | tr -d ' ')
    SUMMARY_ID+=("$id")
    SUMMARY_STATUS+=("skipped (exists)")
    SUMMARY_SIZE+=("$size")
    continue
  fi

  tmp="${target}.part"
  if curl -fsSL --max-time 60 --retry 2 --retry-delay 3 -o "$tmp" "$url"; then
    if is_valid_pdf "$tmp"; then
      mv "$tmp" "$target"
      size=$(wc -c < "$target" | tr -d ' ')
      SUMMARY_ID+=("$id")
      SUMMARY_STATUS+=("downloaded")
      SUMMARY_SIZE+=("$size")
    else
      rm -f "$tmp"
      SUMMARY_ID+=("$id")
      SUMMARY_STATUS+=("FAILED (not a valid PDF)")
      SUMMARY_SIZE+=("0")
    fi
  else
    rm -f "$tmp"
    SUMMARY_ID+=("$id")
    SUMMARY_STATUS+=("FAILED (download error)")
    SUMMARY_SIZE+=("0")
  fi
done <<< "$rows"

echo
printf "%-30s %-24s %10s\n" "DOCUMENT" "STATUS" "BYTES"
printf "%-30s %-24s %10s\n" "------------------------------" "------------------------" "----------"
failures=0
for i in "${!SUMMARY_ID[@]}"; do
  printf "%-30s %-24s %10s\n" "${SUMMARY_ID[$i]}" "${SUMMARY_STATUS[$i]}" "${SUMMARY_SIZE[$i]}"
  case "${SUMMARY_STATUS[$i]}" in
    FAILED*) failures=$((failures + 1)) ;;
  esac
done
echo
echo "${#SUMMARY_ID[@]} documents processed, ${failures} failed."

[ "$failures" -eq 0 ]
