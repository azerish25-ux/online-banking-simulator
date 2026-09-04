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
import type {
  Account,
  Audit,
  Beneficiary,
  CardItem,
  DayTotal,
  IssuedCard,
  MonthPoint,
  NotificationItem,
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
  transactions: (accountId: string, page: number, from?: string, to?: string) =>
    ["transactions", accountId, page, from ?? "", to ?? ""] as const,
  summary: (accountId: string, months: number) => ["summary", accountId, months] as const,
  beneficiaries: ["beneficiaries"] as const,
  notifications: (page: number) => ["notifications", page] as const,
  unreadCount: ["notifications", "unread"] as const,
  cards: (accountId: string) => ["cards", accountId] as const,
  admin: {
    users: (q: string, page: number) => ["admin", "users", q, page] as const,
    transactions: ["admin", "transactions"] as const,
    reviewQueue: ["admin", "review-queue"] as const,
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

export function useTransactions(
  accountId: string,
  page = 0,
  size = 10,
  from?: string,
  to?: string
): UseQueryResult<Page<Tx>, ApiError> {
  return useQuery({
    queryKey: queryKeys.transactions(accountId, page, from, to),
    queryFn: () => {
      let url = "/v1/transactions?accountId=" + accountId + "&page=" + page + "&size=" + size;
      if (from) url += "&from=" + from;
      if (to) url += "&to=" + to;
      return api<Page<Tx>>(url);
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

/** Deposit → refresh accounts, history, summary, and the public hero. */
export function useDeposit(): UseMutationResult<Account, ApiError, { accountId: string; amount: string }> {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ accountId, amount }) =>
      api<Account>("/v1/accounts/" + accountId + "/deposit", {
        method: "POST",
        body: JSON.stringify({ amount })
      }),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: queryKeys.accounts });
      void qc.invalidateQueries({ queryKey: ["transactions"] });
      void qc.invalidateQueries({ queryKey: ["summary"] });
      void qc.invalidateQueries({ queryKey: ["public-stats"] });
      // A deposit posts a DEPOSIT_POSTED notification for this user.
      void qc.invalidateQueries({ queryKey: queryKeys.unreadCount });
    }
  });
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
 * Idempotency-key lifecycle: one key per *logical send intent*. The key is
 * minted on the first attempt and reused across retries (network failures,
 * 5xx) so a retry can never double-post - the server returns the original
 * row. The key is dropped after success, after a definitive client error
 * (4xx - retrying would repeat a rejected transfer), or when the user edits
 * the form (a new intent needs a new key).
 */
export type TransferMutation = UseMutationResult<Tx, ApiError, TransferInput> & {
  resetIdempotencyKey: () => void;
};

export function useTransfer(): TransferMutation {
  const qc = useQueryClient();
  const keyRef = React.useRef<string | null>(null);
  const resetIdempotencyKey = React.useCallback(() => {
    keyRef.current = null;
  }, []);
  const mutation = useMutation<Tx, ApiError, TransferInput>({
    mutationFn: (input) => {
      const key = keyRef.current ?? (keyRef.current = crypto.randomUUID());
      return api<Tx>("/v1/transfers", {
        method: "POST",
        headers: { "Idempotency-Key": key },
        body: JSON.stringify({ ...input, memo: input.memo || undefined })
      });
    },
    onSuccess: () => {
      keyRef.current = null;
      void qc.invalidateQueries({ queryKey: queryKeys.accounts });
      void qc.invalidateQueries({ queryKey: ["transactions"] });
      void qc.invalidateQueries({ queryKey: ["summary"] });
      void qc.invalidateQueries({ queryKey: queryKeys.unreadCount });
      void qc.invalidateQueries({ queryKey: ["public-stats"] });
    },
    onError: (err) => {
      if (err instanceof ApiError && err.status < 500) {
        // Definitive rejection (validation, funds, conflict): a retry with
        // the same key would only fail again - start a fresh intent.
        keyRef.current = null;
      }
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

export function useTotpSetup(): UseMutationResult<{ secret: string; qrDataUri: string }, ApiError, void> {
  return useMutation({
    mutationFn: () => api<{ secret: string; qrDataUri: string }>("/v1/auth/totp/setup", { method: "POST" })
  });
}

export function useTotpEnable(): UseMutationResult<User, ApiError, string> {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (code) =>
      api<User>("/v1/auth/totp/enable", { method: "POST", body: JSON.stringify({ code }) }),
    onSuccess: () => void qc.invalidateQueries({ queryKey: queryKeys.me })
  });
}

export function useTotpDisable(): UseMutationResult<User, ApiError, string> {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (code) =>
      api<User>("/v1/auth/totp/disable", { method: "POST", body: JSON.stringify({ code }) }),
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

export function useAdminReviewQueue(): UseQueryResult<Tx[], ApiError> {
  return useQuery({
    queryKey: queryKeys.admin.reviewQueue,
    queryFn: async () => {
      const page = await api<Page<Tx>>("/v1/admin/transactions?flagged=true&reviewed=false&size=20");
      return page.content ?? [];
    }
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
      void qc.invalidateQueries({ queryKey: queryKeys.admin.reviewQueue });
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
      void qc.invalidateQueries({ queryKey: queryKeys.admin.reviewQueue });
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
    onSuccess: (updated, { account, userId }) => {
      void qc.setQueriesData<Account[]>({ queryKey: queryKeys.admin.userAccounts(userId) }, (prev) =>
        prev?.map((a) => (a.id === updated.id ? updated : a))
      );
      void qc.invalidateQueries({ queryKey: queryKeys.accounts });
    }
  });
}
