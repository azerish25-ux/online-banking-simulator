/**
 * Minimum unresolved-operation identities (lifecycle). When a money
 * mutation ends ambiguously (network drop, 5xx, 429, timeout, killed tab)
 * the client does NOT know whether the server committed - so it must never
 * mint a fresh idempotency key for the next attempt. This store keeps the
 * recoverable identity of EVERY unresolved operation the current tab has
 * dispatched - one record per operation, never one slot per user - so two
 * pending transfers and a deposit can coexist, and resolving one never
 * erases the others.
 *
 * What lives here:
 *   - userId  - who initiated the operation (recovery is owner-scoped)
 *   - kind    - "transfer" | "deposit"
 *   - key     - the idempotency key (client operation id) the request carried
 *   - accountId - the ORIGINATING account: the server key namespace, so a
 *     status lookup can be scoped to exactly the account the key is unique on
 *   - a minimal reviewed-intent summary (amount/to) purely so a reloaded
 *     page can describe what it is offering to check - the server owns the
 *     canonical payload
 *
 * Nothing credential-like or historical lives here. The store is cleared at
 * the logout/expiry boundary (credentials and per-session data leave with the
 * session); recovery after reauthentication does NOT depend on it - the
 * authorized server list (GET /api/v1/operations/recent) makes
 * completed-but-unacknowledged operations discoverable on its own.
 */

const STORAGE_KEY = "bank.pending-ops.v2";

export interface PendingOperation {
  /** The user whose session initiated the operation. */
  userId: string;
  /** Which mutation kind the key belongs to - one key names one intent type. */
  kind: "transfer" | "deposit";
  /** The idempotency key (client operation id) of the unresolved operation. */
  key: string;
  /** Originating account id - the server-side key namespace for lookups. */
  accountId?: string;
  /** Reviewed-intent context so a reloaded page can describe the operation. */
  amount?: string;
  toIban?: string;
  /**
   * The transfer memo, part of the reviewed intent. A replay must re-send the
   * BYTE-IDENTICAL request (the server's dedupe compares a request hash that
   * includes the normalized memo), so the memo is stored with the key.
   */
  memo?: string;
  /** When the operation was dispatched (UTC epoch millis). */
  createdAt: number;
}

type Store = Record<string, Record<string, PendingOperation>>;

function isValid(op: unknown): op is PendingOperation {
  if (!op || typeof op !== "object") return false;
  const record = op as Record<string, unknown>;
  return typeof record.userId === "string"
      && (record.kind === "transfer" || record.kind === "deposit")
      && typeof record.key === "string"
      && record.key.length > 0
      && typeof record.createdAt === "number";
}

function readAll(): Store {
  if (typeof window === "undefined" || typeof sessionStorage === "undefined") {
    return {};
  }
  try {
    const raw = sessionStorage.getItem(STORAGE_KEY);
    if (!raw) return {};
    const parsed: unknown = JSON.parse(raw);
    if (!parsed || typeof parsed !== "object") return {};
    const store: Store = {};
    // Validate on read: a corrupted or foreign-shaped record is discarded,
    // never trusted as a recovery identity.
    for (const [userId, bucket] of Object.entries(parsed as Record<string, unknown>)) {
      if (!bucket || typeof bucket !== "object") continue;
      const clean: Record<string, PendingOperation> = {};
      for (const [opId, op] of Object.entries(bucket as Record<string, unknown>)) {
        if (isValid(op) && op.userId === userId) clean[opId] = op;
      }
      if (Object.keys(clean).length > 0) store[userId] = clean;
    }
    return store;
  } catch {
    return {};
  }
}

function writeAll(store: Store): void {
  if (typeof window === "undefined" || typeof sessionStorage === "undefined") {
    return;
  }
  try {
    sessionStorage.setItem(STORAGE_KEY, JSON.stringify(store));
  } catch {
    // Storage full/blocked: the in-memory key in the hook still covers
    // retries within the page, and the authorized server recovery list makes
    // the operation findable after a reload even when this store is empty -
    // a storage failure never downgrades a recoverable payment into an
    // unsafe in-memory-only one.
  }
}

/** Stable record id for one operation: namespaced by user + kind + key. */
function opId(op: Pick<PendingOperation, "userId" | "kind" | "key">): string {
  return op.userId + ":" + op.kind + ":" + op.key;
}

/**
 * Same-document change notice. The recovery surface reads the store to list
 * unresolved operations; a deposit/transfer that ends ambiguously WHILE that
 * surface is mounted (e.g. the dashboard dialog behind it) would otherwise
 * stay invisible until a reload. Every store write fires this event and the
 * surface re-reads - no polling, no shared state object.
 */
export const PENDING_OPS_EVENT = "bank:pending-ops-changed";

function notifyPendingOpsChanged(): void {
  if (typeof window === "undefined") return;
  try {
    window.dispatchEvent(new Event(PENDING_OPS_EVENT));
  } catch {
    // A broken dispatch must never fail a persistence write.
  }
}

/** Writes one operation record (new or refreshed). */
export function upsertPendingOperation(op: PendingOperation): void {
  const store = readAll();
  const id = opId(op);
  const bucket = store[op.userId] ?? {};
  bucket[id] = op;
  store[op.userId] = bucket;
  writeAll(store);
  notifyPendingOpsChanged();
}

/** All unresolved records of one user (the recovery list for that session). */
export function listPendingOperations(userId: string): PendingOperation[] {
  const bucket = readAll()[userId];
  if (!bucket) return [];
  return Object.values(bucket)
      .filter((op) => op.userId === userId)
      .sort((a, b) => a.createdAt - b.createdAt);
}

/** One specific operation record, addressed by user + kind + key. */
export function findPendingOperation(
  userId: string,
  kind: PendingOperation["kind"],
  key: string
): PendingOperation | null {
  const bucket = readAll()[userId];
  if (!bucket) return null;
  const op = bucket[opId({ userId, kind, key })];
  return op && isValid(op) ? op : null;
}

/** Removes exactly ONE operation record - never another user's or kind's. */
export function removePendingOperation(
  userId: string,
  kind: PendingOperation["kind"],
  key: string
): void {
  const store = readAll();
  const bucket = store[userId];
  if (!bucket) return;
  delete bucket[opId({ userId, kind, key })];
  if (Object.keys(bucket).length === 0) {
    delete store[userId];
  } else {
    store[userId] = bucket;
  }
  writeAll(store);
  notifyPendingOpsChanged();
}

/**
 * Logout/session-expiry boundary: this per-session store (which is scoped to
 * the signing user) leaves with the session. Financial recovery is NOT lost:
 * the owner can rediscover completed-but-unacknowledged operations through
 * the authorized server list after reauthentication.
 */
export function clearAllPendingOperations(): void {
  if (typeof window === "undefined" || typeof sessionStorage === "undefined") {
    return;
  }
  try {
    sessionStorage.removeItem(STORAGE_KEY);
  } catch {
    // Ignore storage failures at logout - the cookie is already gone.
  }
  notifyPendingOpsChanged();
}
