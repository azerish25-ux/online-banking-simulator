"use client";

import {
  useMutation,
  useQuery,
  useQueryClient,
  type UseMutationResult,
  type UseQueryResult
} from "@tanstack/react-query";
import * as React from "react";
import { ApiError, api } from "./api";
import {
  listPendingOperations,
  removePendingOperation,
  upsertPendingOperation
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
  // The authorized recovery list - the caller's own recent keyed operations.
  recentOperations: ["operations", "recent"] as const,
  // The cursor (blank = newest page) is part of the key: two different
  // positions in the same feed are two different result sets and must not
  // collide in the cache.
  transactions: (accountId: string, cursor: string, size: number, from?: string, to?: string) =>
    ["transactions", accountId, cursor, size, from ?? "", to ?? ""] as const,
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
  return useQuery({ queryKey: queryKeys.accounts, queryFn: () => api<Account[]>("/v1/accounts") });
}

export function useAccount(id: string): UseQueryResult<Account, ApiError> {
  return useQuery({
    queryKey: ["account", id],
    queryFn: () => api<Account>("/v1/accounts/" + id),
    enabled: id.length > 0
  });
}

/**
 * One of the caller's own operations by id (F11 durable receipt). The route
 * is bookmarkable and the lookup is originator-scoped server-side; a foreign
 * or unknown id answers 404 either way. Re-fetching after a HELD→POSTED
 * transition returns the current authoritative status and posting time.
 */
export function useTransferReceipt(id: string): UseQueryResult<Tx, ApiError> {
  return useQuery({
    queryKey: queryKeys.transferReceipt(id),
    queryFn: () => api<Tx>("/v1/transfers/" + id),
    enabled: id.length > 0
  });
}

export function useTransactions(
  accountId: string,
  cursor = "",
  size = 10,
  from?: string,
  to?: string
): UseQueryResult<HistoryPage<Tx>, ApiError> {
  return useQuery({
    queryKey: queryKeys.transactions(accountId, cursor, size, from, to),
    queryFn: () => {
      let url = "/v1/transactions?accountId=" + accountId + "&size=" + size;
      if (cursor) url += "&cursor=" + encodeURIComponent(cursor);
      if (from) url += "&from=" + from;
      if (to) url += "&to=" + to;
      return api<HistoryPage<Tx>>(url);
    },
    enabled: accountId.length > 0
  });
}

