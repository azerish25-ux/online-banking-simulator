"use client";

import { ArrowRight } from "lucide-react";
import * as React from "react";
import Link from "next/link";
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
import { Routes } from "../../lib/routes";
import {
  useAccounts,
  useDeposit,
  useMe,
  useOpenAccount,
  useSummary,
  useTransactions
} from "../../lib/queries";
import { fmtDate, usd } from "../../lib/format";

export default function DashboardPage() {
  const { push } = useToast();
  const me = useMe();
  const accounts = useAccounts();

  // Primary account drives the feed + chart; queries stay independent so the
  // chart never waits on the table (and vice versa).
  const primaryId = accounts.data?.[0]?.id ?? "";
  const recent = useTransactions(primaryId, 0, 5);
  const summary = useSummary(primaryId, 6);

  const deposit = useDeposit();
  const openAccount = useOpenAccount();

  const [depositOpen, setDepositOpen] = React.useState(false);
  const [openOpen, setOpenOpen] = React.useState(false);
  const [newType, setNewType] = React.useState("SAVINGS");
  const [depositAmount, setDepositAmount] = React.useState("100.00");

  // Errors surface through toasts once per failed mutation, not on every render.
  React.useEffect(() => {
    if (deposit.isError) push(deposit.error.message, "error");
    if (openAccount.isError) push(openAccount.error.message, "error");
  }, [deposit.isError, deposit.error, openAccount.isError, openAccount.error, push]);

  React.useEffect(() => {
    if (deposit.isSuccess) {
      push("Deposited " + usd(depositAmount) + " (simulated rail).", "success");
      setDepositOpen(false);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [deposit.isSuccess]);

  React.useEffect(() => {
    if (openAccount.isSuccess) {
      push("Account opened.", "success");
      setOpenOpen(false);
    }
  }, [openAccount.isSuccess, push]);

  const user = me.data;
  const accs = accounts.data;
  const primaryAccount = accs?.[0];

  async function submitDeposit() {
    if (!primaryAccount) return;
    deposit.mutate({ accountId: primaryAccount.id, amount: depositAmount });
  }

  async function submitOpenAccount() {
    openAccount.mutate(newType);
  }

  // Total is computed on the decimal strings via usd() - no float slips in.
  const totalCents = (accs ?? [])
    .map((a) => usd(a.balance).replace(/[$,]/g, ""))
    .reduce<number | null>((sum, n) => (sum === null ? parseFloat(n) : sum + parseFloat(n)), null);

  return (
    <AppShell>
      <div className="mb-5 flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="text-2xl font-bold tracking-tight">Overview</h1>
          <p className="muted text-sm">{user ? "Welcome back, " + user.fullName + "." : "Loading..."}</p>
        </div>
        <div className="flex gap-2">
          <Button variant="secondary" onClick={() => setOpenOpen(true)}>Open account</Button>
          <Button variant="secondary" onClick={() => setDepositOpen(true)} disabled={!accs?.length}>
            Simulate deposit
          </Button>
          <Link href={Routes.transfers}>
            <Button>Send money</Button>
          </Link>
        </div>
      </div>

      {accounts.isLoading || accs == null ? (
        <div className="grid gap-4 md:grid-cols-3">
          <Skeleton className="h-28" /><Skeleton className="h-28" /><Skeleton className="h-28" />
        </div>
      ) : (
        <div className="grid gap-4 md:grid-cols-3">
          <Card>
            <CardDescription>Total balance</CardDescription>
            <p className="mt-1 text-3xl font-bold tabular-nums">{usd(totalCents ?? 0)}</p>
          </Card>
          {accs.map((a) => (
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
          <Link href={Routes.activity} className="text-sm text-brass-300 hover:underline">Full activity</Link>
        </div>
        {summary.data == null ? (
          <Skeleton className="h-48" />
        ) : (
          <SpendingChart data={summary.data} />
        )}
      </Card>

      <Card className="mt-4">
        <div className="mb-3 flex items-center justify-between">
          <CardTitle>Recent activity</CardTitle>
          <Link href={Routes.transfers} className="text-sm text-brass-300 hover:underline">
            New transfer
          </Link>
        </div>
        {(recent.data?.content ?? []).length === 0 ? (
          <EmptyState title="No transactions yet" description="Send your first transfer to see it here." />
        ) : (
          <Table>
            <THead>
              <TRow>
                <TH>When</TH><TH>From</TH><TH>To</TH><TH className="text-right">Amount</TH><TH>Status</TH>
              </TRow>
            </THead>
            <tbody>
              {(recent.data?.content ?? []).map((t) => (
                <TRow key={t.id}>
                  <TD className="whitespace-nowrap">{fmtDate(t.createdAt)}</TD>
                  <TD className="mono">{t.fromIban ? "..." + t.fromIban.slice(-6) : "DEPOSIT"}</TD>
                  <TD className="mono">{t.toIban ? "..." + t.toIban.slice(-6) : "-"}</TD>
                  <TD className="max-w-40 truncate">{t.memo ?? "-"}</TD>
                  <TD className="text-right font-semibold tabular-nums">{usd(t.amount)}</TD>
                  <TD>{t.flagged ? <Badge tone="warning">FLAGGED</Badge> : <Badge tone="success">POSTED</Badge>}</TD>
                </TRow>
              ))}
            </tbody>
          </Table>
        )}
      </Card>

      <Modal open={openOpen} onClose={() => setOpenOpen(false)} title="Open account">
        <Field label="Account type">
          <select aria-label="Account type" value={newType} onChange={(e) => setNewType(e.target.value)} className="h-10 w-full rounded-md border border-line bg-ink-950/70 px-3 text-sm focus:border-brass-500 focus:outline-none">
            <option value="CHECKING">Checking - everyday money</option>
            <option value="SAVINGS">Savings - earns monthly interest</option>
            <option value="LOAN">Loan - borrow up to $1,000</option>
          </select>
        </Field>
        <div className="mt-4 flex justify-end gap-2">
          <Button variant="secondary" onClick={() => setOpenOpen(false)}>Cancel</Button>
          <Button onClick={submitOpenAccount} disabled={openAccount.isPending}>
            {openAccount.isPending ? "Opening..." : "Open"}
          </Button>
        </div>
      </Modal>

      <Modal open={depositOpen} onClose={() => setDepositOpen(false)} title="Simulate deposit">
        <Field label="Amount (USD)" hint="Demo rail: funds appear instantly.">
          <Input value={depositAmount} onChange={(e) => setDepositAmount(e.target.value)} inputMode="decimal" />
        </Field>
        <div className="mt-4 flex justify-end gap-2">
          <Button variant="secondary" onClick={() => setDepositOpen(false)}>Cancel</Button>
          <Button onClick={submitDeposit} disabled={deposit.isPending}>
            {deposit.isPending ? "Depositing..." : "Deposit"}
          </Button>
        </div>
      </Modal>
    </AppShell>
  );
}
