#!/usr/bin/env bash
#
# Measures the API latency impact of the Redis cache on the recommendation endpoint
# (the expensive collaborative-filtering compute path).
#
# It restarts recommendation-service with the cache OFF, benchmarks it, then restarts with the
# cache ON, warms it, and benchmarks again — printing p50/p95 for each and the % reduction.
# The number you put on a resume should be whatever THIS prints on your machine.
#
# Usage:  ./scripts/benchmark.sh [REQUESTS]
set -euo pipefail

BASE="${BASE:-http://localhost:8080}"
REQUESTS="${1:-200}"
USERS=(1 2 3 4 5)
COMPOSE="docker compose"

login() {
  local resp
  resp=$(curl -s -X POST "$BASE/api/users/login" \
    -H 'Content-Type: application/json' \
    -d '{"email":"alice@streamflix.dev","password":"password"}')
  echo "$resp" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p'
}

wait_ready() {
  echo -n "  waiting for recommendation-service "
  for _ in $(seq 1 60); do
    if curl -s -o /dev/null -w '%{http_code}' "$BASE/api/recommendations/1" \
        -H "Authorization: Bearer $1" | grep -q '200'; then
      echo " ready"; return 0
    fi
    echo -n "."; sleep 2
  done
  echo " timeout"; exit 1
}

bench() {
  # prints a human-readable summary to stderr; echoes the p50 (ms) to stdout for the caller
  local token="$1" tmp
  tmp=$(mktemp)
  for i in $(seq 1 "$REQUESTS"); do
    local uid=${USERS[$((i % ${#USERS[@]}))]}
    curl -s -o /dev/null -w '%{time_total}\n' \
      -H "Authorization: Bearer $token" \
      "$BASE/api/recommendations/$uid" >> "$tmp"
  done
  sort -n "$tmp" -o "$tmp"
  awk '
    { v[NR]=$1*1000; sum+=$1*1000 }
    END {
      p50=v[int(NR*0.50)]; if (p50=="") p50=v[1]
      p95=v[int(NR*0.95)]; if (p95=="") p95=v[NR]
      printf "    mean=%.1fms  p50=%.1fms  p95=%.1fms\n", sum/NR, p50, p95 > "/dev/stderr"
      printf "%.4f\n", p50
    }' "$tmp"
  rm -f "$tmp"
}

echo "== StreamFlix feed-generation benchmark ($REQUESTS requests) =="

echo "[1/2] BASELINE — compute per request (no precompute, no cache)..."
APP_RECO_FORCE_COMPUTE=true CACHE_ENABLED=false $COMPOSE up -d --no-deps recommendation-service >/dev/null
TOKEN=$(login); [ -z "$TOKEN" ] && { echo "login failed"; exit 1; }
wait_ready "$TOKEN"
echo "  benchmarking (compute per request):"
P50_OFF=$(bench "$TOKEN")

echo "[2/2] OPTIMIZED — precomputed candidates + Redis cache..."
APP_RECO_FORCE_COMPUTE=false CACHE_ENABLED=true $COMPOSE up -d --no-deps recommendation-service >/dev/null
TOKEN=$(login)
wait_ready "$TOKEN"
# generate candidates + warm the cache for all benchmarked users
curl -s -o /dev/null -X POST -H "Authorization: Bearer $TOKEN" "$BASE/api/recommendations/precompute"
for uid in "${USERS[@]}"; do
  curl -s -o /dev/null -H "Authorization: Bearer $TOKEN" "$BASE/api/recommendations/$uid"
done
echo "  benchmarking (precomputed + cached):"
P50_ON=$(bench "$TOKEN")

echo
awk -v off="$P50_OFF" -v on="$P50_ON" 'BEGIN {
  if (off > 0) printf "RESULT: feed-generation p50 %.1fms -> %.1fms  =>  %.0f%% latency reduction\n", off, on, (off-on)/off*100
}'
