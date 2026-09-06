-- V18: F02 - safe authenticator enrollment/replacement + session versioning.
--
-- users.security_version: bumped on every factor change (enroll, replace,
-- disable). Access tokens carry the version they were minted under; the auth
-- filter rejects a token whose version no longer matches, so OLD ACCESS
-- CREDENTIALS stop working the moment a factor changes - not merely at the
-- next refresh rotation.
alter table users
    add column security_version integer not null default 0;

-- A pending TOTP enrollment is SEPARATE from the active factor: starting a
-- setup never touches the user's current secret. The pending row expires and
-- is promoted only after the new authenticator verifies; cancelling or
-- abandoning it leaves the old factor untouched. The pending secret is stored
-- through the same custody boundary as the active one (plaintext only in a
-- keyless local demo).
create table totp_enrollments (
    id               uuid primary key,
    user_id          uuid not null references users (id),
    pending_secret   varchar(512),
    pending_key_version integer not null default 0,
    expires_at       timestamp with time zone not null,
    consumed         boolean not null default false,
    created_at       timestamp with time zone not null
);

create index idx_totp_enrollments_user on totp_enrollments (user_id);
