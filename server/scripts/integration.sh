#!/usr/bin/env bash
# Brings up Postgres + MinIO + the sync service with docker compose, runs the
# push/pull round-trip test against it, and optionally the Android client's
# end-to-end test (WITH_ANDROID=1), then tears the stack down.
#
# Account/claim tests run on a fresh volume so claiming the shared library does
# not empty the unowned namespace that device-token tests use.
set -euo pipefail
cd "$(dirname "$0")/.."

export COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-zetteldraw-it}"
export DEVICE_TOKENS="${DEVICE_TOKENS:-dev-device-token}"
export ALLOW_TEST_GOOGLE_TOKENS="${ALLOW_TEST_GOOGLE_TOKENS:-true}"
export SESSION_SECRET="${SESSION_SECRET:-local-dev-session-secret}"
export REJECT_DEVICE_TOKENS_AFTER_CLAIM="${REJECT_DEVICE_TOKENS_AFTER_CLAIM:-false}"
KEEP="${KEEP:-0}"

cleanup() {
  if [ "$KEEP" != "1" ]; then
    docker compose down -v --remove-orphans >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

docker compose up -d --build --wait sync
curl -fsS http://127.0.0.1:8787/healthz
echo

SYNC_URL=http://127.0.0.1:8787 SYNC_TOKEN="${DEVICE_TOKENS%%,*}" \
  npx vitest run test/integration/roundtrip.test.ts test/integration/web.test.ts

# Fresh DB for claim (singleton library_claim would otherwise break device-token tests).
docker compose down -v --remove-orphans >/dev/null 2>&1 || true
docker compose up -d --build --wait sync
curl -fsS http://127.0.0.1:8787/healthz
echo

SYNC_URL=http://127.0.0.1:8787 SYNC_TOKEN="${DEVICE_TOKENS%%,*}" \
  npx vitest run test/integration/accounts.test.ts

if [ "${WITH_ANDROID:-0}" = "1" ]; then
  (cd .. && ZD_SYNC_URL=http://127.0.0.1:8787 ZD_SYNC_TOKEN="${DEVICE_TOKENS%%,*}" \
    ./gradlew :app:testDebugUnitTest --tests '*SyncEndToEndTest*' --rerun-tasks -q)
  echo "Android client end-to-end: ok"
fi
