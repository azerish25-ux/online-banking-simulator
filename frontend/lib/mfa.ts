/**
 * The MFA challenge token lives in module memory only: never a cookie,
 * never the URL. It survives client-side navigation from the login page to
 * the /login/mfa challenge screen; a hard reload loses it, in which case
 * the challenge page asks the user to log in again (the token is a
 * 5-minute login challenge by design).
 */
const LIFETIME_MS = 5 * 60_000;

let pending: { token: string; expiresAt: number } | null = null;

export function stashMfaToken(token: string): void {
  pending = { token, expiresAt: Date.now() + LIFETIME_MS };
}

/** Read the current challenge token without consuming it (retries allowed). */
export function peekMfaToken(): string | null {
  if (!pending) return null;
  if (pending.expiresAt <= Date.now()) {
    pending = null;
    return null;
  }
  return pending.token;
}

/** Called only after a successful verification: the challenge is spent. */
export function clearMfaToken(): void {
  pending = null;
}
