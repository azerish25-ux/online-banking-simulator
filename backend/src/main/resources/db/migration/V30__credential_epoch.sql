-- The authentication epoch becomes part of every credential. users.security_version
-- (V18) already invalidates stale ACCESS tokens at the auth filter; without the same
-- binding on refresh credentials, a login that verified its proof before a factor
-- change can still insert a live refresh row after the revocation commit, and
-- rotation can mint current-version access from a proof the factor invalidated.
--
-- refresh_tokens.security_version: the epoch this credential was authenticated
-- under. Issue-time verification refuses a stale epoch outright; rotation re-checks
-- it against the user's current version under the same per-user lock. Existing rows
-- are backfilled to their user's current version: they were minted under it (no
-- factor change has occurred since, or they would already be revoked).
alter table refresh_tokens
    add column security_version integer not null default 0;

update refresh_tokens rt
set security_version = u.security_version
from users u
where rt.user_id = u.id;

-- Defense-in-depth against any future writer that mutates security state without
-- the per-user lock: an optimistic version column makes a lost update fail loudly
-- instead of silently interleaving two factor transitions.
alter table users
    add column row_version bigint not null default 0;
