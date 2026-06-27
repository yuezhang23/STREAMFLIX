# StreamFlix — Recommendation-Data Platform (YouTube-metadata)

A streaming-platform **recommendation backend**: a set of **Spring Boot microservices** behind an
API gateway that track user-behavior events through **Kafka**, serve **collaborative-filtering
recommendations** with **precomputed candidates** cached in **Redis**, and separate the **OLTP**
write path from an **OLAP** analytics pipeline. Videos are represented purely by **YouTube metadata**
(no media files / object storage); **synthetic + continuous traffic** provides realistic behavioral
signal. Fully containerized — `docker compose up --build` brings up the whole system plus a small
React demo UI that embeds the real YouTube player.

## What this demonstrates (resume targets)

| Target | Where it lives |
|--------|----------------|
| **Event-driven user-behavior tracking processing thousands of watch events through async pipelines** | Kafka topic `user-behavior`; producer `video-service/.../BehaviorEventPublisher.java`; a continuous `video-service/.../TrafficSimulator.java` streams events; independent consumers in reco (`group: reco`) and analytics (`group: analytics`) |
| **Recommendation APIs using collaborative filtering + popularity-based ranking** | `recommendation-service/.../engine/CollaborativeFilteringEngine.java` (item-item cosine CF blended with a popularity prior + cold-start fallback) served at `GET /api/recommendations/{userId}` |
| **Reduced feed-generation latency via Redis caching + precomputed recommendation candidates** | offline `recommendation-service/.../job/CandidatePrecomputeJob.java` → durable `recommendation_candidates` store + warm Redis; serving is **Redis → candidate store → on-the-fly compute**; measured by `scripts/benchmark.sh` |
| **Separate OLTP and analytics pipelines emulating production streaming-platform architecture** | OLTP (schema-per-service) in `streaming_oltp`; OLAP star schema in `streaming_olap` (`analytics-service/.../db/olap/`); batch ETL `analytics-service/.../etl/EtlJob.java` |

## Architecture

```
                         ┌──────────────┐
       React (Vite) ───► │ API Gateway  │  Spring Cloud Gateway · JWT validation · :8080
        demo UI :3000    └──────┬───────┘  (validates token once, injects X-User-Id downstream)
                                │ REST (stateless, routed)
      ┌──────────────┬─────────┼──────────────────┬────────────────────┐
      ▼              ▼         ▼                  ▼                    ▼
┌────────────┐ ┌───────────┐ ┌──────────────────┐ ┌────────────────────┐
│user-service│ │video-svc  │ │recommendation-svc│ │ analytics-service   │
│ :8081 auth │ │ :8082     │ │ :8083 CF + recs  │ │ :8084 ETL + OLAP    │
│ JWT issue  │ │ catalog + │ │ precompute +     │ │                     │
│            │ │ traffic   │ │ Redis serve      │ │                     │
└─────┬──────┘ └─────┬─────┘ └───────▲──────────┘ └─────────▲──────────┘
      │              │ produces      │ consumes (grp: reco)  │ consumes (grp: analytics)
      │              ▼               │                       │
      │        ┌──────────────── KAFKA (KRaft) ──────────────────────┐
      │        │  topic: user-behavior  (VIEW / WATCH / RATE / LIKE)  │
      │        └──────────────────────────────────────────────────────┘
      │
  ┌───┴───────────────────────┐     ┌──────────────┐
  │ PostgreSQL                 │     │   Redis 7    │ warm cache (precomputed per-user feeds)
  │  ├─ streaming_oltp (write) │     └──────────────┘
  │  └─ streaming_olap (read)  │ ◄── batch ETL (analytics-service @Scheduled) builds star schema
  └────────────────────────────┘
```

Videos are **metadata only** — each `videos` row references a real YouTube id; thumbnails come from
`img.youtube.com` and the UI plays the embed. No object storage and no media bytes anywhere; "watch"
is a behavior event plus an embedded player. This is intentional: the project is about the
**recommendation data pipeline**, not media delivery.

## Tech stack

Java 17 · Spring Boot 3.2 · Spring Cloud Gateway · Spring Data JPA / JDBC · Spring for Apache Kafka ·
Spring Data Redis · Flyway · PostgreSQL 16 · Redis 7 · Apache Kafka 3.7 (KRaft, no ZooKeeper) ·
React + Vite · Docker / docker-compose · springdoc OpenAPI (Swagger UI per service).

## Quick start

```bash
cp .env.example .env            # optional; sensible defaults are baked in
docker compose up --build       # postgres, redis, kafka, 4 services, gateway, frontend
```

- Demo UI:        http://localhost:3000  (login `alice@streamflix.dev` / `password`)
- API gateway:    http://localhost:8080
- Swagger UI:     http://localhost:8082/swagger-ui.html (per service: 8081/8082/8083/8084)

On first boot, `video-service` loads a curated **YouTube catalog** (`catalog/youtube-seed.json` —
~66 real music videos across ~8 sub-genres: Pop, Rock, Hip-Hop, EDM, Latin, K-Pop, R&B, Indie) and
replays synthetic behavior for 400 users **through Kafka**; then the `TrafficSimulator` keeps
emitting events continuously, so the async pipeline processes a steadily growing event count and
trending/recommendations evolve live. The `CandidatePrecomputeJob` periodically regenerates per-user
candidates into the candidate store + warm cache.

