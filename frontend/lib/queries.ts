"use client";

import {
  useMutation,
  useQuery,
  useQueryClient,
  type QueryClient,
  type UseMutationResult,
  type UseQueryResult
} from "@tanstack/react-query";
import * as React from "react";
import { ApiError, api } from "./api";
import {
  classifyMoneyFailure,
  isDefinitiveRejection,
  isReplayInconclusive,
  malformedSuccessError,
  replayInconclusiveFailure
} from "./money-failure";
import {
  accountListSchema,
  accountSchema,
  depositReceiptSchema,
  historyPageSchema,
  monthPointListSchema,
  operationListSchema,
  requireShape,
  sameAmount,
  transactionSchema,
  transferReceiptSchema
} from "./guards";
import {
  PENDING_OPS_EVENT,
  listPendingOperations,
  removePendingOperation,
  upsertPendingOperation,
  type PendingOperation
} from "./pending-op";
import type {
  Account,
  Audit,
  AuthResponse,
  Beneficiary,
  CardItem,
  DayTotal,
  Deposit,
  HistoryPage,
  IssuedCard,
  MonthPoint,
  NotificationItem,
  OperationList,
  Page,
  PublicStats,
  Tx,
  User
} from "./api-types";

/**
 * Typed data layer. Every hook names its resource, so components read
 * `useAccounts().data` instead of hand-rolling fetch/state/error plumbing,
 * and mutations invalidate exactly what they change.
 *
 * 401s propagate as ApiError; the api() client already performs the silent
 * refresh-and-retry, so a surfaced 401 genuinely means "log in again".
 */

export const queryKeys = {
  me: ["me"] as const,
  accounts: ["accounts"] as const,
  accountDetail: (id: string) => ["account", id] as const,
  // The authorized recovery list: the caller's own recent keyed operations.
  recentOperations: ["operations", "recent"] as const,
  // The cursor (blank = newest page) is part of the key: two different
  // positions in the same feed are two different result sets and must not
  // collide in the cache. The optional filter tuple member makes each
  // server-filtered view its own cache entry too.
  transactions: (
    accountId: string,
    cursor: string,
    size: number,
    from?: string,
    to?: string,
    filters?: HistoryFilters
  ) =>
    filters
      ? (["transactions", accountId, cursor, size, from ?? "", to ?? "", filters] as const)
      : (["transactions", accountId, cursor, size, from ?? "", to ?? ""] as const),
  summary: (accountId: string, months: number) => ["summary", accountId, months] as const,
  transferReceipt: (id: string) => ["transfer-receipt", id] as const,
  beneficiaries: ["beneficiaries"] as const,
  notifications: (page: number) => ["notifications", page] as const,
  unreadCount: ["notifications", "unread"] as const,
  cards: (accountId: string) => ["cards", accountId] as const,
  admin: {
    users: (q: string, page: number) => ["admin", "users", q, page] as const,
    transactions: ["admin", "transactions"] as const,
    reviewQueue: (page: number) => ["admin", "review-queue", page] as const,
    audits: (action: string, page: number) => ["admin", "audits", action, page] as const,
    dailyTotals: ["admin", "daily-totals"] as const,
    userAccounts: (userId: string) => ["admin", "user-accounts", userId] as const
  }
};

export function useMe(): UseQueryResult<User, ApiError> {
  return useQuery({ queryKey: queryKeys.me, queryFn: () => api<User>("/v1/auth/me") });
}

export function useAccounts(): UseQueryResult<Account[], ApiError> {
  return useQuery({
    queryKey: queryKeys.accounts,
    queryFn: async () =>
      requireShape(accountListSchema, await api<unknown>("/v1/accounts"), "account") as Account[]
  });
}

export function useAccount(id: string): UseQueryResult<Account, ApiError> {
  return useQuery({
    queryKey: queryKeys.accountDetail(id),
    queryFn: async () =>
      requireShape(accountSchema, await api<unknown>("/v1/accounts/" + id), "account") as Account,
    enabled: id.length > 0
  });
}

/**
 * Server-side history predicates: the customer feed's
 * amount range, kind(s), state(s) and reference/counterparty search are SQL
 * predicates over the WHOLE account history. Empty members mean "open": an
 * empty applied filter is a normal unfiltered browse, not a query for empty
 * values.
 */
export type HistoryFilters = {
  minAmount?: string;
  maxAmount?: string;
  kinds?: string[];
  statuses?: string[];
  q?: string;
};

/**
 * One of the caller's own operations by id (durable receipt). The route
 * is bookmarkable and the lookup is originator-scoped server-side; a foreign
 * or unknown id answers 404 either way. Re-fetching after a HELD→POSTED
 * transition returns the current authoritative status and posting time.
 */
export function useTransferReceipt(id: string): UseQueryResult<Tx, ApiError> {
  return useQuery({
    queryKey: queryKeys.transferReceipt(id),
    queryFn: async () =>
      requireShape(transactionSchema, await api<unknown>("/v1/transfers/" + id), "receipt") as Tx,
    enabled: id.length > 0
  });
}

