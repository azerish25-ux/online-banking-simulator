"use client";

import * as React from "react";
import { AppShell } from "../../components/layout/app-shell";
import { Badge } from "../../components/ui/badge";
import { Button } from "../../components/ui/button";
import { Card, CardDescription, CardTitle } from "../../components/ui/card";
import { EmptyState } from "../../components/ui/empty-state";
import { Field, Input } from "../../components/ui/input";
import { Skeleton } from "../../components/ui/skeleton";
import { TD, TH, THead, TRow, Table } from "../../components/ui/table";
import { useToast } from "../../components/feedback/toast";
import { ApiError, getToken } from "../../lib/api";
import {
  useAdminAudits,
  useAdminDailyTotals,
  useAdminReviewQueue,
  useAdminSetAccountStatus,
  useAdminTransactions,
  useAdminUserAccounts,
  useAdminUsers,
  useReviewTransaction
} from "../../lib/queries";
import { adminStatementUrl } from "../../lib/statements";
import { fmtDate, usd } from "../../lib/format";

export default function AdminPage() {
  const { push } = useToast();
  const [query, setQuery] = React.useState("");
  const [submittedQuery, setSubmittedQuery] = React.useState("");
  const [selectedId, setSelectedId] = React.useState("");
  const [auditAction, setAuditAction] = React.useState("");

  // Each concern is its own cached query: filtering audits no longer refetches
  // the queue or totals, and reviews/freeze invalidate exactly what changed.
  const usersQuery = useAdminUsers(submittedQuery);
  const queue = useAdminReviewQueue();
  const recent = useAdminTransactions();
  const totals = useAdminDailyTotals();
  const audits = useAdminAudits(auditAction);
  const accounts = useAdminUserAccounts(selectedId);

  const review = useReviewTransaction();
  const setStatus = useAdminSetAccountStatus();

  const [forbidden, setForbidden] = React.useState(false);
  React.useEffect(() => {
    const err: ApiError | null =
      (usersQuery.error as ApiError | null) ??
      (queue.error as ApiError | null) ??
      (recent.error as ApiError | null) ??
      (totals.error as ApiError | null) ??
      (audits.error as ApiError | null);
    if (err?.status === 403) setForbidden(true);
  }, [usersQuery.error, queue.error, recent.error, totals.error, audits.error]);

  React.useEffect(() => {
    if (review.isSuccess) push("Transfer marked reviewed.", "success");
  }, [review.isSuccess, push]);

  React.useEffect(() => {
    if (setStatus.isSuccess && setStatus.data) {
      push(setStatus.data.status === "FROZEN" ? "Account frozen." : "Account re-activated.", "success");
    }
  }, [setStatus.isSuccess, setStatus.data, push]);

  async function downloadAccountPdf(accountId: string) {
    try {
      const res = await fetch(adminStatementUrl(accountId), {
        headers: { Authorization: "Bearer " + (getToken() ?? "") }
      });
      if (!res.ok) throw new Error("Export failed: " + res.status);
      const blob = await res.blob();
      const url = URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.href = url;
      a.download = "statement.pdf";
      a.click();
      URL.revokeObjectURL(url);
      push("Statement downloaded.", "success");
    } catch (e) {
      push(e instanceof Error ? e.message : "Export failed", "error");
    }
  }

  if (forbidden) {
    return (
      <AppShell>
        <EmptyState title="Admins only" description="Your account does not have the operator role." />
      </AppShell>
    );
  }

  const selected = usersQuery.data?.content.find((u) => u.id === selectedId) ?? null;
  const userRows = usersQuery.data?.content ?? [];
  const accountRows = accounts.data ?? [];
  const queueRows = queue.data ?? [];
  const recentRows = recent.data ?? [];
  const totalRows = totals.data ?? [];
  const auditRows = audits.data?.content ?? [];

  return (
    <AppShell>
      <h1 className="text-2xl font-bold tracking-tight">Operations</h1>
      <p className="muted mt-1 text-sm">Users, account status, money flow and the audit trail.</p>

      <div className="mt-4 grid gap-4 lg:grid-cols-2">
        <Card>
          <div className="mb-3 flex items-center justify-between">
            <CardTitle>Review queue</CardTitle>
            <Badge tone={queueRows.length > 0 ? "danger" : "success"}>{queueRows.length} open</Badge>
          </div>
          {queueRows.length === 0 ? (
            <CardDescription>No flagged transfers awaiting review.</CardDescription>
          ) : (
            <ul className="space-y-2">
              {queueRows.map((t) => (
                <li key={t.id} className="flex items-center justify-between gap-2 rounded-md border border-line p-3">
                  <div className="text-sm">
                    <span className="mono">{t.fromIban ? "..." + t.fromIban.slice(-6) : "-"} → {t.toIban ? "..." + t.toIban.slice(-6) : "-"}</span>
                    <span className="ml-2 font-semibold tabular-nums">{usd(t.amount)}</span>
                    <span className="muted ml-2 text-xs">{fmtDate(t.createdAt)}</span>
                  </div>
                  <Button size="sm" variant="secondary" disabled={review.isPending} onClick={() => review.mutate(t.id)}>
                    Mark reviewed
                  </Button>
                </li>
              ))}
            </ul>
          )}
        </Card>
        <Card>
          <CardTitle>Daily totals · last 14 days</CardTitle>
          <div className="mt-3">
            {totals.isLoading ? (
              <div className="space-y-2"><Skeleton className="h-8" /><Skeleton className="h-8" /></div>
            ) : (
              <Table>
                <THead><TRow><TH>Date</TH><TH className="text-right">Transfers</TH><TH className="text-right">Volume</TH><TH className="text-right">Deposits</TH></TRow></THead>
                <tbody>
                  {totalRows.slice(-14).map((d) => (
                    <TRow key={d.date}>
                      <TD className="whitespace-nowrap">{d.date}</TD>
                      <TD className="text-right tabular-nums">{d.transfers}</TD>
                      <TD className="text-right tabular-nums">{usd(d.transferVolume)}</TD>
                      <TD className="text-right tabular-nums">{d.deposits}</TD>
                    </TRow>
                  ))}
                </tbody>
              </Table>
            )}
          </div>
        </Card>
      </div>

      <div className="mt-4 grid gap-4 lg:grid-cols-2">
        <Card>
          <CardTitle>Users</CardTitle>
          <form
            onSubmit={(e) => { e.preventDefault(); setSubmittedQuery(query); }}
            className="mt-3 flex gap-2"
          >
            <Input aria-label="Search users" placeholder="email or name..." value={query} onChange={(e) => setQuery(e.target.value)} />
            <Button type="submit" variant="secondary">Search</Button>
          </form>
          {usersQuery.isLoading ? (
            <div className="mt-3 space-y-2"><Skeleton className="h-10" /><Skeleton className="h-10" /></div>
          ) : (
            <ul className="mt-3 space-y-1">
              {userRows.map((u) => (
                <li key={u.id}>
                  <button
                    type="button"
                    onClick={() => setSelectedId(u.id)}
                    className={"flex w-full items-center justify-between rounded-md border p-3 text-left hover:bg-ink-700 " + (selectedId === u.id ? "border-brass-500" : "border-line")}
                  >
                    <span>
                      <span className="block text-sm font-medium">{u.fullName}</span>
                      <span className="mono muted">{u.email}</span>
                    </span>
                    <Badge tone={u.role === "ADMIN" ? "info" : "neutral"}>{u.role}</Badge>
                  </button>
                </li>
              ))}
            </ul>
          )}
          {selected && (
            <div className="mt-4 border-t border-line pt-3">
              <CardTitle>Accounts · {selected.fullName}</CardTitle>
              {accountRows.length === 0 && <CardDescription>No accounts.</CardDescription>}
              {accountRows.map((a) => (
                <div key={a.id} className="mt-2 flex items-center justify-between rounded-md border border-line p-3">
                  <div>
                    <p className="mono text-sm">{a.iban}</p>
                    <p className="text-sm">{usd(a.balance)} · <Badge tone={a.status === "ACTIVE" ? "success" : "danger"}>{a.status}</Badge></p>
                  </div>
                  <div className="flex gap-2">
                  <Button size="sm" variant="secondary" onClick={() => downloadAccountPdf(a.id)}>PDF</Button>
                  {a.status === "ACTIVE" ? (
                    <Button
                      size="sm"
                      variant="danger"
                      disabled={setStatus.isPending}
                      onClick={() => setStatus.mutate({ account: a, frozen: true, userId: selected.id })}
                    >
                      Freeze
                    </Button>
                  ) : (
                    <Button
                      size="sm"
                      variant="secondary"
                      disabled={setStatus.isPending}
                      onClick={() => setStatus.mutate({ account: a, frozen: false, userId: selected.id })}
                    >
                      Unfreeze
                    </Button>
                  )}
                  </div>
                </div>
              ))}
            </div>
          )}
        </Card>

        <div className="space-y-4">
          <Card>
            <CardTitle>Latest transfers</CardTitle>
            {recentRows.length === 0 ? (
              <CardDescription>No transfers yet.</CardDescription>
            ) : (
              <div className="mt-3">
                <Table>
                  <THead><TRow><TH>When</TH><TH>Route</TH><TH className="text-right">Amount</TH></TRow></THead>
                  <tbody>
                    {recentRows.map((t) => (
                      <TRow key={t.id}>
                        <TD className="whitespace-nowrap">{fmtDate(t.createdAt)}</TD>
                        <TD className="mono">{t.fromIban ? "..." + t.fromIban.slice(-6) : "DEP"} → {t.toIban ? "..." + t.toIban.slice(-6) : "-"}</TD>
                        <TD className="text-right font-semibold tabular-nums">{usd(t.amount)}</TD>
                      </TRow>
                    ))}
                  </tbody>
                </Table>
              </div>
            )}
          </Card>

          <Card>
            <div className="flex items-center justify-between gap-2">
              <CardTitle>Audit log</CardTitle>
              <form onSubmit={(e) => e.preventDefault()} className="flex gap-2">
                <Field label="">
                  <Input aria-label="Filter by action" placeholder="TRANSFER_POSTED..." value={auditAction} onChange={(e) => setAuditAction(e.target.value)} />
                </Field>
                <Button type="submit" variant="secondary" size="sm" onClick={() => setAuditAction((v) => v)}>Filter</Button>
              </form>
            </div>
            {auditRows.length === 0 ? (
              <CardDescription>No audit rows.</CardDescription>
            ) : (
              <ul className="mt-3 space-y-1.5 text-sm">
                {auditRows.map((a) => (
                  <li key={a.id} className="flex items-center justify-between gap-2 rounded-md border border-line px-3 py-2">
                    <span><Badge tone="neutral">{a.action}</Badge> <span className="muted">{a.entity} </span><span className="mono">{a.entityId.slice(0, 8)}...</span></span>
                    <span className="muted whitespace-nowrap text-xs">{fmtDate(a.createdAt)}</span>
                  </li>
                ))}
              </ul>
            )}
          </Card>
        </div>
      </div>
    </AppShell>
  );
}
