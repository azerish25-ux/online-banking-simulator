"use client";

import {
  useMutation,
  useQuery,
  useQueryClient,
  type UseMutationResult,
  type UseQueryResult
} from "@tanstack/react-query";
import { ApiError, api } from "./api";
import type {
  Account,
  Beneficiary,
  CardItem,
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
  notifications: ["notifications"] as const,
  unreadCount: ["notifications", "unread"] as const,
  cards: (accountId: string) => ["cards", accountId] as const,
  admin: {
    users: (q: string) => ["admin", "users", q] as const,
    transactions: ["admin", "transactions"] as const,
    reviewQueue: ["admin", "review-queue"] as const,
    audits: (action: string) => ["admin", "audits", action] as const,
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

export function useNotifications(): UseQueryResult<NotificationItem[], ApiError> {
  return useQuery({
    queryKey: queryKeys.notifications,
    queryFn: async () => {
      const page = await api<Page<NotificationItem>>("/v1/notifications?size=30");
      return page.content ?? [];
    }
  });
}

export function useUnreadCount(): UseQueryResult<number, ApiError> {
  return useQuery({
    queryKey: queryKeys.unreadCount,
    queryFn: async () => {
      const r = await api<{ unread?: number }>("/v1/notifications/unread-count");
      return r.unread ?? 0;
    }
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
    }
  });
}

export interface TransferInput {
  fromAccountId: string;
  toIban: string;
  amount: string;
  memo?: string;
}

/** Transfer → refresh accounts, history, summary, notifications, hero. */
export function useTransfer(): UseMutationResult<
  { id: string; toIban: string; amount: string; flagged?: boolean },
  ApiError,
  TransferInput
> {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (input) =>
      api("/v1/transfers", {
        method: "POST",
        headers: { "Idempotency-Key": crypto.randomUUID() },
        body: JSON.stringify({ ...input, memo: input.memo || undefined })
      }),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: queryKeys.accounts });
      void qc.invalidateQueries({ queryKey: ["transactions"] });
      void qc.invalidateQueries({ queryKey: ["summary"] });
      void qc.invalidateQueries({ queryKey: queryKeys.unreadCount });
      void qc.invalidateQueries({ queryKey: ["public-stats"] });
    }
  });
}

export function useOpenAccount(): UseMutationResult<Account, ApiError, string> {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (type) =>
      api<Account>("/v1/accounts", { method: "POST", body: JSON.stringify({ type }) }),
    onSuccess: () => void qc.invalidateQueries({ queryKey: queryKeys.accounts })
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

export function useMarkNotificationRead(): UseMutationResult<void, ApiError, string> {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id) => api<void>("/v1/notifications/" + id + "/read", { method: "POST" }),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: queryKeys.notifications });
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
    onSuccess: (_data, accountId) => void qc.invalidateQueries({ queryKey: queryKeys.cards(accountId) })
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

// ---- admin ----

export function useAdminUsers(q: string): UseQueryResult<Page<User>, ApiError> {
  return useQuery({
    queryKey: queryKeys.admin.users(q),
    queryFn: () => api<Page<User>>("/v1/admin/users?q=" + encodeURIComponent(q) + "&size=20")
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

export function useAdminAudits(action: string): UseQueryResult<Page<import("./api-types").Audit>, ApiError> {
  return useQuery({
    queryKey: queryKeys.admin.audits(action),
    queryFn: () =>
      api<Page<import("./api-types").Audit>>(
        "/v1/admin/audit-logs?size=15" + (action ? "&action=" + encodeURIComponent(action) : "")
      )
  });
}

export function useAdminDailyTotals(): UseQueryResult<import("./api-types").DayTotal[], ApiError> {
  return useQuery({
    queryKey: queryKeys.admin.dailyTotals,
    queryFn: () => api<import("./api-types").DayTotal[]>("/v1/admin/reports/daily-totals?days=14")
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
