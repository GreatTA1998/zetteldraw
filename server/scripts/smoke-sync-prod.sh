#!/usr/bin/env bash
# Prod smoke: confirm PUT/GET /sync/blob/:hash are registered on the live sync API.
#
# Pass:  HTTP 401/403 with no/invalid auth (route exists; auth required).
# Fail:  HTTP 404 (Fastify "Route PUT:/sync/blob/… not found" — not deployed yet).
#
# Tip-ship: run before any APK that calls new /sync/* routes:
#   cd server && npm run smoke:prod
# See docs/CORRECTNESS.md § Tip ship / sync routes.
set -euo pipefail

BASE="${SYNC_URL:-https://zetteldraw-sync.onrender.com}"
HASH="0000000000000000000000000000000000000000000000000000000000000000"
TMP="$(mktemp)"
trap 'rm -f "$TMP"' EXIT

fail() {
  echo "smoke-sync-prod FAIL: $*" >&2
  exit 1
}

check() {
  local method="$1" url="$2" label="$3"
  local code body
  if [[ "$method" == "PUT" ]]; then
    code="$(curl -sS -o "$TMP" -w "%{http_code}" -X PUT \
      -H "Content-Type: application/octet-stream" \
      --data-binary "smoke" \
      --max-time 90 \
      "$url")" || fail "$label curl failed"
  else
    code="$(curl -sS -o "$TMP" -w "%{http_code}" -X GET \
      --max-time 90 \
      "$url")" || fail "$label curl failed"
  fi
  body="$(head -c 300 "$TMP")"
  if [[ "$code" == "404" ]]; then
    fail "$label -> 404 (route missing / not deployed). Body: $body"
  fi
  if [[ "$code" == "401" || "$code" == "403" ]]; then
    echo "ok $label -> $code (route registered; auth required)"
    return 0
  fi
  fail "$label -> unexpected HTTP $code (want 401 unauth or fail on 404). Body: $body"
}

echo "smoke-sync-prod against $BASE"
check PUT "$BASE/sync/blob/${HASH}?device_id=smoke" "PUT /sync/blob/:hash"
check GET "$BASE/sync/blob/${HASH}" "GET /sync/blob/:hash"
echo "smoke-sync-prod PASS"