export function useSummary(accountId: string, months = 6): UseQueryResult<MonthPoint[], ApiError> {
  return useQuery({
    queryKey: queryKeys.summary(accountId, months),
    queryFn: () => api<MonthPoint[]>("/v1/accounts/" + accountId + "/summary?months=" + months),
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
 * A deposit answer (F06 lifecycle): the updated account plus the recoverable
 * operation identity (transaction id, idempotency key, authoritative status)
 * that backs the durable receipt lookup. Aliased straight off the generated
 * OpenAPI schema - one authoritative definition, no parallel shape.
 */
export type DepositResult = Deposit;

export function useRecentOperations(limit = 25): UseQueryResult<OperationList, ApiError> {
  return useQuery({
    queryKey: queryKeys.recentOperations,
    queryFn: () => api<OperationList>("/v1/operations/recent?limit=" + limit)
  });
}

/**
 * Definitive client rejections (validation, insufficient funds) mean the
 * server recorded nothing, so the key was NOT consumed and the next attempt
 * may mint a fresh one. 409 (key already names a different operation) and
 * 429 (throttled) are NOT proof of rejection - the key is kept so a retry
 * deduplicates against whatever the server actually did (F06).
 */
function isDefinitiveRejection(err: unknown): boolean {
  return err instanceof ApiError && err.status >= 400 && err.status < 500
      && err.status !== 409 && err.status !== 429;
}

/** The signed-in user id from the me query, when it has loaded. */
function currentUserId(qc: ReturnType<typeof useQueryClient>): string | undefined {
  return qc.getQueryData<User>(queryKeys.me)?.id;
}

/**
 * Operation key lifecycle shared by deposit and transfer (F06 lifecycle).
 * One key per SUBMITTED operation. A key is minted on the first dispatch and
 * reused only when this page is retrying the SAME submitted operation:
 *
 *  - an in-page retry keeps {@code keyRef} (set on the first attempt);
 *  - after a reload the page has no ref, so a stored pending record whose
 *    originating account AND reviewed intent (amount/destination when the
 *    draft still carries them) match the current submit is resumed with its
 *    own key;
 *  - a genuinely different draft (edited amount/destination/account) never
 *    inherits an older operation's key - the older record stays in the store
 *    (still recoverable) and the new submission mints a fresh key.
 *
 * The identity is persisted BEFORE the financial request is dispatched, so a
 * killed page or tab can never lose the record of what it sent.
 */
function nextOperationKey(
  qc: ReturnType<typeof useQueryClient>,
  kind: "transfer" | "deposit",
  keyRef: React.MutableRefObject<string | null>,
  intent: { accountId: string; amount: string; toIban?: string }
): { key: string; userId?: string } {
  const userId = currentUserId(qc);
  if (keyRef.current) {
    return { key: keyRef.current, userId };
  }
  if (userId) {
    const sameIntent = listPendingOperations(userId).find((op) => {
      if (op.kind !== kind) return false;
      if (op.accountId && op.accountId !== intent.accountId) return false;
      if (op.amount && op.amount !== intent.amount) return false;
      // A transfer's destination is part of the reviewed intent; a deposit
      // has none, so only compare when both sides carry one.
      if (kind === "transfer" && op.toIban && intent.toIban && op.toIban !== intent.toIban) {
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

/** Records the dispatched identity BEFORE the request goes out (F06). */
function persistBeforeDispatch(
  qc: ReturnType<typeof useQueryClient>,
  kind: "transfer" | "deposit",
  key: string,
  intent: { accountId: string; amount: string; toIban?: string }
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
    createdAt: Date.now()
  });
}

/** Drops exactly ONE resolved operation record (never another's). */
function finishPendingOperation(
  qc: ReturnType<typeof useQueryClient>,
  kind: "transfer" | "deposit",
  key: string | null
): void {
  const userId = currentUserId(qc);
  if (userId && key) {
    removePendingOperation(userId, kind, key);
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
  // in the store - still recoverable, never silently erased by an edit.
  const resetIdempotencyKey = React.useCallback(() => {
    keyRef.current = null;
  }, []);
  const mutation = useMutation<DepositResult, ApiError, { accountId: string; amount: string }>({
    mutationFn: ({ accountId, amount }) => {
      // Every user-submitted funding must carry an idempotency key (F06); an
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
      });
    },
    onSuccess: (_data, variables) => {
      // The server answered authoritatively: resolve THIS operation's record.
      const key = keyRef.current;
      finishPendingOperation(qc, "deposit", key);
      keyRef.current = null;
      // Invalidate the funded account's detail as well as the lists that read
      // from it (a deposit changes the detail of the account it funded).
      void qc.invalidateQueries({ queryKey: queryKeys.accounts });
      void qc.invalidateQueries({ queryKey: ["account", variables.accountId] });
      void qc.invalidateQueries({ queryKey: ["transactions"] });
      void qc.invalidateQueries({ queryKey: ["summary"] });
      void qc.invalidateQueries({ queryKey: ["public-stats"] });
      // A deposit posts a DEPOSIT_POSTED notification for this user.
      void qc.invalidateQueries({ queryKey: queryKeys.unreadCount });
      void qc.invalidateQueries({ queryKey: queryKeys.recentOperations });
    },
    onError: (err) => {
      const key = keyRef.current;
      if (isDefinitiveRejection(err)) {
        // Definitive rejection (validation, funds): the server recorded
        // nothing and this operation identity is free.
        finishPendingOperation(qc, "deposit", key);
        keyRef.current = null;
        return;
      }
      // Ambiguous outcome (network, 5xx, 429, 409): the record was persisted
      // BEFORE dispatch - the store already carries this operation, and a
      // retry (even after a reload) deduplicates on the server.
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
 * Idempotency-key lifecycle (F06): one key per *logical send intent*. The
 * key is minted on the first attempt and reused across retries (network
 * failures, 5xx, 429, reloads) so a retry can never double-post - the server
 * returns the original row. The key is dropped only when the intent is
 * resolved: success, a definitive client rejection (400/422 - the server
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
  // in the store - still recoverable, never silently erased by an edit.
  const resetIdempotencyKey = React.useCallback(() => {
    keyRef.current = null;
  }, []);
  const mutation = useMutation<Tx, ApiError, TransferInput>({
    mutationFn: (input) => {
      const intent = {
        accountId: input.fromAccountId,
        amount: input.amount,
        toIban: input.toIban
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
      });
    },
    onSuccess: (_data, variables) => {
      const key = keyRef.current;
      finishPendingOperation(qc, "transfer", key);
      keyRef.current = null;
      // Invalidate both legs' account details when the destination is one of
      // the caller's own accounts (self/own-account transfers move money on
      // both sides).
      const ownAccounts = qc.getQueryData<Account[]>(queryKeys.accounts) ?? [];
      const toOwned = ownAccounts.find((a) => a.iban === variables.toIban);
      void qc.invalidateQueries({ queryKey: queryKeys.accounts });
      void qc.invalidateQueries({ queryKey: ["account", variables.fromAccountId] });
      if (toOwned) {
        void qc.invalidateQueries({ queryKey: ["account", toOwned.id] });
      }
      void qc.invalidateQueries({ queryKey: ["transactions"] });
      void qc.invalidateQueries({ queryKey: ["summary"] });
      void qc.invalidateQueries({ queryKey: queryKeys.unreadCount });
      void qc.invalidateQueries({ queryKey: ["public-stats"] });
      void qc.invalidateQueries({ queryKey: queryKeys.recentOperations });
    },
    onError: (err) => {
      if (isDefinitiveRejection(err)) {
        // Definitive rejection (validation, funds): the server recorded
        // nothing and this operation identity is free.
        const key = keyRef.current;
        finishPendingOperation(qc, "transfer", key);
        keyRef.current = null;
        return;
      }
      // Ambiguous outcome (network drop, 5xx, 429, or a 409 conflict whose
      // original operation the retry will fetch): never discard the key - a
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

/** Bulk mark-read - one server round trip, not one per unread notification. */
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
 *  current password and a code from the EXISTING authenticator (F02). */
export type TotpEnableInput = { code: string; currentPassword?: string; currentCode?: string };

export function useTotpSetup(): UseMutationResult<{ secret: string; qrDataUri: string }, ApiError, void> {
  return useMutation({
    mutationFn: () => api<{ secret: string; qrDataUri: string }>("/v1/auth/totp/setup", { method: "POST" })
  });
}

/** Abandons a pending enrollment - never touches an active factor (F02). */
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

/** Disabling MFA requires the current password AND a factor code (F02). */
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

export function useReviewTransaction(): UseMutationResult<Tx, ApiError, string> {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id) => api<Tx>("/v1/admin/transactions/" + id + "/review", { method: "POST" }),
    onSuccess: () => {
      // Invalidate every queue page (a prefix match): resolving an item can
      // shift rows across page boundaries.
      void qc.invalidateQueries({ queryKey: ["admin", "review-queue"] });
      void qc.invalidateQueries({ queryKey: ["transactions"] });
    }
  });
}

/** Declines a HELD transfer; nothing has moved, so no money ever leaves the sender. */
export function useDeclineTransaction(): UseMutationResult<Tx, ApiError, string> {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id) => api<Tx>("/v1/admin/transactions/" + id + "/decline", { method: "POST" }),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ["admin", "review-queue"] });
      void qc.invalidateQueries({ queryKey: ["transactions"] });
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
