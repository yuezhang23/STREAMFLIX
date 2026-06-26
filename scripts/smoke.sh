#!/usr/bin/env bash
# End-to-end smoke test through the API gateway. Exercises auth, catalog, the Kafka event flow,
# the OLAP ETL, recommendations and analytics. Run after `docker compose up --build`.
set -euo pipefail
BASE="${BASE:-http://localhost:8080}"

say() { printf "\n\033[1;36m== %s ==\033[0m\n" "$1"; }

say "Login (seeded user alice)"
TOKEN=$(curl -s -X POST "$BASE/api/users/login" -H 'Content-Type: application/json' \
  -d '{"email":"alice@streamflix.dev","password":"password"}' | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
[ -z "$TOKEN" ] && { echo "login failed"; exit 1; }
echo "token: ${TOKEN:0:24}..."

say "Auth enforcement (no token -> 401 expected)"
curl -s -o /dev/null -w 'GET /api/recommendations/1 without token -> %{http_code}\n' \
  "$BASE/api/recommendations/1"

say "Catalog (public browse)"
curl -s "$BASE/api/videos?size=3" | head -c 400; echo

say "Record behavior (publishes to Kafka)"
curl -s -o /dev/null -w 'watch -> %{http_code}\n' -X POST "$BASE/api/videos/3/watch" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"watchedSec":900}'
curl -s -o /dev/null -w 'rate  -> %{http_code}\n' -X POST "$BASE/api/videos/3/rate" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"rating":5}'

say "Run OLAP ETL"
curl -s -X POST "$BASE/api/analytics/etl/run" -H "Authorization: Bearer $TOKEN"; echo

say "Trending (from OLAP)"
curl -s "$BASE/api/analytics/trending?limit=5" -H "Authorization: Bearer $TOKEN"; echo

say "Overview (from OLAP)"
curl -s "$BASE/api/analytics/overview" -H "Authorization: Bearer $TOKEN"; echo

say "Recommendations (collaborative filtering)"
curl -s "$BASE/api/recommendations/1" -H "Authorization: Bearer $TOKEN"; echo

echo; echo "Smoke test complete."
