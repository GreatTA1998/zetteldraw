#!/usr/bin/env bash
# Brings up Postgres + MinIO + the sync service with docker compose, runs the
# push/pull round-trip test against it, and optionally the Android client's
# end-to-end test (WITH_ANDROID=1), then tears the stack down.
set -euo pipefail
cd "$(dirname "$0")/.."

export COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-zetteldraw-it}"
export DEVICE_TOKENS="${DEVICE_TOKENS:-dev-device-token}"
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

SYNC_URL=http://127.0.0.1:8787 SYNC_TOKEN="${DEVICE_TOKENS%%,*}" npx vitest run test/integration

if [ "${WITH_ANDROID:-0}" = "1" ]; then
  (cd .. && ZD_SYNC_URL=http://127.0.0.1:8787 ZD_SYNC_TOKEN="${DEVICE_TOKENS%%,*}" \
    ./gradlew :app:testDebugUnitTest --tests '*SyncEndToEndTest*' --rerun-tasks -q)
  echo "Android client end-to-end: ok"
fi
