# StreamFlix — Recommendation-Data Platform (YouTube-metadata)

A streaming-platform **recommendation backend**: Spring Boot microservices behind an API gateway
that track user-behavior events through **Kafka**, serve **collaborative-filtering recommendations**
with **precomputed candidates** cached in **Redis**, and separate the **OLTP** write path from an
**OLAP** analytics pipeline. Videos are **YouTube metadata only** (no media files / object storage);
**synthetic + continuous traffic** provides realistic behavioral signal. Fully containerized —
`docker compose up --build` brings up the whole system plus a small React demo UI that embeds the
real YouTube player.

## Quick start

```bash
cp .env.example .env            # optional; sensible defaults are baked in
docker compose up --build       # postgres, redis, kafka, 4 services, gateway, frontend
./scripts/smoke.sh              # end-to-end smoke test once the stack is healthy
```

- Demo UI — http://localhost:3000  (login `alice@streamflix.dev` / `password`)
- API gateway — http://localhost:8080 · Swagger UI — http://localhost:8082/swagger-ui.html

## Documentation

This README is intentionally short. The full docs are split in two:

- **[CLAUDE.md](./CLAUDE.md)** — project scaffolding to keep in memory: architecture, module
  layout, tech stack, ports, conventions/design rules, build & run commands, how recommendations
  are served. Start here.
- **[Implementation.md](./Implementation.md)** — the current stage of active work: the **OLTP
  streaming path**, the YouTube-sourced data simulator, the k6 load harness + SLO "gap-to-Netflix"
  bar, and observability.
- **[loadtest/README.md](./loadtest/README.md)** — load-simulator specifics.