export function useTransactions(
  accountId: string,
  cursor = "",
  size = 10,
  from?: string,
  to?: string,
  filters?: HistoryFilters
): UseQueryResult<HistoryPage<Tx>, ApiError> {
  return useQuery({
    queryKey: queryKeys.transactions(accountId, cursor, size, from, to, filters),
    queryFn: async () => {
      let url = "/v1/transactions?accountId=" + encodeURIComponent(accountId) + "&size=" + size;
      if (cursor) url += "&cursor=" + encodeURIComponent(cursor);
      if (from) url += "&from=" + from;
      if (to) url += "&to=" + to;
      if (filters) {
        if (filters.minAmount) url += "&minAmount=" + encodeURIComponent(filters.minAmount);
        if (filters.maxAmount) url += "&maxAmount=" + encodeURIComponent(filters.maxAmount);
        for (const kind of filters.kinds ?? []) url += "&kind=" + encodeURIComponent(kind);
        for (const status of filters.statuses ?? []) url += "&status=" + encodeURIComponent(status);
        if (filters.q) url += "&q=" + encodeURIComponent(filters.q);
      }
      return requireShape(historyPageSchema, await api<unknown>(url), "history") as HistoryPage<Tx>;
    },
    enabled: accountId.length > 0
  });
}

export function useSummary(accountId: string, months = 6): UseQueryResult<MonthPoint[], ApiError> {
  return useQuery({
    queryKey: queryKeys.summary(accountId, months),
    queryFn: async () =>
      requireShape(monthPointListSchema, await api<unknown>("/v1/accounts/" + accountId + "/summary?months=" + months), "summary") as MonthPoint[],
    enabled: accountId.length > 0
  });
}

export function useBeneficiaries(): UseQueryResult<Beneficiary[], ApiError> {
  return useQuery({
    queryKey: queryKeys.beneficiaries,
    queryFn: () => api<Beneficiary[]>("/v1/beneficiaries")
  });
}

export function useNotifications(page = 0): UseQueryResult<Page<NotificationItem>, ApiError> {
  return useQuery({
    queryKey: queryKeys.notifications(page),
    queryFn: () => api<Page<NotificationItem>>("/v1/notifications?page=" + page + "&size=10")
  });
}

export function useUnreadCount(): UseQueryResult<number, ApiError> {
  return useQuery({
    queryKey: queryKeys.unreadCount,
    queryFn: async () => {
      const r = await api<{ unread?: number }>("/v1/notifications/unread-count");
      return r.unread ?? 0;
    },
    // Incoming money, interest and card events arrive server-side, so this
    // client cannot know to invalidate them. A light poll keeps the badge
    // honest without churning the bigger queries.
    refetchInterval: 30_000
  });
}

export function useCards(accountId: string): UseQueryResult<CardItem[], ApiError> {
  return useQuery({
    queryKey: queryKeys.cards(accountId),
    queryFn: () => api<CardItem[]>("/v1/accounts/" + accountId + "/cards"),
    enabled: accountId.length > 0
  });
}

export function usePublicStats(): UseQueryResult<PublicStats, ApiError> {
  return useQuery({
    queryKey: ["public-stats"],
    queryFn: () => api<PublicStats>("/public/stats"),
    staleTime: 5 * 60_000
  });
}

/**
 * A deposit answer (lifecycle): the updated account plus the recoverable
 * operation identity (transaction id, idempotency key, authoritative status)
 * that backs the durable receipt lookup. Aliased straight off the generated
 * OpenAPI schema: one authoritative definition, no parallel shape.
 */
export type DepositResult = Deposit;

export function useRecentOperations(limit = 25, enabled = true): UseQueryResult<OperationList, ApiError> {
  const qc = useQueryClient();
  const userId = currentUserId(qc);
  return useQuery({
    queryKey: queryKeys.recentOperations,
    queryFn: async () =>
      requireShape(operationListSchema, await api<unknown>("/v1/operations/recent?limit=" + limit), "operation list") as OperationList,
    // The recovery list is owner-scoped: without a signed-in identity there is
    // nothing to list (and no authorized request to make).
    enabled: enabled && !!userId
  });
}

/**
 * The money invalidation graph: the exact set of queries a posted deposit or
 * transfer makes stale. The live forms AND the recovery replay both resolve
 * money here, so the graph cannot drift between them.
 */
function invalidateMoneyMovement(
  qc: QueryClient,
  opts: { accountIds: string[]; toIban?: string }
): void {
  void qc.invalidateQueries({ queryKey: queryKeys.accounts });
  for (const id of opts.accountIds) {
    void qc.invalidateQueries({ queryKey: ["account", id] });
  }
  // An own-account transfer moves money on BOTH legs.
  if (opts.toIban) {
    const own = (qc.getQueryData<Account[]>(queryKeys.accounts) ?? [])
        .find((a) => a.iban === opts.toIban);
    if (own) void qc.invalidateQueries({ queryKey: ["account", own.id] });
  }
  void qc.invalidateQueries({ queryKey: ["transactions"] });
  void qc.invalidateQueries({ queryKey: ["summary"] });
  void qc.invalidateQueries({ queryKey: ["public-stats"] });
  void qc.invalidateQueries({ queryKey: queryKeys.unreadCount });
  void qc.invalidateQueries({ queryKey: queryKeys.recentOperations });
}

