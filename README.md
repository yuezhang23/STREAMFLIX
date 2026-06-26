# StreamFlix — Mini Video Streaming + Recommendation Backend

A Netflix-style backend built as a set of **Spring Boot microservices** behind an API gateway,
wired together with an **event-driven Kafka** backbone, a **Redis** caching layer, a
**collaborative-filtering recommendation engine**, and an **OLTP → OLAP batch analytics pipeline**
on PostgreSQL. Fully containerized — `docker compose up --build` brings up the whole system plus a
small React demo UI.

> This repo is designed to back specific resume claims with working code. See
> [Resume claims → where they live](#resume-claims--where-they-live).

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
│ :8081 auth │ │ :8082     │ │ :8083 CF + cache │ │ :8084 ETL + OLAP    │
│ JWT issue  │ │ catalog   │ │                  │ │                     │
└─────┬──────┘ └─────┬─────┘ └───────▲──────────┘ └─────────▲──────────┘
      │              │ produces      │ consumes (grp: reco)  │ consumes (grp: analytics)
      │              ▼               │                       │
      │        ┌──────────────── KAFKA (KRaft) ──────────────────────┐
      │        │  topic: user-behavior  (VIEW / WATCH / RATE / LIKE)  │
      │        └──────────────────────────────────────────────────────┘
      │
  ┌───┴───────────────────────┐     ┌──────────────┐
  │ PostgreSQL                 │     │   Redis 7    │ shared cache (catalog + per-user recs)
  │  ├─ streaming_oltp (write) │     └──────────────┘
  │  └─ streaming_olap (read)  │ ◄── batch ETL (analytics-service @Scheduled) builds star schema
  └────────────────────────────┘
```

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

On first boot the catalog is seeded with 150 videos and `video-service` replays ~14k synthetic
behavior events for 300 users **through Kafka** (≈8.7k user-item interactions), so recommendations
and analytics have a realistic working set immediately. (Seeding takes ~1–2 min on first start.)

End-to-end smoke test (after the stack is healthy):

```bash
./scripts/smoke.sh
```

## Resume claims → where they live

| Claim | Where it lives |
|-------|----------------|
| **Spring Boot microservices** (User, Video, Recommendation, Analytics) | `user-service/`, `video-service/`, `recommendation-service/`, `analytics-service/` + `api-gateway/`, independently built & containerized |
| **Event-driven architecture with Kafka** for user-behavior tracking | `common/.../UserBehaviorEvent.java`, producer `video-service/.../BehaviorEventPublisher.java`, consumers in reco & analytics (separate consumer groups) |
| **Redis caching layer** reducing API latency ~65% | cache-aside in `video-service/.../CacheService.java` (catalog) and `recommendation-service/.../CacheService.java` (per-user recs); **measured 65%** p50 reduction by `scripts/benchmark.sh` |
| **Recommendation engine** — collaborative filtering + ranking heuristics | `recommendation-service/.../CollaborativeFilteringEngine.java` (item-item cosine CF blended with popularity prior + cold-start fallback) |
| **OLTP + OLAP separation** + batch analytics pipeline | OLTP in `streaming_oltp`; OLAP star schema in `streaming_olap` (`.../db/olap/`); batch ETL in `analytics-service/.../EtlJob.java` |
| **Containerized via Docker + docker-compose** | `docker/Dockerfile` (multi-stage, per-service via build arg), `frontend/Dockerfile`, `docker-compose.yml` |

## Measuring the latency reduction

```bash
./scripts/benchmark.sh 200
```

It restarts `recommendation-service` with the cache **off**, benchmarks the
`/api/recommendations/{userId}` path (which loads the ~8.7k-row interaction matrix from Postgres and
recomputes collaborative-filtering scores), then restarts with the cache **on**, warms it, and
benchmarks again — printing p50/p95 for each and the measured % reduction.

Measured on this stack (200 requests, seeded matrix):

```
cache OFF:  mean=15.4ms  p50=14.6ms  p95=19.5ms
cache ON:   mean= 5.3ms  p50= 5.2ms  p95= 6.3ms
RESULT:     p50 14.6ms -> 5.2ms  =>  65% latency reduction   (p95: ~68%)
```

The script reprints this on demand — re-run it on your machine and use the number it gives you
(it varies with hardware and seed scale, both configurable).

## Designed to scale into a distributed system

This runs on a laptop, but the design choices are the ones that let it scale out:

- **Stateless services + JWT** — the gateway validates the token once and forwards identity via
  `X-User-Id`; no service holds session state, so any replica serves any request.
  Try it: `docker compose up -d --scale video-service=3`.
- **Kafka event backbone** — producers and consumers are decoupled; each consumer type is its own
  consumer group, so reco and analytics scale independently. Events are keyed by user id for
  per-user ordering and partition-friendly fan-out.
- **Shared Redis** (not in-JVM cache) — cache is shared across replicas and survives restarts.
- **Schema-per-service ownership + OLTP/OLAP split** — no cross-service table coupling; the read
  side scales separately from the write side.
- **Idempotent consumers** (`event_id` dedupe) — safe under Kafka at-least-once redelivery.
- **12-factor config** (all wiring via env vars) and **Actuator health/readiness probes** —
  container-orchestrator (Kubernetes) ready.
- **Independently deployable images** — one image per service via the shared multi-stage Dockerfile.

Laptop-friendly simplifications (each documented above): a single Postgres instance hosts the two
logically-separated databases, and Kafka runs as a single KRaft broker. In production these become a
Postgres cluster / separate OLAP warehouse and a multi-broker partitioned Kafka cluster — no code
changes, only configuration and topic partition counts.

## Repository layout

```
common/                 shared event type + JWT utility
api-gateway/            edge routing + JWT auth + identity propagation
user-service/           registration, login (JWT), profile
video-service/          catalog (Redis cache-aside) + Kafka behavior producer + seed
recommendation-service/ Kafka consumer -> interaction matrix -> CF + ranking, Redis-cached
analytics-service/      Kafka consumer -> OLTP event store -> batch ETL -> OLAP star schema
frontend/               React + Vite demo SPA (served by nginx, proxies /api to the gateway)
docker/Dockerfile       generic multi-stage build, selected per service via MODULE arg
infra/db/init/          creates the OLAP database alongside the OLTP one
scripts/                smoke.sh (e2e) + benchmark.sh (cache latency)
```
# STREAMFLIX
