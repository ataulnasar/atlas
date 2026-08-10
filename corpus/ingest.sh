#!/usr/bin/env bash
# Uploads every file in corpus/files/ to a running Atlas instance via POST /api/documents,
# then polls each document's status endpoint until it reaches READY or FAILED. Prints a
# final table: filename, status, chunk count, error (if any).
#
# By default, any document that ends up FAILED is retried once automatically (embedding rate
# limits are the usual transient cause on large corpora, and re-uploading identical bytes safely
# resets a FAILED document to PENDING and re-ingests it). Disable with --no-retry.
#
# Env vars:
#   ATLAS_BASE_URL       Base URL of the running Atlas instance (default: http://localhost:8080)
#   ATLAS_API_KEY        Optional; sent as the X-API-Key header if set (required when the server
#                        has auth enabled — i.e. ATLAS_API_KEY is set on the server side).
#   ATLAS_INGEST_RETRY   1 (default) to retry FAILED documents once; 0 to disable.
#
# Flags:
#   --no-retry           Disable the automatic single retry pass (same as ATLAS_INGEST_RETRY=0).
#
# Requires: curl, jq.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
FILES_DIR="${SCRIPT_DIR}/files"

BASE_URL="${ATLAS_BASE_URL:-http://localhost:8080}"
API_KEY="${ATLAS_API_KEY:-}"

POLL_INTERVAL_SECONDS=2
POLL_TIMEOUT_SECONDS=180

RETRY="${ATLAS_INGEST_RETRY:-1}"
for arg in "$@"; do
  [ "$arg" = "--no-retry" ] && RETRY=0
done

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

# Uploads one file. Sets _UP_ID to the document id (empty on failure) and _UP_ERR to an error
# message (empty on success). A 409 (identical content already ingested) is treated as success and
# tracks the existing document; a FAILED document's bytes re-uploaded here reset it and re-ingest.
upload_one() {
  local filepath="$1" response http_code body msg
  _UP_ID=""
  _UP_ERR=""
  response=$(curl -sS -o - -w '\n%{http_code}' --max-time 60 \
    "${auth_args[@]+"${auth_args[@]}"}" \
    -F "file=@${filepath};type=application/pdf" \
    "${BASE_URL}/api/documents") || {
    _UP_ERR="upload request failed (network error)"
    return
  }
  http_code=$(printf '%s' "$response" | tail -n1)
  body=$(printf '%s' "$response" | sed '$d')
  case "$http_code" in
    201) _UP_ID=$(printf '%s' "$body" | jq -r '.id') ;;
    409) _UP_ID=$(printf '%s' "$body" | jq -r '.existingDocumentId') ;;
    *)
      msg=$(printf '%s' "$body" | jq -r '.message // .error // "unknown error"' 2>/dev/null \
        || echo "unknown error")
      _UP_ERR="upload rejected (HTTP ${http_code}: ${msg})"
      ;;
  esac
}

# Polls a document to a terminal state. Sets _P_STATUS (READY/FAILED/TIMEOUT), _P_CHUNKS, _P_ERR.
poll_to_terminal() {
  local doc_id="$1" elapsed=0 status="PENDING" chunk_count="0" error_message="" status_response
  while [ "$elapsed" -lt "$POLL_TIMEOUT_SECONDS" ]; do
    status_response=$(curl -sS --max-time 20 "${auth_args[@]+"${auth_args[@]}"}" \
      "${BASE_URL}/api/documents/${doc_id}") || true
    status=$(printf '%s' "$status_response" | jq -r '.status // "UNKNOWN"' 2>/dev/null || echo "UNKNOWN")
    chunk_count=$(printf '%s' "$status_response" | jq -r '.chunkCount // 0' 2>/dev/null || echo 0)
    error_message=$(printf '%s' "$status_response" | jq -r '.errorMessage // ""' 2>/dev/null || echo "")

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

  _P_STATUS="$status"
  _P_CHUNKS="$chunk_count"
  _P_ERR="$error_message"
}

# True if an error message looks like an embedding/rate-limit failure (the retryable kind).
is_embedding_error() {
  printf '%s' "$1" | grep -qiE 'embed|rate.?limit|429|tokens? per (min|minute)|TPM|quota'
}