/** One operation this session dispatched without a confirmed answer. */
export type UnresolvedOperation = PendingOperation;

/**
 * The recovery surface's list. Unresolved operations = the records this
 * session's store holds (an operation is only "unresolved" because a response
 * never arrived); the SERVER list is the authority that ENDS that state:
 *
 *  - an operation the server recorded is resolved in fact: its outcome is no
 *    longer unknown and the money already shows in accounts/activity, so the
 *    record is cleared here and never offered as "unknown";
 *  - an operation the server has never seen stays listed for a safe keyed
 *    retry, and one cannot vanish just because the list is still loading or
 *    its fetch failed (absence proves nothing).
 *
 * The server records no read-state, so only operations THIS session still
 * holds a record for are surfaced: the store marks an op as unacknowledged,
 * the server is what ends that state. The store emits PENDING_OPS_EVENT on
 * every write, so an operation that ends ambiguously while this surface is
 * mounted (e.g. a deposit dialog behind it) appears without a reload.
 */
export function useUnresolvedOperations(): {
  items: UnresolvedOperation[];
  loading: boolean;
} {
  const qc = useQueryClient();
  const userId = currentUserId(qc);
  const recent = useRecentOperations(100);
  const [items, setItems] = React.useState<UnresolvedOperation[]>([]);

  // Latest server truth, readable from the store-change listener below (a
  // listener closure must never read a stale list).
  const recentRef = React.useRef(recent);
  React.useEffect(() => {
    recentRef.current = recent;
  });

  React.useEffect(() => {
    function reconcile() {
      if (!userId) {
        setItems([]);
        return;
      }
      const stored = listPendingOperations(userId);
      if (stored.length === 0) {
        setItems([]);
        return;
      }
      const list = recentRef.current;
      if (list.status === "success" && list.data) {
        // Reconcile on the COMPLETE identity: key AND originating account.
        // Two owned accounts (or a deposit and a transfer on one account)
        // may legitimately share a key; resolving one must never erase the
        // other. A server row without its namespace field cannot prove it is
        // THIS operation, so it resolves nothing (absence never erases).
        const recorded = new Set(
          (list.data.items ?? [])
            .filter((op) => op.idempotencyKey && op.originatingAccountId)
            .map((op) => op.idempotencyKey + ":" + op.originatingAccountId)
        );
        const kept: UnresolvedOperation[] = [];
        for (const op of stored) {
          if (recorded.has(op.key + ":" + op.accountId)) {
            removePendingOperation(userId, op.kind, op.accountId, op.key);
          } else {
            kept.push(op);
          }
        }
        setItems(kept);
      } else {
        setItems(stored);
      }
    }
    reconcile();
    if (userId) {
      window.addEventListener(PENDING_OPS_EVENT, reconcile);
      return () => window.removeEventListener(PENDING_OPS_EVENT, reconcile);
    }
    return undefined;
  }, [userId, recent.status, recent.data]);

  return { items, loading: recent.status === "pending" && items.length === 0 };
}

/**
 * The outcome of resolving ONE saved operation (the record is cleared for
 * {@code resolved}/{@code rejected}, kept for {@code unknown}).
 */
export type UnresolvedOutcome =
  | { kind: "resolved"; status: Tx["status"]; data: Tx | Deposit }
  | { kind: "rejected"; message: string }
  | { kind: "unknown"; message: string };

/**
 * Resolves ONE saved operation by re-sending the IDENTICAL keyed request the
 * original dispatch carried. The server deduplicates on the key, so the
 * replay either returns the ORIGINAL result (the operation already posted or
 * sits held: never a second credit) or completes a request that never
 * arrived. Outcomes:
 *
 *  - 2xx with a VERIFIED receipt → resolved: the receipt must name the same
 *    key, originating account and amount (and parse as a valid receipt);
 *    anything else is an UNKNOWN outcome, exactly like a lost response.
 *  - definitive 4xx → rejected: the server recorded nothing; cleared.
 *  - 401/403/404/408 → the check itself was never processed (session,
 *    authorization, routing, timeout): the earlier attempt's outcome stays
 *    unknown and the record is KEPT.
 *  - 409            → the key names a recorded operation under a DIFFERENT
 *    intent; its recorded truth is fetched by key instead of guessed.
 *  - network/5xx/429 → unknown: money may have moved; the record is KEPT so
 *    the user can check again, never silently erased.
 */
