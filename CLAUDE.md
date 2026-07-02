# CLAUDE.md — StreamFlix project scaffolding

Persistent orientation for this repo. Read this first; it captures the shape of the
system, the conventions, and the commands — the things that don't change often.
For the **current stage of active work (the OLTP streaming path)** see
[Implementation.md](./Implementation.md).

## What this is

A Netflix-style **recommendation-data backend**, built as Spring Boot microservices behind an
API gateway. Videos are **YouTube metadata only** — every `videos` row references a real
YouTube id; thumbnails come from `img.youtube.com` and the UI plays the embed. There is **no
media storage and no media bytes** anywhere. "Watch" is a behavior event plus an embedded
player. The project is deliberately about the **recommendation / event data pipeline**, not
media delivery.

Core dataflow: users interact → `video-service` writes OLTP + publishes a Kafka event →
`recommendation-service` and `analytics-service` consume that event independently and build
their own derived state (CF interaction matrix / OLAP star schema). No service reads another
service's tables — the Kafka `user-behavior` topic is the only coupling.

## Architecture

```
 React (Vite) demo UI :3000
        │  (nginx proxies /api → gateway)
        ▼
 API Gateway :8080  ── Spring Cloud Gateway · validates JWT once · injects X-User-Id downstream
        │ REST (stateless, routed)
   ┌────┼────────────────┬──────────────────────┬─────────────────────┐
   ▼    ▼                ▼                      ▼                     ▼
 user-service      video-service        recommendation-service   analytics-service
 :8081             :8082                :8083                    :8084
 auth / JWT issue  catalog (read,       Kafka consumer (grp reco)  Kafka consumer (grp analytics)
 registration      cache-aside) +       → user_item_interactions   → analytics_raw_events (OLTP)
                   behavior WRITE path  → collaborative filtering   → batch ETL → OLAP star schema
                   + Kafka producer     → precompute candidates
                   + traffic simulator  → Redis-warmed serving
        │                │  produces           ▲ consumes              ▲ consumes
        │                ▼                     │                       │
        │          KAFKA (KRaft, single broker) ─ topic: user-behavior (VIEW/WATCH/RATE/LIKE)
        │
   ┌────┴───────────────────────┐     ┌──────────────┐
   │ PostgreSQL 16               │     │  Redis 7     │  warm cache: catalog reads + per-user feeds
   │  ├─ streaming_oltp (write)  │     └──────────────┘
   │  └─ streaming_olap (read)   │ ◄── batch ETL (analytics-service @Scheduled) builds star schema
   └─────────────────────────────┘
```

Laptop-friendly simplifications that are **config, not code**: one Postgres container hosts the
two logically-separate databases (`streaming_oltp`, `streaming_olap`); Kafka runs as a single
KRaft broker (no ZooKeeper). In production these become a Postgres cluster / OLAP warehouse and a
multi-broker partitioned Kafka.

## Tech stack

Java 17 · Spring Boot 3.2.5 · Spring Cloud Gateway 2023.0.1 · Spring Data JPA / JDBC · Spring for
Apache Kafka · Spring Data Redis · Flyway · PostgreSQL 16 · Redis 7 · Apache Kafka 3.7 (KRaft) ·
jjwt 0.12.5 · springdoc OpenAPI 2.5 · React + Vite · Docker / docker-compose · k6 · Prometheus ·
Grafana.

## Repository layout

```
common/                 shared UserBehaviorEvent + BehaviorType + JwtService
api-gateway/            edge routing + JWT auth + identity propagation (X-User-Id)
user-service/           registration, login (JWT), profile; BulkUserSeeder for load tests
video-service/          catalog (read/cache) + behavior WRITE path + Kafka producer
                        + CatalogSeeder + BehaviorSeeder + TrafficSimulator
recommendation-service/ Kafka consumer → interaction matrix → CF; precompute job + candidate store; Redis serve
analytics-service/      Kafka consumer → OLTP raw event store → batch ETL → OLAP star schema
frontend/               React + Vite demo SPA (YouTube embeds; nginx proxies /api)
docker/Dockerfile       one generic multi-stage build, selected per service via MODULE build-arg
infra/db/init/          creates the OLAP database alongside the OLTP one on first Postgres boot
scripts/                smoke.sh (e2e) · benchmark.sh (cache/precompute latency)
                        youtube-catalog-pull.mjs (bulk catalog) · gap-report.mjs (SLO gap)
loadtest/               k6 harness + SLO targets.json + results   (see Implementation.md)
monitoring/             Prometheus + Grafana dashboards + provisioning
```

