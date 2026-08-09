#!/usr/bin/env bash
#
# Manual retrieval spot-check for the mini golden dataset (datasets/mini-golden.json).
#
# For each ANSWERABLE question it POSTs the query to all three search endpoints
# (/api/search/{vector,keyword,hybrid}, topK=5) and reports, per endpoint, hit@5
# (did the expected document appear in the top 5) and the rank of the first
# expected-document chunk. For the UNANSWERABLE question it reports the top score
# per endpoint (which should be low). No tooling/deps beyond curl + jq — this is
# the "use it manually while tuning" helper the Phase 2 card asks for; Phase 4
# replaces it with the atlas-evals Python runner.
#
# Usage:
#   ./mini-golden-check.sh [BASE_URL]
#   ATLAS_BASE_URL=http://localhost:8080 ./mini-golden-check.sh
#
# Env:
#   ATLAS_API_KEY  Optional; sent as the X-API-Key header when set. Leave unset against a
#                  keyless-dev server (backward compatible).
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DATASET="${SCRIPT_DIR}/datasets/mini-golden.json"
BASE_URL="${1:-${ATLAS_BASE_URL:-http://localhost:8080}}"
API_KEY="${ATLAS_API_KEY:-}"
TOP_K=5
ENDPOINTS=(vector keyword hybrid)

auth_args=()
if [ -n "$API_KEY" ]; then
  auth_args=(-H "X-API-Key: ${API_KEY}")
fi

command -v curl >/dev/null 2>&1 || { echo "error: curl is required" >&2; exit 1; }
command -v jq >/dev/null 2>&1 || { echo "error: jq is required" >&2; exit 1; }
[ -f "$DATASET" ] || { echo "error: dataset not found at $DATASET" >&2; exit 1; }

# Returns the endpoint's JSON body plus a trailing line with the HTTP status code.
search() {
  local endpoint="$1" query="$2"
  curl -s -w '\n%{http_code}' --max-time 60 -X POST "${BASE_URL}/api/search/${endpoint}" \
    -H "Content-Type: application/json" \
    "${auth_args[@]+"${auth_args[@]}"}" \
    -d "$(jq -n --arg q "$query" --argjson k "$TOP_K" '{query: $q, topK: $k}')"
}

echo "Mini golden retrieval spot-check"
echo "  target : ${BASE_URL}"
echo "  dataset: ${DATASET}"
echo "  topK   : ${TOP_K}"
echo

row_format='%-44s %-14s %-30s %-12s %-12s %-12s\n'
header=$(printf "$row_format" "ID" "CATEGORY" "EXPECTED" "VECTOR" "KEYWORD" "HYBRID")
printf '%s\n' "$header"
printf '%.0s-' $(seq 1 ${#header}); echo

# Index-based reads (only 5 questions) rather than a TSV split, so a null
# expected_document doesn't get collapsed by whitespace field-splitting.
question_count=$(jq '.questions | length' "$DATASET")
for i in $(seq 0 $((question_count - 1))); do
  id=$(jq -r ".questions[$i].id" "$DATASET")
  category=$(jq -r ".questions[$i].category" "$DATASET")
  expected=$(jq -r ".questions[$i].expected_document // \"\"" "$DATASET")
  question=$(jq -r ".questions[$i].question" "$DATASET")

  cells=()
  for endpoint in "${ENDPOINTS[@]}"; do
    response=$(search "$endpoint" "$question")
    http_code=$(printf '%s' "$response" | tail -n1)
    body=$(printf '%s' "$response" | sed '$d')

    if [ "$http_code" != "200" ] || ! printf '%s' "$body" | jq -e '.results' >/dev/null 2>&1; then
      cells+=("HTTP:${http_code}")
      continue
    fi

    if [ -z "$expected" ]; then
      # Unanswerable: report the top score (want it low). "none" = no results at all.
      top_score=$(printf '%s' "$body" | jq -r '.results[0].score // "none"')
      cells+=("top=${top_score}")
    else
      # Answerable: rank of the first chunk whose document is the expected one, if in top 5.
      rank=$(printf '%s' "$body" \
        | jq -r --arg d "$expected" \
            '[.results | to_entries[] | select(.value.documentFilename == $d) | .key + 1][0] // "none"')
      if [ "$rank" = "none" ]; then
        cells+=("MISS")
      else
        cells+=("hit r${rank}")
      fi
    fi
  done

  printf "$row_format" \
    "$id" "$category" "${expected:-(none)}" "${cells[0]}" "${cells[1]}" "${cells[2]}"
done

echo
echo "Legend: 'hit rN' = expected document found at rank N of top ${TOP_K}; 'MISS' = not in top ${TOP_K};"
echo "        'top=S'  = top result score for the unanswerable question (lower is better);"
echo "        'HTTP:C' = endpoint returned status C (e.g. 503 in keyless mode)."