export async function resolveUnresolvedOperation(
  qc: QueryClient,
  record: UnresolvedOperation
): Promise<UnresolvedOutcome> {
  const { userId, key, kind, accountId } = record;
  // The replay is the BYTE-IDENTICAL keyed request against the account the
  // key is namespaced on; a malformed record must never produce a request
  // against an undefined account id.
  if (!accountId) {
    return {
      kind: "unknown",
      message:
        "This saved attempt predates complete operation identity, so it cannot be checked "
        + "automatically. Review the account history instead; a keyed replay can never double-post."
    };
  }
  const accountIds = [accountId];
  const replay = (): Promise<Tx | Deposit> =>
    kind === "deposit"
      ? api<Deposit>("/v1/accounts/" + accountId + "/deposit", {
          method: "POST",
          headers: { "Idempotency-Key": key },
          body: JSON.stringify({ amount: record.amount })
        })
      : api<Tx>("/v1/transfers", {
          method: "POST",
          headers: { "Idempotency-Key": key },
          body: JSON.stringify({
            fromAccountId: accountId,
            toIban: record.toIban,
            amount: record.amount,
            memo: record.memo || undefined
          })
        });
  // A 2xx is only a resolution when the receipt PROVES it answers THIS
  // dispatch: the recorded key, the originating account, the exact intent.
  // Anything less is an unknown outcome: the record survives.
  const finishIfVerified = (data: unknown): UnresolvedOutcome | null => {
    if (kind === "deposit") {
      const parsed = depositReceiptSchema.safeParse(data);
      if (!parsed.success) return null;
      const receipt = parsed.data;
      if (receipt.idempotencyKey !== key || receipt.account.id !== accountId) return null;
      if (!sameAmount(receipt.amount, record.amount)) return null;
      removePendingOperation(userId, kind, accountId, key);
      invalidateMoneyMovement(qc, { accountIds });
      return { kind: "resolved", status: receipt.status, data: receipt as unknown as Deposit };
    }
    const parsed = transferReceiptSchema.safeParse(data);
    if (!parsed.success) return null;
    const receipt = parsed.data;
    if (receipt.idempotencyKey !== key) return null;
    if (!sameAmount(receipt.amount, record.amount)) return null;
    // IBANs compare case-insensitively: the ledger stores them uppercased,
    // the stored intent may carry whatever case the user typed.
    if (record.toIban && (receipt.toIban ?? "").toUpperCase() !== record.toIban.toUpperCase()) return null;
    removePendingOperation(userId, kind, accountId, key);
    invalidateMoneyMovement(qc, { accountIds, toIban: record.toIban });
    return { kind: "resolved", status: receipt.status, data: receipt as unknown as Tx };
  };
  try {
    const data = await replay();
    const verified = finishIfVerified(data);
    if (verified) return verified;
    // A malformed or foreign receipt is an UNKNOWN outcome, never a failure
    // and never a completion: the server may have committed and answered
    // something unreadable (proxy, captive portal, broken codec).
    return { kind: "unknown", message: malformedSuccessError(kind).message };
  } catch (err) {
    // A 401 during RECOVERY is ambiguous, never definitive: the ORIGINAL
    // dispatch may have committed with a token that expired afterwards, so
    // this replay is refused by the filter without telling us what happened.
    // The record is kept; the user re-authenticates and checks again.
    if (err instanceof ApiError && err.status === 401) {
      return {
        kind: "unknown",
        message: replayInconclusiveFailure(kind).message
      };
    }
    // 403/404/408: the server never processed the check (authorization,
    // routing, timeout): it says nothing about the earlier attempt.
    if (isReplayInconclusive(err)) {
      return { kind: "unknown", message: replayInconclusiveFailure(kind).message };
    }
    const failure = classifyMoneyFailure(kind, err);
    if (!failure.ambiguous) {
      // Definitive rejection: the server recorded nothing: resolve + clear.
      removePendingOperation(userId, kind, accountId, key);
      return { kind: "rejected", message: failure.message };
    }
    if (err instanceof ApiError && err.status === 409) {
      try {
        // Same key, different intent: fetch the recorded operation by key,
        // scoped to the account whose namespace the key is unique on. The
        // server's answer is the truth, not our guess.
        const truth = await api<Tx>(
          "/v1/operations?key=" + encodeURIComponent(key)
            + "&accountId=" + encodeURIComponent(accountId)
            + "&kind=" + (kind === "deposit" ? "DEPOSIT" : "TRANSFER")
        );
        const verified = finishIfVerified(truth);
        if (verified) return verified;
        return { kind: "unknown", message: malformedSuccessError(kind).message };
      } catch {
        // Even the truth lookup failed: still unknown; keep the record.
      }
    }
    return { kind: "unknown", message: failure.message };
  }
}

// Definitive client rejections (validation, insufficient funds) mean the
// server recorded nothing, so the key was NOT consumed and the next attempt
// may mint a fresh one. 409 (key already names a different operation) and
// 429 (throttled) are NOT proof of rejection: the key is kept so a retry
// deduplicates against whatever the server actually did. The predicate
// itself is owned by money-failure.ts: the single authority on the
// definitive-vs-ambiguous boundary.

/** The signed-in user id from the me query, when it has loaded. */
function currentUserId(qc: ReturnType<typeof useQueryClient>): string | undefined {
  return qc.getQueryData<User>(queryKeys.me)?.id;
}

