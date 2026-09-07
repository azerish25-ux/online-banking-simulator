import { problemParts } from "./guards";
import type { AuthResponse, User } from "./api-types";
export type { AuthResponse, User };
import { clearAllPendingOperations } from "./pending-op";

export class ApiError extends Error {
  status: number;
  title: string;

  constructor(status: number, title: string, detail: string) {
    super(detail || title || "Request failed: " + status);
    this.name = "ApiError";
    this.status = status;
    this.title = title;
  }
}

export function getToken(): string | null {
  if (typeof document === "undefined") return null;
  const match = document.cookie.match(/(?:^|; )bank_token=([^;]*)/);
  return match ? decodeURIComponent(match[1]) : null;
}

/**
 * The bank_token cookie is JS-readable (the Edge middleware needs it for UX
 * routing) and carries the access token. Its Max-Age mirrors the ACCESS
 * TOKEN's validated lifetime so a stolen cookie dies with the JWT it holds;
 * the silent-refresh path rewrites the cookie on every rotation, so active
 * sessions never notice. Trade-off: after that lifetime a fresh navigation
 * may land on login even though the HttpOnly refresh cookie could still
 * repair the session - routing here is UX-only, the API is the authority.
 */

/**
 * Fallback cookie lifetime (seconds) when no VALIDATED session configuration
 * has been seen yet. The authoritative value always comes from an
 * authentication response's expiresInSeconds ( section 9: "derive
 * access-cookie lifetime from validated session configuration rather than
 * the hard-coded lifetime") - this constant only sizes a cookie set by test
 * helpers or defensive paths that lack a validated number.
 */
export const ACCESS_TOKEN_COOKIE_MAX_AGE = 15 * 60;

/**
 * The full Set-Cookie value; split out so tests can pin the exact attributes.
 * {@code maxAgeSeconds} is the ACCESS TOKEN's validated lifetime - callers
 * that hold an AuthResponse pass expiresInSeconds; everyone else falls back
 * to the constant.
 */
export function tokenCookie(
  token: string,
  maxAgeSeconds: number = ACCESS_TOKEN_COOKIE_MAX_AGE,
  secure: boolean = isSecureContext()
): string {
  const maxAge = Number.isFinite(maxAgeSeconds) && maxAgeSeconds > 0
      ? Math.floor(maxAgeSeconds)
      : ACCESS_TOKEN_COOKIE_MAX_AGE;
  return "bank_token=" + encodeURIComponent(token)
      + "; path=/; max-age=" + maxAge
      + "; samesite=lax"
      + (secure ? "; Secure" : "");
}

function isSecureContext(): boolean {
  return typeof window !== "undefined" && window.location.protocol === "https:";
}

/** Installs an access token; pass the AUTH RESPONSE's expiresInSeconds when
 *  one is available so the cookie dies exactly when the JWT does (F22). */
export function setToken(token: string, maxAgeSeconds?: number) {
  document.cookie = tokenCookie(token, maxAgeSeconds);
}

export function clearToken() {
  document.cookie = "bank_token=; path=/; max-age=0";
  // The logout/expiry boundary must not carry an unresolved operation identity
  // into another session (F06): the key only means something while the user
  // who minted it is signed in.
  clearAllPendingOperations();
}

function safeJson(
  text: string
): { detail?: string; title?: string; accessToken?: string; expiresInSeconds?: number } | null {
  if (!text) return null;
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
}

/**
 * Broadcast when the session can no longer be repaired (refresh failed).
 * The query provider listens and routes to the login page; nothing in this
 * module navigates, so unit tests and non-browser callers stay safe.
 */
export const SESSION_EXPIRED_EVENT = "simulator:session-expired";

// Cross-tab session announcements (F22). The browser cookie jar is shared, so
// a successful refresh in ONE tab already fixes the others - but tabs must
// not ROTATE THE SAME refresh token simultaneously (double-use burns the
// whole family server-side), and a logout/expiry in one tab must evict the
// others' cached data. BroadcastChannel is a progressive enhancement: tabs
// without it simply fall back to the Web Lock + single-flight path below.
const AUTH_CHANNEL = "simulator:auth";

type AuthMessage = { type: "expired" | "logout" | "refreshed" };

