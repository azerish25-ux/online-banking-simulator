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
import { api } from "../../lib/api";
import { fmtDate, usd } from "../../lib/format";

type AdminUser = { id: string; email: string; fullName: string; role: string };
type Account = { id: string; iban: string; type: string; balance: string; status: string };
type Tx = { id: string; fromIban: string | null; toIban: string | null; amount: string; currency: string; memo: string | null; createdAt: string };
type Audit = { id: number; actorId: string | null; action: string; entity: string; entityId: string; createdAt: string };

export default function AdminPage() {
  const { push } = useToast();
  const [query, setQuery] = React.useState("");
  const [users, setUsers] = React.useState<AdminUser[] | null>(null);
  const [selected, setSelected] = React.useState<AdminUser | null>(null);
  const [accounts, setAccounts] = React.useState<Account[]>([]);
  const [recent, setRecent] = React.useState<Tx[]>([]);
  const [audits, setAudits] = React.useState<Audit[]>([]);
  const [auditAction, setAuditAction] = React.useState("");
  const [forbidden, setForbidden] = React.useState(false);

  const loadUsers = React.useCallback(async (q: string) => {
    try {
      const page = await api("/v1/admin/users?q=" + encodeURIComponent(q) + "&size=20");
      setUsers(page.content ?? []);
    } catch (e) {
      if (e instanceof Error && e.message.includes("403")) setForbidden(true);
      push(e instanceof Error ? e.message : "Failed to load users", "error");
    }
  }, [push]);

  const loadSide = React.useCallback(async () => {
    try {
      const txPage = await api("/v1/admin/transactions?size=8");
      setRecent(txPage.content ?? []);
      const auditPage = await api("/v1/admin/audit-logs?size=15" + (auditAction ? "&action=" + encodeURIComponent(auditAction) : ""));
      setAudits(auditPage.content ?? []);
    } catch (e) {
      push(e instanceof Error ? e.message : "Failed to load ops data", "error");
    }
  }, [auditAction, push]);

  React.useEffect(() => { loadUsers(""); loadSide(); }, [loadUsers, loadSide]);

  async function search(e: React.FormEvent) {
    e.preventDefault();
    await loadUsers(query);
  }

  async function select(user: AdminUser) {
    setSelected(user);
    try {
      setAccounts(await api("/v1/admin/users/" + user.id + "/accounts"));
    } catch (e) {
      push(e instanceof Error ? e.message : "Failed to load accounts", "error");
    }
  }

  async function setStatus(account: Account, frozen: boolean) {
    try {
      const updated: Account = await api(
        "/v1/admin/accounts/" + account.id + (frozen ? "/freeze" : "/unfreeze"),
        { method: "POST" }
      );
      setAccounts((prev) => prev.map((a) => (a.id === updated.id ? updated : a)));
      push(frozen ? "Account frozen." : "Account re-activated.", "success");
      await loadSide();
    } catch (e) {
      push(e instanceof Error ? e.message : "Status change failed", "error");
    }
  }

  if (forbidden) {
    return (
      <AppShell>
        <EmptyState title="Admins only" description="Your account does not have the operator role." />
      </AppShell>
    );
  }

  return (
    <AppShell>
      <h1 className="text-2xl font-bold tracking-tight">Operations</h1>
      <p className="muted mt-1 text-sm">Users, account status, money flow and the audit trail.</p>

      <div className="mt-4 grid gap-4 lg:grid-cols-2">
        <Card>
          <CardTitle>Users</CardTitle>
          <form onSubmit={search} className="mt-3 flex gap-2">
            <Input aria-label="Search users" placeholder="email or name..." value={query} onChange={(e) => setQuery(e.target.value)} />
            <Button type="submit" variant="secondary">Search</Button>
          </form>
          {users == null ? (
            <div className="mt-3 space-y-2"><Skeleton className="h-10" /><Skeleton className="h-10" /></div>
          ) : (
            <ul className="mt-3 space-y-1">
              {users.map((u) => (
                <li key={u.id}>
                  <button
                    type="button"
                    onClick={() => select(u)}
                    className={"flex w-full items-center justify-between rounded-lg border p-3 text-left hover:bg-ink-700 " + (selected?.id === u.id ? "border-brand-500" : "border-line")}
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
              {accounts.length === 0 && <CardDescription>No accounts.</CardDescription>}
              {accounts.map((a) => (
                <div key={a.id} className="mt-2 flex items-center justify-between rounded-lg border border-line p-3">
                  <div>
                    <p className="mono text-sm">{a.iban}</p>
                    <p className="text-sm">{usd(a.balance)} · <Badge tone={a.status === "ACTIVE" ? "success" : "danger"}>{a.status}</Badge></p>
                  </div>
                  {a.status === "ACTIVE" ? (
                    <Button size="sm" variant="danger" onClick={() => setStatus(a, true)}>Freeze</Button>
                  ) : (
                    <Button size="sm" variant="secondary" onClick={() => setStatus(a, false)}>Unfreeze</Button>
                  )}
                </div>
              ))}
            </div>
          )}
        </Card>

        <div className="space-y-4">
          <Card>
            <CardTitle>Latest transfers</CardTitle>
            {recent.length === 0 ? (
              <CardDescription>No transfers yet.</CardDescription>
            ) : (
              <div className="mt-3">
                <Table>
                  <THead><TRow><TH>When</TH><TH>Route</TH><TH className="text-right">Amount</TH></TRow></THead>
                  <tbody>
                    {recent.map((t) => (
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
              <form onSubmit={(e) => { e.preventDefault(); loadSide(); }} className="flex gap-2">
                <Field label="">
                  <Input aria-label="Filter by action" placeholder="TRANSFER_POSTED..." value={auditAction} onChange={(e) => setAuditAction(e.target.value)} />
                </Field>
                <Button type="submit" variant="secondary" size="sm">Filter</Button>
              </form>
            </div>
            {audits.length === 0 ? (
              <CardDescription>No audit rows.</CardDescription>
            ) : (
              <ul className="mt-3 space-y-1.5 text-sm">
                {audits.map((a) => (
                  <li key={a.id} className="flex items-center justify-between gap-2 rounded-lg border border-line px-3 py-2">
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
