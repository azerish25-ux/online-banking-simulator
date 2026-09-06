-- V17: TOTP secret custody (F30) + persisted login challenges (F30).
--
-- Secret custody: users.totp_secret may still hold a LEGACY PLAINTEXT secret
-- (totp_key_version = 0) until it is migrated. New secrets are stored
-- encrypted in totp_secret_ciphertext with totp_key_version = 1. The payload
-- format is owned by TotpSecretCustody: "v<version>:<iv-b64>:<ciphertext-b64>"
-- with AES-256-GCM and the user id bound as AAD. Database access alone never
-- exposes a usable seed when a master key is configured.
alter table users
    add column totp_secret_ciphertext varchar(512);

alter table users
    add column totp_key_version integer not null default 0;

-- Login challenges: each password-stage MFA login creates ONE row that carries
-- its own random identity, user binding, expiry, consumption state and attempt
-- budget. Verification atomically consumes exactly one unused, unexpired
-- challenge; a replay or a concurrent twin can never mint a second session.
-- Attempt accounting lives on the row so an ordinary rollback of the failed
-- verification cannot erase the attempt and grant unlimited guesses.
create table login_challenges (
    id              uuid primary key,
    user_id         uuid not null references users (id),
    expires_at      timestamp with time zone not null,
    consumed        boolean not null default false,
    failed_attempts integer not null default 0,
    created_at      timestamp with time zone not null
);

create index idx_login_challenges_user on login_challenges (user_id);
create index idx_login_challenges_expiry on login_challenges (expires_at);
