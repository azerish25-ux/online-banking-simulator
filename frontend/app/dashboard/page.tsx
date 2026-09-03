"use client";

import { ArrowRight } from "lucide-react";
import * as React from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { AppShell } from "../../components/layout/app-shell";
import { Badge } from "../../components/ui/badge";
import { Button } from "../../components/ui/button";
import { Card, CardDescription, CardTitle } from "../../components/ui/card";
import { EmptyState } from "../../components/ui/empty-state";
import { Field, Input } from "../../components/ui/input";
import { Modal } from "../../components/ui/modal";
import { Skeleton } from "../../components/ui/skeleton";
import { TD, TH, THead, TRow, Table } from "../../components/ui/table";
import { useToast } from "../../components/feedback/toast";
import { SpendingChart } from "../../components/charts/spending-chart";
import { ApiError, api, type User } from "../../lib/api";
import { Routes } from "../../lib/routes";
import type { Account, MonthPoint, Tx } from "../../lib/api-types";
import { fmtDate, usd } from "../../lib/format";


export default function DashboardPage() {
  const { push } = useToast();
  const router = useRouter();
  const [user, setUser] = React.useState<User | null>(null);
  const [accounts, setAccounts] = React.useState<Account[] | null>(null);
  const [recent, setRecent] = React.useState<Tx[]>([]);
  const [summary, setSummary] = React.useState<MonthPoint[] | null>(null);
  const [depositOpen, setDepositOpen] = React.useState(false);
  const [openOpen, setOpenOpen] = React.useState(false);
  const [newType, setNewType] = React.useState("SAVINGS");
  const [creating, setCreating] = React.useState(false);
  const [depositAmount, setDepositAmount] = React.useState("100.00");
  const [depositing, setDepositing] = React.useState(false);

  const load = React.useCallback(async () => {
    const me = await api("/v1/auth/me");
    setUser(me);
    const accs: Account[] = await api("/v1/accounts");
    setAccounts(accs);
    if (accs.length > 0) {
      const page = await api("/v1/transactions?accountId=" + accs[0].id + "&size=5");
      setRecent(page.content ?? []);
        setSummary(await api("/v1/accounts/" + accs[0].id + "/summary?months=6"));
    }
  }, []);

  React.useEffect(() => {
    load().catch((e) => {
      if (e instanceof ApiError && e.status === 401) { router.push(Routes.login); return; }
      push(e instanceof Error ? e.message : "Failed to load dashboard", "error");
    });
  }, [load, push]);

  async function deposit() {
    if (accounts == null || accounts.length === 0) return;
    setDepositing(true);
    try {
      await api("/v1/accounts/" + accounts[0].id + "/deposit", {
        method: "POST",
        body: JSON.stringify({ amount: depositAmount })
      });
      push("Deposited " + usd(depositAmount) + " (simulated rail).", "success");
      setDepositOpen(false);
      await load();
    } catch (e) {
      push(e instanceof Error ? e.message : "Deposit failed", "error");
    } finally {
      setDepositing(false);
    }
  }

  async function openAccount() {
    setCreating(true);
    try {
      await api("/v1/accounts", { method: "POST", body: JSON.stringify({ type: newType }) });
      push("Account opened.", "success");
      setOpenOpen(false);
      await load();
    } catch (e) {
      push(e instanceof Error ? e.message : "Could not open account", "error");
    } finally {
      setCreating(false);
    }
  }

  const total = (accounts ?? []).reduce((sum, a) => sum + parseFloat(a.balance), 0);

  return (
    <AppShell>
      <div className="mb-5 flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="text-2xl font-bold tracking-tight">Overview</h1>
          <p className="muted text-sm">{user ? "Welcome back, " + user.fullName + "." : "Loading..."}</p>
        </div>
        <div className="flex gap-2">
          <Button variant="secondary" onClick={() => setOpenOpen(true)}>Open account</Button>
          <Button variant="secondary" onClick={() => setDepositOpen(true)} disabled={!accounts?.length}>
            Simulate deposit
          </Button>
          <Link href={Routes.transfers}>
            <Button>Send money</Button>
          </Link>
        </div>
      </div>

      {accounts == null ? (
        <div className="grid gap-4 md:grid-cols-3">
          <Skeleton className="h-28" /><Skeleton className="h-28" /><Skeleton className="h-28" />
        </div>
      ) : (
        <div className="grid gap-4 md:grid-cols-3">
          <Card>
            <CardDescription>Total balance</CardDescription>
            <p className="mt-1 text-3xl font-bold tabular-nums">{usd(total)}</p>
          </Card>
          {accounts.map((a) => (
            <Card key={a.id}>
              <div className="flex items-center justify-between">
                <Link href={Routes.account(a.id)}><CardTitle className="hover:underline">{a.type} <ArrowRight size={14} aria-hidden="true" className="inline" /></CardTitle></Link>
                <Badge tone={a.status === "ACTIVE" ? "success" : "neutral"}>{a.status}</Badge>
              </div>
              <p className="mt-1 text-2xl font-semibold tabular-nums">{usd(a.balance)}</p>
              <p className="mono muted mt-2">{a.iban}</p>
            </Card>
          ))}
        </div>
      )}

      <Card className="mt-4">
        <div className="mb-3 flex items-center justify-between">
          <CardTitle>Money flow · last 6 months</CardTitle>
          <Link href={Routes.activity} className="text-sm text-brand-300 hover:underline">Full activity</Link>
        </div>
        {summary == null ? (
          <Skeleton className="h-48" />
        ) : (
          <SpendingChart data={summary} />
        )}
      </Card>

      <Card className="mt-4">
        <div className="mb-3 flex items-center justify-between">
          <CardTitle>Recent activity</CardTitle>
          <Link href={Routes.transfers} className="text-sm text-brand-300 hover:underline">
            New transfer
          </Link>
        </div>
        {recent.length === 0 ? (
          <EmptyState title="No transactions yet" description="Send your first transfer to see it here." />
        ) : (
          <Table>
            <THead>
              <TRow>
                <TH>When</TH><TH>From</TH><TH>To</TH><TH>Memo</TH><TH className="text-right">Amount</TH>
              </TRow>
            </THead>
            <tbody>
              {recent.map((t) => (
                <TRow key={t.id}>
                  <TD className="whitespace-nowrap">{fmtDate(t.createdAt)}</TD>
                  <TD className="mono">{t.fromIban ? "..." + t.fromIban.slice(-6) : "DEPOSIT"}</TD>
                  <TD className="mono">{t.toIban ? "..." + t.toIban.slice(-6) : "-"}</TD>
                  <TD className="max-w-40 truncate">{t.memo ?? "-"}</TD>
                  <TD className="text-right font-semibold tabular-nums">{usd(t.amount)}</TD>
                </TRow>
              ))}
            </tbody>
          </Table>
        )}
      </Card>

      <Modal open={openOpen} onClose={() => setOpenOpen(false)} title="Open account">
        <Field label="Account type">
          <select aria-label="Account type" value={newType} onChange={(e) => setNewType(e.target.value)} className="h-10 w-full rounded-lg border border-line bg-ink-950 px-3 text-sm">
            <option value="CHECKING">Checking - everyday money</option>
            <option value="SAVINGS">Savings - earns monthly interest</option>
            <option value="LOAN">Loan - borrow up to $1,000</option>
          </select>
        </Field>
        <div className="mt-4 flex justify-end gap-2">
          <Button variant="secondary" onClick={() => setOpenOpen(false)}>Cancel</Button>
          <Button onClick={openAccount} disabled={creating}>{creating ? "Opening..." : "Open"}</Button>
        </div>
      </Modal>

      <Modal open={depositOpen} onClose={() => setDepositOpen(false)} title="Simulate deposit">
        <Field label="Amount (USD)" hint="Demo rail: funds appear instantly.">
          <Input value={depositAmount} onChange={(e) => setDepositAmount(e.target.value)} inputMode="decimal" />
        </Field>
        <div className="mt-4 flex justify-end gap-2">
          <Button variant="secondary" onClick={() => setDepositOpen(false)}>Cancel</Button>
          <Button onClick={deposit} disabled={depositing}>{depositing ? "Depositing..." : "Deposit"}</Button>
        </div>
      </Modal>
    </AppShell>
  );
}