Each service is a Maven module under the parent `pom.xml` (groupId `com.streamflix`, package
`com.streamflix.<service>`). `common` is a shared dependency of the others.

## Ports

| Service | Port | | Service | Port |
| --- | --- | --- | --- | --- |
| frontend (demo UI) | 3000 | | analytics-service | 8084 |
| api-gateway | 8080 | | postgres | 5433→5432 |
| user-service | 8081 | | redis | 6379 |
| video-service | 8082 | | kafka | 9092 |
| recommendation-service | 8083 | | prometheus / grafana | 9090 / 3001 |

## Conventions & design rules

- **Schema-per-service ownership.** Each service owns its schema via Flyway
  (`src/main/resources/db/migration/V*__*.sql`, `default-schema` = service name). No cross-service
  FKs; no service queries another's tables. Derived state is rebuilt from the Kafka stream.
- **OLTP/OLAP split.** Write side (`streaming_oltp`) and read/analytics side (`streaming_olap`) are
  separate databases so they scale independently. analytics-service uses a two-datasource config
  (`config/DataSourceConfig.java`) and a second migration path (`db/olap/`).
- **Event backbone is the only coupling.** `common` defines the canonical `UserBehaviorEvent`
  (record) and `BehaviorType` enum. Events are keyed by `userId` (per-user partition ordering).
  `eventId` (UUID) makes consumers **idempotent** under Kafka at-least-once delivery (`UNIQUE`
  dedupe on the consumer side).
- **Stateless services + JWT.** Gateway validates the token once and forwards identity as the
  `X-User-Id` header; downstream services trust it. Any replica serves any request
  (`docker compose up -d --scale video-service=3`). Public catalog browse (`GET /api/videos/**`)
  has identity headers stripped — that's why endpoints like `/api/videos/tags/{userId}` and
  `/api/recommendations/{userId}` take the user id in the **path**, not the header.
- **Cache-aside Redis.** Hot catalog reads and per-user feeds are served from Redis; shared across
  replicas (not in-JVM), survives restarts. `CACHE_ENABLED` toggles it (used by benchmark).
- **12-factor config + Actuator probes.** All knobs are env vars; `/actuator/health` (+
  `prometheus`) exposed for orchestrator readiness.

## Build, run, test

```bash
# Full stack (postgres, redis, kafka, 4 services, gateway, frontend)
cp .env.example .env            # optional; sensible defaults are baked in
docker compose up --build

# Local Maven build (all modules)
mvn -q clean install            # add -DskipTests to skip

# End-to-end smoke test (after the stack is healthy)
./scripts/smoke.sh

# Recommendation cache/precompute latency benchmark
./scripts/benchmark.sh 200
```

Entry points after boot:
- Demo UI — http://localhost:3000  (login `alice@streamflix.dev` / `password`)
- API gateway — http://localhost:8080
- Swagger UI — http://localhost:8082/swagger-ui.html (per service 8081–8084)

On first boot `video-service` loads the YouTube catalog (`CatalogSeeder`), replays synthetic
behavior for 400 users **through Kafka** (`BehaviorSeeder`), then `TrafficSimulator` emits events
continuously so the async pipeline and recommendations evolve live.

## How recommendations are served (two-stage)

1. **Offline candidate generation** — `CandidatePrecomputeJob` (scheduled, or
   `POST /api/recommendations/precompute`) builds the CF model, computes top-50 candidates per
   active user into `recommendation_candidates`, and warms Redis.
2. **Online serving** — `GET /api/recommendations/{userId}` is a fast lookup:
   Redis (warm) → candidate store → on-the-fly CF compute (only for a brand-new user).

The expensive CF compute is kept off the request path — that's the whole point of the two-stage design.

## Key config knobs (env)

| Var | Default | Effect |
| --- | --- | --- |
| `APP_TRAFFIC_ENABLED` | `true` | continuous synthetic traffic generator on/off |
| `APP_TRAFFIC_EVENTS_PER_TICK` / `APP_TRAFFIC_INTERVAL_MS` | 20 / 5000 | traffic volume |
| `APP_PRECOMPUTE_INTERVAL_MS` | 30000 | candidate regeneration cadence |
| `CACHE_ENABLED` | `true` | Redis serving cache (benchmark toggles this) |
| `APP_SEED_USERS` | `0` | bulk synthetic users for load tests (see Implementation.md) |
| `YOUTUBE_API_KEY` | *(blank)* | enriches catalog + required by the catalog puller; blank = curated JSON |

## Pointers

- **Active work / OLTP stage, load simulator, SLO gap** → [Implementation.md](./Implementation.md)
- **Load harness details** → [loadtest/README.md](./loadtest/README.md)
