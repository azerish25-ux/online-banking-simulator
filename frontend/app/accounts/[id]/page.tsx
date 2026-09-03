"use client";

import { ArrowLeft } from "lucide-react";
import * as React from "react";
import Link from "next/link";
import { AppShell } from "../../../components/layout/app-shell";
import { Badge } from "../../../components/ui/badge";
import { Button } from "../../../components/ui/button";
import { Card, CardDescription, CardTitle } from "../../../components/ui/card";
import { EmptyState } from "../../../components/ui/empty-state";
import { Skeleton } from "../../../components/ui/skeleton";
import { TD, TH, THead, TRow, Table } from "../../../components/ui/table";
import type { IssuedCard } from "../../../lib/api-types";
import { useToast } from "../../../components/feedback/toast";
import { useAccount, useCards, useIssueCard, useSetCardStatus, useTransactions } from "../../../lib/queries";
import { fmtDate, usd } from "../../../lib/format";
import { Routes } from "../../../lib/routes";


export default function AccountDetailPage({ params }: { params: { id: string } }) {
  const { push } = useToast();
  const account = useAccount(params.id);
  const recent = useTransactions(params.id, 0, 8);
  const isLoan = account.data?.type === "LOAN";
  // LOAN accounts can never hold cards; the query stays idle for them.
  const cards = useCards(isLoan ? "" : params.id);
  const issue = useIssueCard();
  const setCardStatus = useSetCardStatus();
  const [issued, setIssued] = React.useState<IssuedCard | null>(null);

  React.useEffect(() => {
    if (issue.isSuccess && issue.data) {
      setIssued(issue.data);
      push("Virtual card issued. Copy it now - it is never shown again.", "success");
    }
  }, [issue.isSuccess, issue.data, push]);

  React.useEffect(() => {
    if (issue.isError) push(issue.error.message, "error");
    if (setCardStatus.isError) push(setCardStatus.error.message, "error");
  }, [issue.isError, issue.error, setCardStatus.isError, setCardStatus.error, push]);

  React.useEffect(() => {
    if (setCardStatus.isSuccess && setCardStatus.data) {
      push(setCardStatus.data.status === "FROZEN" ? "Card frozen." : "Card active.", "success");
    }
  }, [setCardStatus.isSuccess, setCardStatus.data, push]);

  return (
    <AppShell>
      <p className="text-sm"><Link href={Routes.dashboard} className="text-brass-300 hover:underline"><ArrowLeft size={14} aria-hidden="true" /> Overview</Link></p>
      {account.data == null ? (
        <div className="mt-3 space-y-2"><Skeleton className="h-24" /><Skeleton className="h-40" /></div>
      ) : (
        <>
          <div className="mt-2 flex flex-wrap items-center gap-3">
            <h1 className="mono text-2xl font-bold">{account.data.iban}</h1>
            <Badge tone="info">{account.data.type}</Badge>
            <Badge tone={account.data.status === "ACTIVE" ? "success" : "danger"}>{account.data.status}</Badge>
          </div>
          <p className="mt-1 text-4xl font-bold tabular-nums">{usd(account.data.balance)}</p>
          {account.data.type === "SAVINGS" && <p className="muted text-sm">Earns monthly interest, posted automatically.</p>}
          {account.data.type === "LOAN" && <p className="muted text-sm">Negative balance is what you owe. Interest accrues monthly while negative.</p>}

          <div className="mt-4 grid gap-4 lg:grid-cols-2">
            <Card>
              <div className="mb-3 flex items-center justify-between">
                <CardTitle>Virtual cards</CardTitle>
                {isLoan ? null : (
                  <Button size="sm" onClick={() => issue.mutate(params.id)} disabled={issue.isPending}>
                    {issue.isPending ? "Issuing..." : "Issue card"}
                  </Button>
                )}
              </div>
              {issued && (
                <div className="mb-3 rounded-md border border-emerald-900/60 bg-emerald-950/40 p-4" role="status">
                  <p className="text-sm font-medium text-mint">Copy now - shown only once.</p>
                  <p className="mono mt-2 text-xl tracking-widest">{issued.pan}</p>
                  <p className="mono muted text-sm">CVV {issued.cvv} · Exp {issued.expMonth}/{issued.expYear}</p>
                </div>
              )}
              {(cards.data ?? []).length === 0 ? (
                <CardDescription>No cards on this account yet.</CardDescription>
              ) : (
                <ul className="space-y-2">
                  {(cards.data ?? []).map((c) => (
                    <li key={c.id} className="flex items-center justify-between rounded-md border border-line p-3">
                      <div>
                        <p className="mono">•••• •••• •••• {c.last4}</p>
                        <p className="muted text-xs">Exp {c.expMonth}/{c.expYear} · <Badge tone={c.status === "ACTIVE" ? "success" : "danger"}>{c.status}</Badge></p>
                      </div>
                      {c.status === "ACTIVE" ? (
                        <Button size="sm" variant="secondary" onClick={() => setCardStatus.mutate({ card: c, frozen: true })}>Freeze</Button>
                      ) : (
                        <Button size="sm" variant="secondary" onClick={() => setCardStatus.mutate({ card: c, frozen: false })}>Unfreeze</Button>
                      )}
                    </li>
                  ))}
                </ul>
              )}
            </Card>

            <Card>
              <CardTitle>Recent activity</CardTitle>
              {(recent.data?.content ?? []).length === 0 ? (
                <CardDescription>No transactions yet.</CardDescription>
              ) : (
                <div className="mt-3">
                  <Table>
                    <THead><TRow><TH>When</TH><TH>Memo</TH><TH className="text-right">Amount</TH></TRow></THead>
                    <tbody>
                      {(recent.data?.content ?? []).map((t) => (
                        <TRow key={t.id}>
                          <TD className="whitespace-nowrap">{fmtDate(t.createdAt)}</TD>
                          <TD className="max-w-40 truncate">{t.memo ?? (t.fromIban ? "Transfer" : "Deposit")}</TD>
                          <TD className="text-right font-semibold tabular-nums">{usd(t.amount)}</TD>
                        </TRow>
                      ))}
                    </tbody>
                  </Table>
                </div>
              )}
            </Card>
          </div>
        </>
      )}
    </AppShell>
  );
}
