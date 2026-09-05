"use client";

import { ArrowRight } from "lucide-react";
import * as React from "react";
import Link from "next/link";
import { AppShell } from "../../components/layout/app-shell";
import { DepositDialog } from "../../components/banking/deposit-dialog";
import { OpenAccountDialog } from "../../components/banking/open-account-dialog";
import { Badge } from "../../components/ui/badge";
import { Button } from "../../components/ui/button";
import { Card, CardDescription, CardTitle } from "../../components/ui/card";
import { EmptyState } from "../../components/ui/empty-state";
import { Skeleton } from "../../components/ui/skeleton";
import { TD, TH, THead, TRow, Table } from "../../components/ui/table";
import { TxStatusBadge } from "../../components/ui/tx-status-badge";
import { SpendingChart } from "../../components/charts/spending-chart";
import { Routes } from "../../lib/routes";
import { useAccounts, useMe, useSummary, useTransactions } from "../../lib/queries";
import { decimalToCents, fmtDate, maskIban, signedUsd, usd, usdFromCents } from "../../lib/format";

export default function DashboardPage() {
  const me = useMe();
  const accounts = useAccounts();

  // Primary account drives the feed + chart; queries stay independent so the
  // chart never waits on the table (and vice versa).
  const primaryId = accounts.data?.[0]?.id ?? "";
  const recent = useTransactions(primaryId, 0, 5);
  const summary = useSummary(primaryId, 6);

  const [depositOpen, setDepositOpen] = React.useState(false);
  const [openOpen, setOpenOpen] = React.useState(false);

  // The dialogs own their forms and toasts; the page decides visibility (the
  // header buttons) and supplies what they derive from the account query.
  const accountsList = accounts.data ?? [];
  const activeAccounts = accountsList.filter((a) => a.status === "ACTIVE");

  const user = me.data;
  const accs = accounts.data;
  const primaryAccount = accs?.[0];

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
          <Button
            variant="secondary"
            onClick={() => setDepositOpen(true)}
            disabled={activeAccounts.length === 0}
          >
            Deposit funds
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
            <CardDescription>
              {hasLoan
                ? "Net position across accounts"
                : accs && accs.length > 1
                  ? "Total across accounts"
                  : "Available balance"}
            </CardDescription>
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
                    <p className="label muted mt-2 text-xs">Outstanding loan - amount you owe</p>
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
                  <TD className="mono">{maskIban(t.fromIban) ?? "DEPOSIT"}</TD>
                  <TD className="mono">{maskIban(t.toIban) ?? "-"}</TD>
                  <TD className="max-w-40 truncate">{t.memo ?? "-"}</TD>
                  <TD><TxStatusBadge status={t.status} /></TD>
                  <TD className="text-right font-semibold tabular-nums">{signedUsd(t.amount, t.fromIban, t.toIban, primaryAccount?.iban ?? "")}</TD>
                </TRow>
              ))}
            </tbody>
          </Table>
        )}
      </Card>

      <OpenAccountDialog open={openOpen} onClose={() => setOpenOpen(false)} hasLoan={hasLoan} />
      <DepositDialog open={depositOpen} onClose={() => setDepositOpen(false)} accounts={accountsList} />
    </AppShell>
  );
}
