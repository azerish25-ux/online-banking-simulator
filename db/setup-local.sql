-- Run once against a local PostgreSQL as superuser (postgres):
--   "C:\Program Files\PostgreSQL\16\bin\psql.exe" -U postgres -f db\setup-local.sql
-- Safe to re-run: existing role/database are kept.

SELECT 'CREATE ROLE bankapp LOGIN PASSWORD ''bankapp_secret_change_me''' WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'bankapp')\gexec
SELECT 'CREATE DATABASE bankdb OWNER bankapp' WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'bankdb')\gexec
