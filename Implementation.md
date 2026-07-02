# Implementation.md — Current stage: the OLTP streaming path

This document covers the stage of work that is currently the focus: the **OLTP write path** and
the **YouTube-sourced data simulator** that load-tests it, plus how the gap to a "lite
Netflix-like" service is measured. For the stable, high-level project map (architecture, module
layout, conventions, ports, commands) see [CLAUDE.md](./CLAUDE.md).

## Why OLTP is the current focus

The recommendation and analytics pipelines already work end to end. The active question now is
**how the transactional write path holds up under realistic streaming load** — thousands of
watch/rate/like writes per second landing in Postgres *and* fanning out to Kafka, while catalog
and recommendation reads run concurrently. So the current stage is about (1) making the OLTP path
production-shaped and (2) building a data simulator + observability to see how far it is from a
mid-scale SLO bar.

## The OLTP write path

Every user action follows the same rule: **persist to the OLTP source of truth AND publish a
Kafka event.** Downstream services never read `video-service`'s tables — they react to the event.

```
POST /api/videos/{id}/{view|watch|rate|like}
        │  (gateway injects X-User-Id)
        ▼
VideoController ──► BehaviorService  ─┬─► Postgres (streaming_oltp, schema "video")
                                      │       watch_events / ratings   (VIEW & LIKE are event-only)
                                      └─► BehaviorEventPublisher ──► Kafka topic user-behavior
                                                                     key = userId (per-user ordering)
```

Code: `video-service/src/main/java/com/streamflix/video/`
- `web/VideoController.java` — write endpoints return **202 Accepted** (fire-and-forward semantics).
- `service/BehaviorService.java` — `@Transactional` for `watch`/`rate` (DB write); `view`/`like`
  are event-only. `rate` upserts (unique `(user_id, video_id)`).
- `event/BehaviorEventPublisher.java` — builds `UserBehaviorEvent` (UUID `eventId`, keyed by
  `userId`) and sends to the `user-behavior` topic.

### OLTP schema (owned by video-service, schema `video`)

`db/migration/V1__init_videos.sql` + `V2__catalog_indexes.sql`:

| Table | Grain | Notes |
| --- | --- | --- |
| `videos` | one row per catalog video | metadata only — `youtube_id`, `title`, `genre`, `channel`, `duration_sec`, `release_year`, `thumbnail_url`. No media. Seeded at startup, not in migration. |
| `watch_events` | one row per watch | highest-volume write table; `(user_id, created_at DESC)` composite index for per-user recency reads |
| `ratings` | one row per (user, video) | `UNIQUE(user_id, video_id)`, `CHECK rating BETWEEN 1 AND 5` |

Indexes tuned for scale: `idx_videos_genre`, `idx_videos_release_year`, a **pg_trgm GIN** index
on `title` (keeps ILIKE search off a sequential scan as the catalog grows to thousands), and the
`watch_events` composite above.

### The event contract (`common`)

`UserBehaviorEvent(eventId, userId, videoId, type, value, timestamp)` on topic `user-behavior`.
`value` is type-specific: seconds watched for `WATCH`, rating 1–5 for `RATE`, else 1. `eventId`
(UUID) makes consumers idempotent under at-least-once delivery. Consumed independently by
recommendation-service (group `reco`) and analytics-service (group `analytics`).

## The data simulator (YouTube-sourced)

Four pieces generate realistic OLTP load. All behavior goes through `BehaviorService`, so seeded
and live traffic hit the exact same write path (Postgres + Kafka) as a real user.

| Piece | Where | What it does |
| --- | --- | --- |
| **Catalog puller** | `scripts/youtube-catalog-pull.mjs` | one-time bulk pull from the real YouTube Data API (search.list → videos.list, batched 50) → `video-service/.../catalog/youtube-catalog-large.json`. Quota-aware (`--max-units`), resumable checkpoint. Load runs OFFLINE against this cached file. |
| **Catalog seeder** | `video-service/.../config/CatalogSeeder.java` (`@Order(1)`) | on first boot prefers the large catalog, falls back to the curated `youtube-seed.json` (~66 videos, 8 sub-genres). Single JDBC batch (500/batch). |
| **User seeder** | `user-service/.../config/BulkUserSeeder.java` | seeds `APP_SEED_USERS` synthetic accounts (`loadtest+{n}@streamflix.dev`, shared BCrypt hash, idempotent `ON CONFLICT`) so writes spread across many user ids. `0` = off. |
| **Behavior seeder** | `video-service/.../config/BehaviorSeeder.java` (`@Order(2)`) | on first boot generates correlated watch/rate history for 400 users (each leans to one genre + cross-genre noise), all via Kafka. No-ops if behavior exists. |
| **Traffic simulator** | `video-service/.../config/TrafficSimulator.java` | `@Scheduled` — each tick emits a batch of genre-coherent events for random users. Keeps the async pipeline continuously busy so trending/recs evolve live. |