End-to-end smoke test (after the stack is healthy):

```bash
./scripts/smoke.sh
```

## How recommendations are served (two-stage)

1. **Offline candidate generation** — `CandidatePrecomputeJob` (scheduled, or `POST
   /api/recommendations/precompute`) builds the CF model once, computes top-50 candidates for every
   active user, writes them to `reco.recommendation_candidates`, and warms Redis.
2. **Online serving** — `GET /api/recommendations/{userId}` is a fast lookup:
   **Redis (warm) → candidate store → on-the-fly CF compute (only for a brand-new user)**.

This is why feed generation is cheap on the request path even though CF itself is not.

## The demo homepage (YouTube-style, music-focused)

The UI is built like YouTube's homepage so the recommendation signal is visible as it forms:

- **Categorized rails** — instead of one undifferentiated grid, the "For You" view shows a rail per
  music sub-genre, so a brand-new user with no history still sees *different types* of music
  immediately (cold-start variety comes straight from the catalog, by construction).
- **Emerging recommendation tags** — a chip bar on top is driven by
  `GET /api/videos/tags/{userId}`, which ranks the user's sub-genres from their own watch/rating
  history (`watch_events` + `ratings` joined to `videos.genre`), falling back to global popularity
  when there's no data yet. The genres the user actually engages with surface to the front and are
  highlighted (★); as you watch/rate, the chips **re-rank live** — the personalized tags *emerge as
  data accumulates*. Selecting a chip filters the feed to that genre.

The tags are computed in video-service via a direct SQL join (it owns `videos`/`watch_events`/
`ratings` in one schema), so this needs no change to the CF engine or the Kafka event.

## Measuring the latency reduction

```bash
./scripts/benchmark.sh 200
```

It benchmarks `/api/recommendations/{userId}` two ways: a **baseline** that computes per request
(loads the interaction matrix from Postgres and runs CF — `force-compute`, cache off) vs the
**optimized** two-stage path (precomputed candidates served from warm Redis / the candidate store).

Measured on this stack (200 requests, ~7.3k interactions, with the live traffic generator running):

```
compute per request:      mean=12.4ms  p50=10.0ms  p95=23.1ms
precomputed + cached:     mean= 6.6ms  p50= 6.3ms  p95= 8.6ms
RESULT: feed-generation p50 10.0ms -> 6.3ms  (-36%)   p95 23.1ms -> 8.6ms  (-63%)
```

The p95 (tail latency — what matters for a feed under concurrent traffic) drops most. Re-run on your
machine and use the numbers it prints; they vary with hardware and the (configurable) seed + traffic
volume.

## Configuration knobs (env)

- `APP_TRAFFIC_ENABLED` (default `true`) — continuous synthetic traffic generator on/off.
- `APP_TRAFFIC_EVENTS_PER_TICK` / `APP_TRAFFIC_INTERVAL_MS` — traffic volume.
- `APP_PRECOMPUTE_INTERVAL_MS` (default `30000`) — candidate regeneration cadence.
- `CACHE_ENABLED` — toggles the Redis serving cache (used by the benchmark).
- `YOUTUBE_API_KEY` — optional; when set, `YoutubeApiCatalogLoader` refreshes catalog titles from the
  YouTube Data API. Blank (default) uses the curated JSON verbatim — no credential needed.

## Designed to scale into a distributed system

This runs on a laptop, but the design choices are the ones that let it scale out:

- **Stateless services + JWT** — the gateway validates the token once and forwards identity via
  `X-User-Id`; any replica serves any request. Try it: `docker compose up -d --scale video-service=3`.
- **Kafka event backbone** — producers/consumers decoupled; each consumer type is its own group, so
  reco and analytics scale independently. Events keyed by user id for per-user ordering.
- **Shared Redis** (not in-JVM) — warm feed cache shared across replicas, survives restarts.
- **Schema-per-service ownership + OLTP/OLAP split** — no cross-service table coupling; read side
  scales separately from the write side.
- **Idempotent consumers** (`event_id` dedupe) — safe under Kafka at-least-once redelivery.
- **Offline candidate generation** — the expensive CF compute is off the request path; at scale it
  would be partitioned per user-shard and triggered by activity.
- **12-factor config** + **Actuator probes** — container-orchestrator (Kubernetes) ready.

Laptop-friendly simplifications: a single Postgres hosts the two logically-separated databases, and
Kafka runs as a single KRaft broker. In production these become a Postgres cluster / OLAP warehouse
and a multi-broker partitioned Kafka cluster — config only, no code changes.

## Repository layout

```
common/                 shared event type + JWT utility
api-gateway/            edge routing + JWT auth + identity propagation
user-service/           registration, login (JWT), profile
video-service/          YouTube catalog + Kafka producer + seed + continuous TrafficSimulator
recommendation-service/ Kafka consumer -> interaction matrix -> CF; precompute job + candidate store; Redis serve
analytics-service/      Kafka consumer -> OLTP event store -> batch ETL -> OLAP star schema
frontend/               React + Vite demo SPA (YouTube embeds; nginx proxies /api to the gateway)
docker/Dockerfile       generic multi-stage build, selected per service via MODULE arg
infra/db/init/          creates the OLAP database alongside the OLTP one
scripts/                smoke.sh (e2e) + benchmark.sh (cache/precompute latency)
```
