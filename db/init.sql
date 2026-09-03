-- Seed for `docker compose up db` ONLY. Flyway (backend) owns every table.
-- Safe to run multiple times.
CREATE EXTENSION IF NOT EXISTS "pgcrypto";
CREATE EXTENSION IF NOT EXISTS "citext";