/**
 * Operation key lifecycle shared by deposit and transfer (lifecycle).
 * One key per SUBMITTED operation. A key is minted on the first dispatch and
 * reused only when this page is retrying the SAME submitted operation:
 *
 *  - an in-page retry keeps {@code keyRef} (set on the first attempt);
 *  - after a reload the page has no ref, so a stored pending record whose
 *    originating account AND reviewed intent (amount/destination when the
 *    draft still carries them) match the current submit is resumed with its
 *    own key;
 *  - a genuinely different draft (edited amount/destination/account) never
 *    inherits an older operation's key: the older record stays in the store
 *    (still recoverable) and the new submission mints a fresh key.
 *
 * The identity is persisted BEFORE the financial request is dispatched, so a
 * killed page or tab can never lose the record of what it sent.
 */
function nextOperationKey(
  qc: ReturnType<typeof useQueryClient>,
  kind: "transfer" | "deposit",
  keyRef: React.MutableRefObject<string | null>,
  intent: { accountId: string; amount: string; toIban?: string; memo?: string }
): { key: string; userId?: string } {
  const userId = currentUserId(qc);
  if (keyRef.current) {
    return { key: keyRef.current, userId };
  }
  if (userId) {
    const sameIntent = listPendingOperations(userId).find((op) => {
      if (op.kind !== kind) return false;
      // The originating account is part of the identity: one key on two
      // accounts is two operations, so a mismatch never inherits a key.
      if (op.accountId !== intent.accountId) return false;
      if (op.amount && op.amount !== intent.amount) return false;
      // A transfer's destination is part of the reviewed intent; a deposit
      // has none, so only compare when both sides carry one.
      if (kind === "transfer" && op.toIban && intent.toIban && op.toIban !== intent.toIban) {
        return false;
      }
      // The memo is part of the server's request hash: replaying a stored key
      // with a DIFFERENT memo would answer 409, so a changed memo is a new
      // intent that must mint a fresh key (the old record stays recoverable).
      if (kind === "transfer" && (op.memo ?? "") !== (intent.memo ?? "")) {
        return false;
      }
      return true;
    });
    if (sameIntent) {
      keyRef.current = sameIntent.key;
      return { key: sameIntent.key, userId };
    }
  }
  const key = crypto.randomUUID();
  keyRef.current = key;
  return { key, userId };
}

/** Records the dispatched identity BEFORE the request goes out. */
function persistBeforeDispatch(
  qc: ReturnType<typeof useQueryClient>,
  kind: "transfer" | "deposit",
  key: string,
  intent: { accountId: string; amount: string; toIban?: string; memo?: string }
): void {
  const userId = currentUserId(qc);
  if (!userId) return;
  upsertPendingOperation({
    userId,
    kind,
    key,
    accountId: intent.accountId,
    amount: intent.amount,
    toIban: kind === "transfer" ? intent.toIban : undefined,
    memo: kind === "transfer" ? intent.memo : undefined,
    createdAt: Date.now()
  });
}

/** Drops exactly ONE resolved operation record, addressed by its complete
 *  identity (never another account's, kind's or key's). */
function finishPendingOperation(
  qc: ReturnType<typeof useQueryClient>,
  kind: "transfer" | "deposit",
  accountId: string | null,
  key: string | null
): void {
  const userId = currentUserId(qc);
  if (userId && accountId && key) {
    removePendingOperation(userId, kind, accountId, key);
  }
}

/** Deposit → refresh accounts, history, summary, and the public hero. */
export type DepositMutation = UseMutationResult<
  DepositResult,
  ApiError,
  { accountId: string; amount: string }
> & { resetIdempotencyKey: () => void };

