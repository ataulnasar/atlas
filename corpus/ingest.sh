#!/usr/bin/env bash
# Uploads every file in corpus/files/ to a running Atlas instance via POST /api/documents,
# then polls each document's status endpoint until it reaches READY or FAILED. Prints a
# final table: filename, status, chunk count, error (if any).
#
# Env vars:
#   ATLAS_BASE_URL  Base URL of the running Atlas instance (default: http://localhost:8080)
#   ATLAS_API_KEY   Optional; sent as the X-API-Key header if set (Atlas does not yet
#                   enforce it — see docs/plan.md Phase 3 — but scripts should be ready
#                   for when it does)
#
# Requires: curl, jq.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
FILES_DIR="${SCRIPT_DIR}/files"

BASE_URL="${ATLAS_BASE_URL:-http://localhost:8080}"
API_KEY="${ATLAS_API_KEY:-}"

POLL_INTERVAL_SECONDS=2
POLL_TIMEOUT_SECONDS=180

if ! command -v jq >/dev/null 2>&1; then
  echo "error: jq is required but not found on PATH" >&2
  exit 1
fi

if [ ! -d "$FILES_DIR" ] || [ -z "$(ls -A "$FILES_DIR" 2>/dev/null)" ]; then
  echo "error: no files found in $FILES_DIR — run download.sh first" >&2
  exit 1
fi

auth_args=()
if [ -n "$API_KEY" ]; then
  auth_args=(-H "X-API-Key: ${API_KEY}")
fi

declare -a NAMES IDS UPLOAD_ERRORS

echo "Uploading documents to ${BASE_URL} ..."
for filepath in "$FILES_DIR"/*.pdf; do
  [ -e "$filepath" ] || continue
  filename="$(basename "$filepath")"

  response=$(curl -sS -o - -w '\n%{http_code}' --max-time 60 \
    "${auth_args[@]+"${auth_args[@]}"}" \
    -F "file=@${filepath};type=application/pdf" \
    "${BASE_URL}/api/documents") || {
    NAMES+=("$filename")
    IDS+=("")
    UPLOAD_ERRORS+=("upload request failed (network error)")
    continue
  }

  http_code=$(echo "$response" | tail -n1)
  body=$(echo "$response" | sed '$d')

  case "$http_code" in
    201)
      doc_id=$(echo "$body" | jq -r '.id')
      NAMES+=("$filename")
      IDS+=("$doc_id")
      UPLOAD_ERRORS+=("")
      ;;
    409)
      # Same content already ingested in a prior run — track the existing document
      # instead of treating a re-run of this script as a failure.
      doc_id=$(echo "$body" | jq -r '.existingDocumentId')
      NAMES+=("$filename")
      IDS+=("$doc_id")
      UPLOAD_ERRORS+=("")
      ;;
    *)
      msg=$(echo "$body" | jq -r '.message // .error // "unknown error"' 2>/dev/null || echo "unknown error")
      NAMES+=("$filename")
      IDS+=("")
      UPLOAD_ERRORS+=("upload rejected (HTTP ${http_code}: ${msg})")
      ;;
  esac
done

echo "Polling document status until READY or FAILED (timeout ${POLL_TIMEOUT_SECONDS}s each) ..."

declare -a FINAL_STATUS FINAL_CHUNKS FINAL_ERROR

for i in "${!NAMES[@]}"; do
  doc_id="${IDS[$i]}"

  if [ -z "$doc_id" ]; then
    FINAL_STATUS+=("UPLOAD_FAILED")
    FINAL_CHUNKS+=("-")
    FINAL_ERROR+=("${UPLOAD_ERRORS[$i]}")
    continue
  fi

  elapsed=0
  status="PENDING"
  chunk_count="0"
  error_message=""
  while [ "$elapsed" -lt "$POLL_TIMEOUT_SECONDS" ]; do
    status_response=$(curl -sS --max-time 20 "${auth_args[@]+"${auth_args[@]}"}" "${BASE_URL}/api/documents/${doc_id}") || true
    status=$(echo "$status_response" | jq -r '.status // "UNKNOWN"')
    chunk_count=$(echo "$status_response" | jq -r '.chunkCount // 0')
    error_message=$(echo "$status_response" | jq -r '.errorMessage // ""')

    if [ "$status" = "READY" ] || [ "$status" = "FAILED" ]; then
      break
    fi

    sleep "$POLL_INTERVAL_SECONDS"
    elapsed=$((elapsed + POLL_INTERVAL_SECONDS))
  done

  if [ "$status" != "READY" ] && [ "$status" != "FAILED" ]; then
    error_message="timed out after ${POLL_TIMEOUT_SECONDS}s waiting for terminal status (last seen: ${status})"
    status="TIMEOUT"
  fi

  FINAL_STATUS+=("$status")
  FINAL_CHUNKS+=("$chunk_count")
  FINAL_ERROR+=("$error_message")
done

echo
printf "%-30s %-14s %8s   %s\n" "DOCUMENT" "STATUS" "CHUNKS" "ERROR"
printf "%-30s %-14s %8s   %s\n" "------------------------------" "--------------" "--------" "----------------------------------------"
ready=0
failed=0
for i in "${!NAMES[@]}"; do
  printf "%-30s %-14s %8s   %s\n" "${NAMES[$i]}" "${FINAL_STATUS[$i]}" "${FINAL_CHUNKS[$i]}" "${FINAL_ERROR[$i]}"
  case "${FINAL_STATUS[$i]}" in
    READY) ready=$((ready + 1)) ;;
    *) failed=$((failed + 1)) ;;
  esac
done
echo
echo "${#NAMES[@]} documents processed: ${ready} READY, ${failed} not READY."

[ "$failed" -eq 0 ]
