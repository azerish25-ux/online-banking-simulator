import type { AuthResponse, User } from "./api-types";
export type { AuthResponse, User };

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

export function setToken(token: string) {
  document.cookie = "bank_token=" + encodeURIComponent(token) + "; path=/; max-age=604800; samesite=lax";
}

export function clearToken() {
  document.cookie = "bank_token=; path=/; max-age=0";
}

function safeJson(text: string): { detail?: string; title?: string; accessToken?: string } | null {
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

export function expireSession(): void {
  clearToken();
  if (typeof window !== "undefined") {
    window.dispatchEvent(new Event(SESSION_EXPIRED_EVENT));
  }
}

// Token endpoints never retry: they mint rather than consume the session.
const NO_RETRY = new Set(["/v1/auth/login", "/v1/auth/register", "/v1/auth/refresh", "/v1/auth/logout", "/v1/auth/mfa/verify"]);
let refreshing: Promise<void> | null = null;

/**
 * Single-flight refresh: parallel 401s trigger exactly one rotation call.
 * The rotated access token must be persisted - the refresh endpoint returns
 * a fresh AuthResponse whose accessToken the retried call depends on.
 */
function rotateSession(): Promise<void> {
  if (!refreshing) {
    refreshing = fetch("/backend/v1/auth/refresh", { method: "POST" })
      .then(async (res) => {
        if (!res.ok) throw new Error("refresh failed: " + res.status);
        const data = safeJson(await res.text());
        if (!data || typeof data.accessToken !== "string" || data.accessToken.length === 0) {
          throw new Error("refresh returned no access token");
        }
        setToken(data.accessToken);
      })
      .finally(() => {
        refreshing = null;
      });
  }
  return refreshing;
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
    // Rotation succeeded but the server still rejects us: unrecoverable.
    expireSession();
    throw new ApiError(401, "Unauthorized", "Session expired. Please log in again.");
  }
  return res;
}

export async function api<T>(path: string, options: RequestInit = {}): Promise<T> {
  const res = await request(path, options);
  const data = safeJson(await res.text());
  if (!res.ok) {
    throw new ApiError(
      res.status,
      (data && data.title) || "Error",
      (data && data.detail) || "Request failed: " + res.status
    );
  }
  return data as T;
}

/** Authenticated GET that keeps the response body intact (binary/CSV downloads). */
export async function authedFetch(path: string): Promise<Response> {
  const res = await request(path);
  if (res.ok) return res;
  const data = safeJson(await res.text());
  throw new ApiError(
    res.status,
    (data && data.title) || "Error",
    (data && data.detail) || "Request failed: " + res.status
  );
}