export function useDeposit(): DepositMutation {
  const qc = useQueryClient();
  const keyRef = React.useRef<string | null>(null);
  // Editing a draft is NOT resolving a submitted operation: the ref (in-page
  // retry memory) drops so the next submit mints a fresh key for the new
  // intent, but any pending record of an earlier ambiguous attempt survives
  // in the store: still recoverable, never silently erased by an edit.
  const resetIdempotencyKey = React.useCallback(() => {
    keyRef.current = null;
  }, []);
  const mutation = useMutation<DepositResult, ApiError, { accountId: string; amount: string }>({
    mutationFn: ({ accountId, amount }) => {
      // Every user-submitted funding must carry an idempotency key; an
      // identical replay returns the original result, never a second credit.
      const intent = { accountId, amount };
      const { key, userId } = nextOperationKey(qc, "deposit", keyRef, intent);
      // Persist the identity BEFORE the request: a killed tab must not lose
      // the record of what it sent.
      persistBeforeDispatch(qc, "deposit", key, intent);
      void userId;
      return api<DepositResult>("/v1/accounts/" + accountId + "/deposit", {
        method: "POST",
        headers: { "Idempotency-Key": key },
        body: JSON.stringify({ amount })
      }).then((data) => {
        // Validation happens INSIDE the mutation promise, so onSuccess can
        // only run for a receipt that proves it answers THIS dispatch: the
        // same key, the funded account, the exact decimal amount. A 2xx that
        // fails this is an UNKNOWN outcome (the server may have committed): it
        // throws the malformed-success error, the pending identity survives,
        // and the operation stays recoverable through the keyed replay.
        const parsed = depositReceiptSchema.safeParse(data);
        if (!parsed.success
            || parsed.data.idempotencyKey !== key
            || parsed.data.account.id !== accountId
            || !sameAmount(parsed.data.amount, amount)) {
          throw malformedSuccessError("deposit");
        }
        return parsed.data as DepositResult;
      });
    },
    onSuccess: (_data, variables) => {
      // The server answered with a VERIFIED receipt: resolve THIS operation's
      // record, scoped to its own account namespace.
      finishPendingOperation(qc, "deposit", variables.accountId, keyRef.current);
      keyRef.current = null;
      invalidateMoneyMovement(qc, { accountIds: [variables.accountId] });
    },
    onError: (err, variables) => {
      if (isDefinitiveRejection(err)) {
        // Definitive rejection (validation, funds): the server recorded
        // nothing and this operation identity is free.
        finishPendingOperation(qc, "deposit", variables.accountId, keyRef.current);
        keyRef.current = null;
        return;
      }
      // Ambiguous outcome (network, 5xx, 429, 409, malformed success): the
      // record was persisted BEFORE dispatch: the store already carries this
      // operation, and a retry (even after a reload) deduplicates on the
      // server under the same key.
    }
  });
  return Object.assign(mutation, { resetIdempotencyKey });
}

export interface TransferInput {
  fromAccountId: string;
  toIban: string;
  amount: string;
  memo?: string;
}

/**
 * Transfer → refresh accounts, history, summary, notifications, hero.
 *
 * Idempotency-key lifecycle: one key per *logical send intent*. The
 * key is minted on the first attempt and reused across retries (network
 * failures, 5xx, 429, reloads) so a retry can never double-post: the server
 * returns the original row. The key is dropped only when the intent is
 * resolved: success, a definitive client rejection (400/422: the server
 * recorded nothing), or the user edits the form into a new intent.
 */
export type TransferMutation = UseMutationResult<Tx, ApiError, TransferInput> & {
  resetIdempotencyKey: () => void;
};

export function useTransfer(): TransferMutation {
  const qc = useQueryClient();
  const keyRef = React.useRef<string | null>(null);
  // Editing a draft is NOT resolving a submitted operation: the ref (in-page
  // retry memory) drops so the next submit mints a fresh key for the new
  // intent, but any pending record of an earlier ambiguous attempt survives
  // in the store: still recoverable, never silently erased by an edit.
  const resetIdempotencyKey = React.useCallback(() => {
    keyRef.current = null;
  }, []);
  const mutation = useMutation<Tx, ApiError, TransferInput>({
    mutationFn: (input) => {
      const intent = {
        accountId: input.fromAccountId,
        amount: input.amount,
        toIban: input.toIban,
        memo: input.memo
      };
      const { key, userId } = nextOperationKey(qc, "transfer", keyRef, intent);
      // Persist the identity BEFORE the request: a killed tab must not lose
      // the record of what it sent.
      persistBeforeDispatch(qc, "transfer", key, intent);
      void userId;
      return api<Tx>("/v1/transfers", {
        method: "POST",
        headers: { "Idempotency-Key": key },
        body: JSON.stringify({ ...input, memo: input.memo || undefined })
      }).then((data) => {
        // Validation INSIDE the mutation promise (see useDeposit): only a
        // receipt naming this key, amount and destination may clear the
        // pending identity; anything else stays an unknown outcome.
        const parsed = transferReceiptSchema.safeParse(data);
        if (!parsed.success
            || parsed.data.idempotencyKey !== key
            || !sameAmount(parsed.data.amount, input.amount)
            || (parsed.data.toIban ?? "").toUpperCase() !== input.toIban.toUpperCase()) {
          throw malformedSuccessError("transfer");
        }
        return parsed.data as Tx;
      });
    },
    onSuccess: (_data, variables) => {
      finishPendingOperation(qc, "transfer", variables.fromAccountId, keyRef.current);
      keyRef.current = null;
      invalidateMoneyMovement(qc, {
        accountIds: [variables.fromAccountId],
        toIban: variables.toIban
      });
    },
    onError: (err, variables) => {
      if (isDefinitiveRejection(err)) {
        // Definitive rejection (validation, funds): the server recorded
        // nothing and this operation identity is free.
        finishPendingOperation(qc, "transfer", variables.fromAccountId, keyRef.current);
        keyRef.current = null;
        return;
      }
      // Ambiguous outcome (network drop, 5xx, 429, or a 409 conflict whose
      // original operation the retry will fetch): never discard the key: a
      // retry must deduplicate against whatever the server actually did. The
      // record was persisted BEFORE dispatch, so it survives a reload too.
    }
  });
  return Object.assign(mutation, { resetIdempotencyKey });
}

