-- Runs once on first Postgres startup (docker-entrypoint-initdb.d).
-- POSTGRES_DB already creates the OLTP database; here we add the OLAP database
-- so a single Postgres container hosts the logically-separated OLTP and OLAP stores.
-- Each service owns its own schema via Flyway migrations once it boots.

CREATE DATABASE streaming_olap;
