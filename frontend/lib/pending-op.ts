/**
 * Minimum unresolved-operation identity (F06). When a money mutation ends
 * ambiguously (network drop, 5xx, 429, timeout) the client does NOT know
 * whether the server committed - so it must not mint a fresh idempotency key
 * for the next attempt. This store keeps just the *key* of the unresolved
 * operation, bound to the user who initiated it, so a retry after a route
 * change or a full reload reuses the same key and the server deduplicates.
 *
 * Nothing financial lives here: no amounts, no history, no credentials. The
 * server owns the canonical operation payload and result; the key only lets
 * the client ask the server about it. Cleared at the logout/expiry boundary
 * (see api.ts clearToken) so one user's unresolved identity never survives
 * into another user's session.
 */

const STORAGE_KEY = "bank.pending-ops.v1";

export interface PendingOperation {
  /** The user whose session initiated the operation. */
  userId: string;
  /** The idempotency key of the unresolved operation. */
  key: string;
  /** Which mutation kind the key belongs to - one key names one intent type. */
  kind: "transfer" | "deposit";
}

function readAll(): Record<string, PendingOperation> {
  if (typeof window === "undefined" || typeof sessionStorage === "undefined") {
    return {};
  }
  try {
    const raw = sessionStorage.getItem(STORAGE_KEY);
    return raw ? (JSON.parse(raw) as Record<string, PendingOperation>) : {};
  } catch {
    return {};
  }
}

function writeAll(all: Record<string, PendingOperation>): void {
  if (typeof window === "undefined" || typeof sessionStorage === "undefined") {
    return;
  }
  try {
    sessionStorage.setItem(STORAGE_KEY, JSON.stringify(all));
  } catch {
    // Storage full/blocked: the in-memory key in the hook still covers retries
    // within the page - persistence is a reload-safety enhancement only.
  }
}

export function readPendingOperation(userId: string, kind: PendingOperation["kind"]): PendingOperation | null {
  const pending = readAll()[userId];
  return pending && pending.kind === kind ? pending : null;
}

export function writePendingOperation(op: PendingOperation): void {
  const all = readAll();
  all[op.userId] = op;
  writeAll(all);
}

export function clearPendingOperation(userId: string): void {
  const all = readAll();
  delete all[userId];
  writeAll(all);
}

/** Logout/session-expiry boundary: no unresolved identity survives the session. */
export function clearAllPendingOperations(): void {
  if (typeof window === "undefined" || typeof sessionStorage === "undefined") {
    return;
  }
  try {
    sessionStorage.removeItem(STORAGE_KEY);
  } catch {
    // Ignore storage failures at logout - the cookie is already gone.
  }
}