export function useOpenAccount(): UseMutationResult<Account, ApiError, string> {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (type) =>
      api<Account>("/v1/accounts", { method: "POST", body: JSON.stringify({ type }) }),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: queryKeys.accounts });
      // Opening an account posts an ACCOUNT_OPENED notification.
      void qc.invalidateQueries({ queryKey: queryKeys.unreadCount });
    }
  });
}

export function useAddBeneficiary(): UseMutationResult<
  Beneficiary,
  ApiError,
  { nickname: string; iban: string }
> {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (input) =>
      api<Beneficiary>("/v1/beneficiaries", { method: "POST", body: JSON.stringify(input) }),
    onSuccess: () => void qc.invalidateQueries({ queryKey: queryKeys.beneficiaries })
  });
}

export function useRemoveBeneficiary(): UseMutationResult<void, ApiError, string> {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id) => api<void>("/v1/beneficiaries/" + id, { method: "DELETE" }),
    onSuccess: () => void qc.invalidateQueries({ queryKey: queryKeys.beneficiaries })
  });
}

export function useMarkNotificationRead(): UseMutationResult<NotificationItem, ApiError, string> {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id) =>
      api<NotificationItem>("/v1/notifications/" + id + "/read", { method: "POST" }),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ["notifications"] });
      void qc.invalidateQueries({ queryKey: queryKeys.unreadCount });
    }
  });
}

/** Bulk mark-read: one server round trip, not one per unread notification. */
export function useMarkAllRead(): UseMutationResult<{ marked?: number }, ApiError, void> {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: () => api<{ marked?: number }>("/v1/notifications/read-all", { method: "POST" }),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ["notifications"] });
      void qc.invalidateQueries({ queryKey: queryKeys.unreadCount });
    }
  });
}

export function useIssueCard(): UseMutationResult<
  IssuedCard,
  ApiError,
  string
> {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (accountId) =>
      api<IssuedCard>("/v1/accounts/" + accountId + "/cards", { method: "POST" }),
    onSuccess: (_data, accountId) => {
      void qc.invalidateQueries({ queryKey: queryKeys.cards(accountId) });
      // Issuing a card posts a CARD_ISSUED notification.
      void qc.invalidateQueries({ queryKey: queryKeys.unreadCount });
    }
  });
}

export function useSetCardStatus(): UseMutationResult<
  CardItem,
  ApiError,
  { card: CardItem; frozen: boolean }
> {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ card, frozen }) =>
      api<CardItem>("/v1/cards/" + card.id + (frozen ? "/freeze" : "/unfreeze"), { method: "POST" }),
    onSuccess: (updated) => {
      void qc.setQueriesData<CardItem[]>({ queryKey: ["cards"] }, (prev) =>
        prev?.map((c) => (c.id === updated.id ? updated : c))
      );
    }
  });
}

// ---- security (TOTP) ----

/** Enabling a NEW factor only needs the new code. REPLACING an active factor
 *  (the backend detects it from the user's state) additionally requires the
 *  current password and a code from the EXISTING authenticator. */
export type TotpEnableInput = { code: string; currentPassword?: string; currentCode?: string };

export function useTotpSetup(): UseMutationResult<{ secret: string; qrDataUri: string }, ApiError, void> {
  return useMutation({
    mutationFn: () => api<{ secret: string; qrDataUri: string }>("/v1/auth/totp/setup", { method: "POST" })
  });
}

/** Abandons a pending enrollment: never touches an active factor. */
export function useTotpCancel(): UseMutationResult<void, ApiError, void> {
  return useMutation({
    mutationFn: () => api<void>("/v1/auth/totp/cancel", { method: "POST" })
  });
}

export function useTotpEnable(): UseMutationResult<AuthResponse, ApiError, TotpEnableInput> {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ code, currentPassword, currentCode }) =>
      api<AuthResponse>("/v1/auth/totp/enable", {
        method: "POST",
        // Undefined values are dropped by JSON.stringify, so an initial
        // enrollment sends only the new code.
        body: JSON.stringify({
          code,
          currentPassword: currentPassword || undefined,
          currentCode: currentCode || undefined
        })
      }),
    onSuccess: () => void qc.invalidateQueries({ queryKey: queryKeys.me })
  });
}

/** Disabling MFA requires the current password AND a factor code. */
export function useTotpDisable(): UseMutationResult<AuthResponse, ApiError, { password: string; code: string }> {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ password, code }) =>
      api<AuthResponse>("/v1/auth/totp/disable", {
        method: "POST",
        body: JSON.stringify({ password, code })
      }),
    onSuccess: () => void qc.invalidateQueries({ queryKey: queryKeys.me })
  });
}

// ---- admin ----

export function useAdminUsers(q: string, page = 0): UseQueryResult<Page<User>, ApiError> {
  return useQuery({
    queryKey: queryKeys.admin.users(q, page),
    queryFn: () =>
      api<Page<User>>("/v1/admin/users?q=" + encodeURIComponent(q) + "&size=5&page=" + page)
  });
}