function channel(): BroadcastChannel | null {
  if (typeof window === "undefined" || typeof BroadcastChannel === "undefined") {
    return null;
  }
  try {
    return new BroadcastChannel(AUTH_CHANNEL);
  } catch {
    return null;
  }
}

export function subscribeAuthChannel(listener: (message: AuthMessage) => void): () => void {
  const bus = channel();
  if (!bus) return () => {};
  bus.onmessage = (event: MessageEvent) => {
    const message = event.data as AuthMessage | undefined;
    if (message && (message.type === "expired" || message.type === "logout" || message.type === "refreshed")) {
      listener(message);
    }
  };
  return () => bus.close();
}

function broadcastAuth(message: AuthMessage): void {
  const bus = channel();
  if (bus) bus.postMessage(message);
}

export function expireSession(): void {
  clearToken();
  if (typeof window !== "undefined") {
    window.dispatchEvent(new Event(SESSION_EXPIRED_EVENT));
  }
  // Other tabs must evict their caches too, or a logged-out session's data
  // keeps rendering behind the login page.
  broadcastAuth({ type: "expired" });
}

/** The user logged out on purpose in this tab; peers do the same. */
export function broadcastLogout(): void {
  broadcastAuth({ type: "logout" });
}

// Token endpoints never retry: they mint rather than consume the session.
const NO_RETRY = new Set(["/v1/auth/login", "/v1/auth/register", "/v1/auth/refresh", "/v1/auth/logout", "/v1/auth/mfa/verify"]);

/**
 * Endpoints whose controller answers 401 for WRONG CREDENTIALS on a perfectly
 * valid session (F02: TOTP enable/disable reject a bad password or code with
 * BadCredentials). After the silent-refresh retry such a 401 is a definitive
 * business rejection - surfacing it must NOT nuke the session the way an
 * unrecoverable "token revoked" 401 does. Endpoints outside this set keep the
 * strict rule: a 401 on a freshly rotated token means the credentials were
 * revoked, so the session is expired.
 */
const DEFINITIVE_401 = new Set(["/v1/auth/totp/enable", "/v1/auth/totp/disable"]);
let refreshing: Promise<void> | null = null;

/**
 * Serializes the browser-wide refresh rotation (F22). One Web Lock per
 * browser, so two tabs that hit expiry together cannot both spend the SAME
 * refresh token: the loser waits, re-checks whether the winner already
 * rotated (the JS-readable bank_token changed), and skips its own rotation.
 * Tabs without Web Locks fall back to the in-context single-flight promise.
 * The lock itself never reads or broadcasts the HttpOnly refresh cookie.
 */
async function withRefreshLock(task: () => Promise<void>): Promise<void> {
  const locks = (typeof navigator !== "undefined" && navigator.locks) || null;
  if (!locks) {
    return task();
  }
  // Bounded wait: a crashed lock-holder or pathological contention must not
  // wedge every tab forever - beyond the timeout the session is treated as
  // expired and the user is asked to log in again (an unresolved refresh is
  // an unknown outcome, never a silently dead screen). The Web Locks spec
  // bounds a WAIT via the request's abort signal (LockOptions has no
  // timeout field); once granted, the callback runs regardless.
  await locks.request(
    "bank-session-refresh",
    { signal: AbortSignal.timeout(5000) },
    () => task()
  );
}

/**
 * The endpoints whose 2xx body IS an AuthResponse (access token + validated
 * expiresInSeconds): login/register, MFA verification, and the factor
 * changes that reissue credentials under a new security version. ONLY these
 * may install credentials - a resource endpoint must never be able to plant
 * an arbitrary accessToken into the cookie jar ( section 9).
 */
const AUTH_SESSION_PATHS = new Set([
  "/v1/auth/login",
  "/v1/auth/register",
  "/v1/auth/mfa/verify",
  "/v1/auth/totp/enable",
  "/v1/auth/totp/disable"
]);

/**
 * Rotate the refresh cookie once and persist the new access token.
 * Returns true when THIS caller performed a rotation; false when another tab
 * already did (nothing left to do - the retried request will carry the fresh
 * cookie/token automatically because the cookie jar is shared).
 */
