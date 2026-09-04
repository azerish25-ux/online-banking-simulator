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
import { TxStatusBadge } from "../../components/ui/tx-status-badge";
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
import { fmtDate, signedUsd, usd, usdFromCents, decimalToCents } from "../../lib/format";
import { depositSchema } from "../../lib/validation";

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
  const [depositError, setDepositError] = React.useState<string | undefined>(undefined);

  // Errors surface through toasts once per failed mutation, not on every render.
  React.useEffect(() => {
    if (deposit.isError) push(deposit.error.message, "error");
    if (openAccount.isError) push(openAccount.error.message, "error");
  }, [deposit.isError, deposit.error, openAccount.isError, openAccount.error, push]);

  // The amount the successful deposit actually used lives in a ref, so the
  // effect fires exactly once per success without stale-closure suppression.
  const lastDepositAmount = React.useRef("0");
  React.useEffect(() => {
    if (deposit.isSuccess) {
      push("Deposited " + usd(lastDepositAmount.current) + " (simulated rail).", "success");
      setDepositOpen(false);
      setDepositError(undefined);
    }
  }, [deposit.isSuccess, push]);

  React.useEffect(() => {
    if (openAccount.isSuccess) {
      push("Account opened.", "success");
      setOpenOpen(false);
    }
  }, [openAccount.isSuccess, push]);

  const user = me.data;
  const accs = accounts.data;
  const primaryAccount = accs?.[0];

  function submitDeposit() {
    if (!primaryAccount) return;
    const parsed = depositSchema.safeParse({ amount: depositAmount });
    if (!parsed.success) {
      setDepositError(parsed.error.issues[0]?.message ?? "Enter a valid amount");
      return;
    }
    setDepositError(undefined);
    lastDepositAmount.current = depositAmount;
    deposit.mutate({ accountId: primaryAccount.id, amount: depositAmount });
  }

  function submitOpenAccount() {
    openAccount.mutate(newType);
  }

  // Exact total: integer-cents arithmetic over the server's decimal strings -
  // no float ever sums the ledger. A loan's negative balance counts as debt,
  // so the card reads as a net figure across all accounts.
  const totalCents = (accs ?? []).reduce((sum, a) => sum + decimalToCents(a.balance), 0n);
  const hasLoan = (accs ?? []).some((a) => a.type === "LOAN");

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
            <CardDescription>{hasLoan ? "Net position across accounts" : "Total across accounts"}</CardDescription>
            <p className="mt-1 text-3xl font-bold tabular-nums">{usdFromCents(totalCents)}</p>
          </Card>
          {accs.map((a) => {
            // A drawn loan (negative balance) is debt, so its card must not
            // read as a drained balance: show the magnitude in the danger tone
            // under an explicit "Outstanding loan" label. Undrawn/positive
            // loans keep the plain balance treatment.
            const isOutstandingLoan = a.type === "LOAN" && decimalToCents(a.balance) < 0n;
            return (
              <Card key={a.id}>
                <div className="flex items-center justify-between">
                  <Link href={Routes.account(a.id)}><CardTitle className="hover:underline">{a.type} <ArrowRight size={14} aria-hidden="true" className="inline" /></CardTitle></Link>
                  <Badge tone={a.status === "ACTIVE" ? "success" : "neutral"}>{a.status}</Badge>
                </div>
                {isOutstandingLoan ? (
                  <>
                    <p className="caps muted mt-2 text-xs">Outstanding loan - amount you owe</p>
                    <p className="mt-1 text-2xl font-semibold tabular-nums text-rose">{usdFromCents(-decimalToCents(a.balance))}</p>
                  </>
                ) : (
                  <>
                    <p className="mt-1 text-2xl font-semibold tabular-nums">{usd(a.balance)}</p>
                    <p className="mono muted mt-2">{a.iban}</p>
                  </>
                )}
              </Card>
            );
          })}
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
                <TH>When</TH><TH>From</TH><TH>To</TH><TH>Memo</TH><TH>Status</TH><TH className="text-right">Amount</TH>
              </TRow>
            </THead>
            <tbody>
              {(recent.data?.content ?? []).map((t) => (
                <TRow key={t.id}>
                  <TD className="whitespace-nowrap">{fmtDate(t.createdAt)}</TD>
                  <TD className="mono">{t.fromIban ? "..." + t.fromIban.slice(-6) : "DEPOSIT"}</TD>
                  <TD className="mono">{t.toIban ? "..." + t.toIban.slice(-6) : "-"}</TD>
                  <TD className="max-w-40 truncate">{t.memo ?? "-"}</TD>
                  <TD><TxStatusBadge status={t.status} /></TD>
                  <TD className="text-right font-semibold tabular-nums">{signedUsd(t.amount, t.fromIban, t.toIban, primaryAccount?.iban ?? "")}</TD>
                </TRow>
              ))}
            </tbody>
          </Table>
        )}
      </Card>

      <Modal open={openOpen} onClose={() => setOpenOpen(false)} title="Open account">
        <form onSubmit={(e) => { e.preventDefault(); submitOpenAccount(); }} className="space-y-4">
          <Field label="Account type">
            <select aria-label="Account type" value={newType} onChange={(e) => setNewType(e.target.value)} className="h-10 w-full rounded-md border border-line bg-ink-950/70 px-3 text-sm focus:border-brass-500 focus:outline-none">
              <option value="CHECKING">Checking - everyday money</option>
              <option value="SAVINGS">Savings - earns monthly interest</option>
              <option value="LOAN">Loan - borrow up to $1,000</option>
            </select>
          </Field>
          <div className="flex justify-end gap-2">
            <Button type="button" variant="secondary" onClick={() => setOpenOpen(false)}>Cancel</Button>
            <Button type="submit" disabled={openAccount.isPending}>
              {openAccount.isPending ? "Opening..." : "Open"}
            </Button>
          </div>
        </form>
      </Modal>

      <Modal open={depositOpen} onClose={() => setDepositOpen(false)} title="Simulate deposit">
        <form onSubmit={(e) => { e.preventDefault(); submitDeposit(); }} className="space-y-4">
          <Field label="Amount (USD)" hint="Demo rail: funds appear instantly." error={depositError}>
            <Input
              value={depositAmount}
              onChange={(e) => {
                setDepositAmount(e.target.value);
                setDepositError(undefined);
              }}
              inputMode="decimal"
            />
          </Field>
          <div className="flex justify-end gap-2">
            <Button type="button" variant="secondary" onClick={() => setDepositOpen(false)}>Cancel</Button>
            <Button type="submit" disabled={deposit.isPending}>
              {deposit.isPending ? "Depositing..." : "Deposit"}
            </Button>
          </div>
        </form>
      </Modal>
    </AppShell>
  );
}