export function useAdminReviewQueue(page = 0): UseQueryResult<Page<Tx>, ApiError> {
  return useQuery({
    queryKey: queryKeys.admin.reviewQueue(page),
    queryFn: () => api<Page<Tx>>("/v1/admin/transactions?flagged=true&reviewed=false&size=20&page=" + page)
  });
}

export function useAdminTransactions(): UseQueryResult<Tx[], ApiError> {
  return useQuery({
    queryKey: queryKeys.admin.transactions,
    queryFn: async () => {
      const page = await api<Page<Tx>>("/v1/admin/transactions?size=8");
      return page.content ?? [];
    }
  });
}

export function useAdminAudits(action: string, page = 0): UseQueryResult<Page<Audit>, ApiError> {
  return useQuery({
    queryKey: queryKeys.admin.audits(action, page),
    queryFn: () =>
      api<Page<Audit>>(
        "/v1/admin/audit-logs?size=10&page=" + page + (action ? "&action=" + encodeURIComponent(action) : "")
      )
  });
}

export function useAdminDailyTotals(): UseQueryResult<DayTotal[], ApiError> {
  return useQuery({
    queryKey: queryKeys.admin.dailyTotals,
    queryFn: () => api<DayTotal[]>("/v1/admin/reports/daily-totals?days=14")
  });
}

export function useAdminUserAccounts(userId: string): UseQueryResult<Account[], ApiError> {
  return useQuery({
    queryKey: queryKeys.admin.userAccounts(userId),
    queryFn: () => api<Account[]>("/v1/admin/users/" + userId + "/accounts"),
    enabled: userId.length > 0
  });
}

export type DecisionInput = {
  id: string;
  reason: string;
  expectedStatus: Tx["status"];
  expectedReviewed: boolean;
};

/**
 * Operator decision. Every decision carries the bounded
 * REQUIRED reason plus the case state the console displayed; when the row is
 * no longer in that state the server answers 409 and the queue refetches to
 * the winning decision. A lost race must never keep the optimistic toast.
 */
function useDecision(path: "review" | "decline"): UseMutationResult<Tx, ApiError, DecisionInput> {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, reason, expectedStatus, expectedReviewed }) =>
      api<Tx>("/v1/admin/transactions/" + id + "/" + path, {
        method: "POST",
        body: JSON.stringify({ reason, expectedStatus, expectedReviewed })
      }),
    onSuccess: () => {
      // Invalidate every queue page (a prefix match): resolving an item can
      // shift rows across page boundaries.
      void qc.invalidateQueries({ queryKey: ["admin", "review-queue"] });
      void qc.invalidateQueries({ queryKey: ["transactions"] });
    },
    onError: (err) => {
      // A stale-decision 409 must refresh immediately so the losing console
      // renders the winner's authoritative state, not its stale row.
      if (err instanceof ApiError && err.status === 409) {
        void qc.invalidateQueries({ queryKey: ["admin", "review-queue"] });
        void qc.invalidateQueries({ queryKey: ["transactions"] });
      }
    }
  });
}

/** Approves a HELD transfer (settles it) or acknowledges a flagged POSTED deposit. */
export function useReviewTransaction(): UseMutationResult<Tx, ApiError, DecisionInput> {
  return useDecision("review");
}

/** Declines a HELD transfer; nothing has moved, so no money ever leaves the sender. */
export function useDeclineTransaction(): UseMutationResult<Tx, ApiError, DecisionInput> {
  return useDecision("decline");
}

/**
 * Reverses a POSTED transaction (V29) with the operator's mandatory reason.
 * The server authors a NEW reversal row that moves the money back: one per
 * original: so the success response is that row, never the edited original.
 * Reversal moves money, so every operator readout that reflects balances or
 * flow is refreshed from the same graph the console lists use.
 */
export function useReverseTransaction(): UseMutationResult<
  Tx,
  ApiError,
  { id: string; reason: string }
> {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, reason }) =>
      api<Tx>("/v1/admin/transactions/" + id + "/reverse", {
        method: "POST",
        body: JSON.stringify({ reason })
      }),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ["admin", "review-queue"] });
      void qc.invalidateQueries({ queryKey: ["admin", "transactions"] });
      void qc.invalidateQueries({ queryKey: queryKeys.admin.dailyTotals });
      void qc.invalidateQueries({ queryKey: queryKeys.accounts });
    }
  });
}

export function useAdminSetAccountStatus(): UseMutationResult<
  Account,
  ApiError,
  { account: Account; frozen: boolean; userId: string }
> {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ account, frozen }) =>
      api<Account>("/v1/admin/accounts/" + account.id + (frozen ? "/freeze" : "/unfreeze"), {
        method: "POST"
      }),
    onSuccess: (updated, { userId }) => {
      void qc.setQueriesData<Account[]>({ queryKey: queryKeys.admin.userAccounts(userId) }, (prev) =>
        prev?.map((a) => (a.id === updated.id ? updated : a))
      );
      void qc.invalidateQueries({ queryKey: queryKeys.accounts });
    }
  });
}