async function rotateSession(): Promise<void> {
  const tokenBeforeLock = getToken();
  if (!refreshing) {
    refreshing = withRefreshLock(async () => {
      // Another tab may have refreshed while we waited for the lock: its
      // Set-Cookie already replaced the shared refresh cookie, so rotating
      // again would burn the winner's brand-new token. Skip if the access
      // token changed under us.
      if (getToken() !== tokenBeforeLock) {
        return;
      }
      const res = await fetch("/backend/v1/auth/refresh", { method: "POST" });
      if (!res.ok) throw new Error("refresh failed: " + res.status);
      const data = safeJson(await res.text());
      // A refresh body is an AuthResponse: only a validated access token AND
      // its positive lifetime may be installed - a malformed body is a failed
      // rotation (expire the session), never a partial cookie.
      if (!isValidAuthBody(data)) {
        throw new Error("refresh returned no validated access token");
      }
      setToken(data.accessToken, data.expiresInSeconds);
      broadcastAuth({ type: "refreshed" });
    })
      .finally(() => {
        refreshing = null;
      });
  }
  return refreshing;
}

/** The validated AuthResponse fields every credential-installing path needs. */
function isValidAuthBody(
  body: { accessToken?: string; expiresInSeconds?: number } | null
): body is { accessToken: string; expiresInSeconds: number } {
  return Boolean(
    body
    && typeof body.accessToken === "string"
    && body.accessToken.length > 0
    && typeof body.expiresInSeconds === "number"
    && Number.isFinite(body.expiresInSeconds)
    && body.expiresInSeconds > 0
  );
}

/**
 * Raw same-origin request through the rewrite proxy. On 401 it rotates the
 * refresh cookie once and retries with the new access token; if rotation
 * fails the session is cleared and a session-expired event is broadcast.
 */
async function request(path: string, options: RequestInit = {}, retried = false): Promise<Response> {
  const token = getToken();
  const res = await fetch("/backend" + path, {
    ...options,
    headers: {
      "Content-Type": "application/json",
      ...(token ? { Authorization: "Bearer " + token } : {}),
      ...(options.headers || {})
    }
  });
  if (res.status === 401 && !NO_RETRY.has(path)) {
    if (!retried) {
      try {
        await rotateSession();
        return request(path, options, true);
      } catch {
        expireSession();
        throw new ApiError(401, "Unauthorized", "Session expired. Please log in again.");
      }
    }
    // Rotation succeeded but the server still rejects us. For the credential-
    // check endpoints a second 401 is a business rejection (wrong code or
    // password - the session is healthy, only the submission was refused), so
    // it surfaces as an ordinary ApiError instead of ending the session.
    if (DEFINITIVE_401.has(path)) {
      const parts = problemParts(safeJson(await res.text()));
      throw new ApiError(
        401,
        parts.title || "Unauthorized",
        parts.detail || "Request failed: 401"
      );
    }
    // Everywhere else a 401 on a freshly rotated token means the credentials
    // were revoked server-side: unrecoverable, expire the session.
    expireSession();
    throw new ApiError(401, "Unauthorized", "Session expired. Please log in again.");
  }
  return res;
}

export async function api<T>(path: string, options: RequestInit = {}): Promise<T> {
  const res = await request(path, options);
  const data = safeJson(await res.text());
  if (!res.ok) {
    // Every error answers the RFC-7807 envelope; validate before surfacing so
    // a malformed body still yields a sane ApiError, never undefined fields.
    const parts = problemParts(data);
    throw new ApiError(
      res.status,
      parts.title || "Error",
      parts.detail || "Request failed: " + res.status
    );
  }
  // Session-issuing endpoints (login, register, MFA verification, and the
  // factor changes like totp/enable that bump the security version and would
  // otherwise kill the current access token) reissue credentials in their
  // AuthResponse. Adopt the fresh token ONLY from those validated endpoints,
  // and only when the body actually is an AuthResponse ( section 9): a
  // resource endpoint can never plant an arbitrary accessToken into the
  // cookie jar, and the cookie's Max-Age mirrors the validated lifetime.
  if (AUTH_SESSION_PATHS.has(path) && isValidAuthBody(data)) {
    setToken(data.accessToken, data.expiresInSeconds);
  }
  return data as T;
}

/** Authenticated GET that keeps the response body intact (binary/CSV downloads). */
export async function authedFetch(path: string): Promise<Response> {
  const res = await request(path);
  if (res.ok) return res;
  const parts = problemParts(safeJson(await res.text()));
  throw new ApiError(
    res.status,
    parts.title || "Error",
    parts.detail || "Request failed: " + res.status
  );
}