Traffic knobs: `APP_TRAFFIC_ENABLED`, `APP_TRAFFIC_EVENTS_PER_TICK`, `APP_TRAFFIC_INTERVAL_MS`,
`APP_TRAFFIC_USERS`.

## Load harness + the "gap to Netflix" bar

The k6 harness (`loadtest/k6/`) drives a mixed **read + write** profile against the OLTP path and
compares live numbers to an SLO bar. See [loadtest/README.md](./loadtest/README.md) for full details.

The target bar (`loadtest/targets.json`, mirrored in k6 config + Grafana):

| Dimension | Target |
| --- | --- |
| Scale | 5,000 catalog videos · 50,000 users |
| Throughput | ~500 events/sec sustained |
| Read p95 | < 200 ms |
| Write p95 | < 250 ms |
| Recommendation p95 | < 150 ms |
| Error rate | < 0.1% |
| DB pool utilization | < 80% |

Run it:

```bash
# 1. (once) build the large catalog from the real API
YOUTUBE_API_KEY=... node scripts/youtube-catalog-pull.mjs --target 5000

# 2. bring up the stack + monitoring, seeding 50k users
APP_SEED_USERS=50000 docker compose --profile monitoring up --build -d

# 3. drive load (writes at TARGET_EPS + concurrent reads), streaming metrics to Grafana
docker compose --profile loadtest run --rm -e TARGET_EPS=500 -e DURATION=5m k6

# 4. generate the gap report (measured vs target vs %gap)
node scripts/gap-report.mjs            # → loadtest/results/gap-report.md

# 5. push harder to find the breaking point
docker compose --profile loadtest run --rm -e TARGET_EPS=2000 -e DURATION=5m k6
```

## Observability

- **Prometheus** (`monitoring/prometheus/`) scrapes Actuator (`/actuator/prometheus`) +
  `postgres_exporter` + k6 remote-write.
- **Grafana** (`monitoring/grafana/`, http://localhost:3001, admin/admin) — dashboards:
  **Gap Scorecard** (green = SLO met, red = remaining distance), **OLTP Streaming** (write path),
  **Read Path**.
- Ramp `TARGET_EPS` until tiles go red — that crossing point *is* the visualized gap.

## Measuring the recommendation latency win

`./scripts/benchmark.sh 200` benchmarks `/api/recommendations/{userId}` two ways: a **baseline**
that computes per request (loads the interaction matrix and runs CF, cache off) vs the **two-stage**
path (precomputed candidates from warm Redis). Representative run on this stack (~7.3k interactions,
live traffic on):

```
compute per request:   mean=12.4ms  p50=10.0ms  p95=23.1ms
precomputed + cached:  mean= 6.6ms  p50= 6.3ms  p95= 8.6ms
RESULT: p50 10.0→6.3ms (-36%)   p95 23.1→8.6ms (-63%)
```

Numbers vary with hardware and seed/traffic volume — re-run and use what it prints. Tail latency
(p95) drops most, which is what matters for a feed under concurrent traffic.

## What's done vs. what's next

**Done at this stage**
- Full OLTP write path (Postgres + Kafka), idempotent consumers, schema-per-service.
- YouTube-sourced data simulator (catalog puller, bulk users, behavior seeder, live traffic).
- k6 mixed read/write harness + SLO targets + gap report.
- Prometheus/Grafana observability with a gap scorecard.
- Two-stage recommendation serving with a measured latency win.

**Natural next steps**
- Push `TARGET_EPS` toward the 500/sec bar and close whichever SLO tile goes red first
  (likely write p95 / DB pool at scale).
- Tune Hikari pool sizing, batch inserts, and Kafka producer batching for the write path.
- Partition the `user-behavior` topic and scale consumers per group.
- Consider incremental (vs recompute-and-replace) OLAP ETL as raw-event volume grows.