declare -a NAMES IDS UPLOAD_ERRORS

echo "Uploading documents to ${BASE_URL} ..."
for filepath in "$FILES_DIR"/*.pdf; do
  [ -e "$filepath" ] || continue
  upload_one "$filepath"
  NAMES+=("$(basename "$filepath")")
  IDS+=("$_UP_ID")
  UPLOAD_ERRORS+=("$_UP_ERR")
done

echo "Polling document status until READY or FAILED (timeout ${POLL_TIMEOUT_SECONDS}s each) ..."

declare -a FINAL_STATUS FINAL_CHUNKS FINAL_ERROR

for i in "${!NAMES[@]}"; do
  if [ -z "${IDS[$i]}" ]; then
    FINAL_STATUS+=("UPLOAD_FAILED")
    FINAL_CHUNKS+=("-")
    FINAL_ERROR+=("${UPLOAD_ERRORS[$i]}")
    continue
  fi
  poll_to_terminal "${IDS[$i]}"
  FINAL_STATUS+=("$_P_STATUS")
  FINAL_CHUNKS+=("$_P_CHUNKS")
  FINAL_ERROR+=("$_P_ERR")
done

# Single automatic retry pass for documents that reached FAILED. Default on because on large
# corpora the dominant failure is a transient OpenAI 429 (TPM) during embedding, which a single
# re-ingest usually clears; it only re-touches FAILED docs, and genuinely broken files simply stay
# FAILED after one more attempt. Bounded to one pass to avoid looping on a permanent failure.
if [ "$RETRY" != "0" ]; then
  retry_idx=()
  for i in "${!NAMES[@]}"; do
    [ "${FINAL_STATUS[$i]}" = "FAILED" ] && retry_idx+=("$i")
  done
  if [ "${#retry_idx[@]}" -gt 0 ]; then
    echo
    echo "Retrying ${#retry_idx[@]} FAILED document(s) once (--no-retry to disable) ..."
    for i in "${retry_idx[@]}"; do
      filepath="${FILES_DIR}/${NAMES[$i]}"
      [ -e "$filepath" ] || continue
      upload_one "$filepath"
      if [ -z "$_UP_ID" ]; then
        FINAL_ERROR[$i]="retry upload failed: ${_UP_ERR}"
        continue
      fi
      IDS[$i]="$_UP_ID"
      poll_to_terminal "$_UP_ID"
      FINAL_STATUS[$i]="$_P_STATUS"
      FINAL_CHUNKS[$i]="$_P_CHUNKS"
      FINAL_ERROR[$i]="$_P_ERR"
    done
  fi
fi

echo
printf "%-30s %-14s %8s   %s\n" "DOCUMENT" "STATUS" "CHUNKS" "ERROR"
printf "%-30s %-14s %8s   %s\n" "------------------------------" "--------------" "--------" "----------------------------------------"
ready=0
failed=0
embedding_failures=0
for i in "${!NAMES[@]}"; do
  printf "%-30s %-14s %8s   %s\n" "${NAMES[$i]}" "${FINAL_STATUS[$i]}" "${FINAL_CHUNKS[$i]}" "${FINAL_ERROR[$i]}"
  case "${FINAL_STATUS[$i]}" in
    READY) ready=$((ready + 1)) ;;
    *) failed=$((failed + 1)) ;;
  esac
  if [ "${FINAL_STATUS[$i]}" = "FAILED" ] && is_embedding_error "${FINAL_ERROR[$i]}"; then
    embedding_failures=$((embedding_failures + 1))
  fi
done
echo
echo "${#NAMES[@]} documents processed: ${ready} READY, ${failed} not READY."

if [ "$embedding_failures" -gt 0 ]; then
  echo
  echo "Hint: ${embedding_failures} document(s) failed with an embedding error. Embedding rate limits"
  echo "      (OpenAI 429 / tokens-per-minute) can cause this on large corpora — just re-run this"
  echo "      script: re-uploading identical bytes safely resets FAILED documents and re-ingests them."
fi

[ "$failed" -eq 0 ]
