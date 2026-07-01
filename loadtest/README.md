# OLTP Streaming Load Simulator + Gap-to-Netflix Visualization

A YouTube-sourced data simulator that load-tests the StreamFlix **OLTP streaming path**
(watch/rate/like writes → Postgres INSERT + Kafka → consumers, plus catalog/recommendation reads)
and visualizes how far the backend is from a **"lite Netflix-like"** SLO bar.

## Pieces

| Piece | Where | What it does |
| --- | --- | --- |
| Catalog puller | `scripts/youtube-catalog-pull.mjs` | One-time bulk pull from the real YouTube Data API → `video-service/.../catalog/youtube-catalog-large.json` (quota-aware, resumable) |
| Catalog seeder | `video-service/.../config/CatalogSeeder.java` | Prefers the large catalog, batch-inserts it on first boot |
| User seeder | `user-service/.../config/BulkUserSeeder.java` | Seeds `APP_SEED_USERS` synthetic accounts for realistic write spread |
| Load harness | `loadtest/k6/` | k6 mixed read+write profile; thresholds encode the SLO bar |
| SLO targets | `loadtest/targets.json` | The "lite Netflix-like" bar (also mirrored in k6 config + Grafana) |
| Observability | `monitoring/` | Prometheus (Actuator + postgres_exporter + k6 remote-write) + Grafana dashboards |
| Gap report | `scripts/gap-report.mjs` | `measured vs target vs %gap` table → `loadtest/results/gap-report.md` |

## Run it

```bash
# 1. (once) Build the large catalog from the real API — needs YOUTUBE_API_KEY
YOUTUBE_API_KEY=... node scripts/youtube-catalog-pull.mjs --target 5000

# 2. Bring up the stack + monitoring, seeding 50k users
APP_SEED_USERS=50000 docker compose --profile monitoring up --build -d
#    Grafana: http://localhost:3001  (anonymous view enabled; admin/admin)
#    Dashboards: "Gap Scorecard", "OLTP Streaming", "Read Path"

# 3. Drive load (writes at TARGET_EPS + concurrent reads), streaming metrics to Grafana
docker compose --profile loadtest run --rm -e TARGET_EPS=500 -e DURATION=5m k6

# 4. Generate the gap report from the k6 summary + live Prometheus
node scripts/gap-report.mjs

# 5. Push harder to find the breaking point — where p95/pool/errors cross the target
docker compose --profile loadtest run --rm -e TARGET_EPS=2000 -e DURATION=5m k6
```

All knobs (`TARGET_EPS`, `READ_VUS`, `DURATION`, `USER_POOL`, `APP_SEED_USERS`) are env vars — see
`.env.example`. The target SLOs live in `loadtest/targets.json` and `loadtest/k6/lib/config.js`.

## The gap

The k6 thresholds and the Grafana **Gap Scorecard** both compare live performance to the target bar
(read p95 < 200ms, write p95 < 250ms, reco p95 < 150ms, error rate < 0.1%, ~500 events/sec sustained,
DB pool < 80%). Green = met; red = the remaining distance to a lite Netflix-like platform. Ramp
`TARGET_EPS` until tiles go red — that crossing point is the visualized gap.
