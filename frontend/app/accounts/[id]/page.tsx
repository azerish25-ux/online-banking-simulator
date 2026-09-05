"use client";

import { ArrowLeft } from "lucide-react";
import * as React from "react";
import Link from "next/link";
import { AppShell } from "../../../components/layout/app-shell";
import { Badge } from "../../../components/ui/badge";
import { Button } from "../../../components/ui/button";
import { Card, CardDescription, CardTitle } from "../../../components/ui/card";
import { ConfirmDialog } from "../../../components/ui/confirm-dialog";
import { EmptyState } from "../../../components/ui/empty-state";
import { Skeleton } from "../../../components/ui/skeleton";
import { TD, TH, THead, TRow, Table } from "../../../components/ui/table";
import type { CardItem, IssuedCard } from "../../../lib/api-types";
import { useResultToast } from "../../../components/feedback/use-result-toast";
import { useToast } from "../../../components/feedback/toast";
import { useAccount, useCards, useIssueCard, useSetCardStatus, useTransactions } from "../../../lib/queries";
import { decimalToCents, fmtDate, signedUsd, usd, usdFromCents } from "../../../lib/format";
import { Routes } from "../../../lib/routes";

export default function AccountDetailPage({ params }: { params: { id: string } }) {
  const { push } = useToast();
  const account = useAccount(params.id);
  const recent = useTransactions(params.id, 0, 8);
  const isLoan = account.data?.type === "LOAN";
  // A drawn loan (negative balance) is debt: the hero presents it as a rose
  // "amount you owe" figure, exactly like the dashboard card.
  const isOutstandingLoan =
    account.data != null && isLoan && decimalToCents(account.data.balance) < 0n;
  // LOAN accounts can never hold cards; the query stays idle for them.
  const cards = useCards(isLoan ? "" : params.id);
  const issue = useIssueCard();
  const setCardStatus = useSetCardStatus();
  const [issued, setIssued] = React.useState<IssuedCard | null>(null);
  const [freezeCandidate, setFreezeCandidate] = React.useState<CardItem | null>(null);
  // A failed freeze leaves the confirm dialog open with its buttons re-armed:
  // the rejection renders inside that dialog, not in a corner toast behind it.
  const [freezeError, setFreezeError] = React.useState<string | null>(null);

  // Result → feedback wiring lives in the shared owner. Card-status success
  // also closes the freeze confirmation (freeze and unfreeze both settle
  // through this mutation; unfreeze never had a dialog open to close). A
  // freeze failure is a dialog rejection (inline, below); an unfreeze failure
  // is a direct page action, so it keeps its corner toast.
  useResultToast(issue, {
    success: {
      toast: { message: "Virtual card issued. Copy it now - it is never shown again." },
      run: (card) => setIssued(card)
    }
  });
  useResultToast(setCardStatus, {
    error: false,
    onFailure: (message) => {
      if (freezeCandidate) setFreezeError(message);
      else push(message, "error");
    },
    success: {
      toast: (card) => ({
        message: card.status === "FROZEN" ? "Card frozen." : "Card active."
      }),
      run: () => {
        setFreezeCandidate(null);
        setFreezeError(null);
      }
    }
  });

  return (
    <AppShell>
      <p className="text-sm"><Link href={Routes.dashboard} className="text-brass-300 hover:underline"><ArrowLeft size={14} aria-hidden="true" /> Overview</Link></p>
      {account.isError ? (
        // A bad or foreign account link must say so, not shimmer forever:
        // the API answers 404/400 and the query never resolves to data.
        <div className="mt-3">
          <EmptyState
            title="Account not found"
            description="This link looks wrong, or the account is no longer available. Head back to your overview and pick an account from there."
          />
        </div>
      ) : account.data == null ? (
        <div className="mt-3 space-y-2"><Skeleton className="h-24" /><Skeleton className="h-40" /></div>
      ) : (
        <>
          <div className="mt-2 flex flex-wrap items-center gap-3">
            <h1 className="mono text-2xl font-bold">{account.data.iban}</h1>
            <Badge tone="info">{account.data.type}</Badge>
            <Badge tone={account.data.status === "ACTIVE" ? "success" : "danger"}>{account.data.status}</Badge>
          </div>
          {isOutstandingLoan ? (
            // A drawn loan is debt - same "amount you owe" treatment as the cards.
            <>
              <p className="label muted mt-1 text-xs">Outstanding loan - amount you owe</p>
              <p className="mt-1 text-4xl font-bold tabular-nums text-rose">{usdFromCents(-decimalToCents(account.data.balance))}</p>
              <p className="muted text-sm">Repay by sending money to this account from another of yours. Interest accrues monthly on what you owe.</p>
            </>
          ) : (
            <>
              <p className="mt-1 text-4xl font-bold tabular-nums">{usd(account.data.balance)}</p>
              {account.data.type === "SAVINGS" && <p className="muted text-sm">Earns monthly interest, posted automatically.</p>}
              {account.data.type === "LOAN" && <p className="muted text-sm">Borrow up to $1,000 by sending money from this account to another of yours. Repay by sending money back here. Interest accrues monthly on what you owe.</p>}
            </>
          )}

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
                <div className="mb-3 rounded-md border border-success-border bg-success-surface p-4" role="status">
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
                        <Button size="sm" variant="secondary" onClick={() => { setFreezeError(null); setFreezeCandidate(c); }}>Freeze</Button>
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
                          <TD className="text-right font-semibold tabular-nums">{signedUsd(t.amount, t.fromIban, t.toIban, account.data.iban)}</TD>
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

      <ConfirmDialog
        open={freezeCandidate != null}
        title="Freeze this card?"
        confirmLabel="Freeze card"
        busy={setCardStatus.isPending}
        error={freezeError}
        body={
          freezeCandidate ? (
            <>
              Card <span className="mono">•••• {freezeCandidate.last4}</span> will stop working until you
              unfreeze it. You can unfreeze any time - this is not permanent.
            </>
          ) : null
        }
        onClose={() => {
          setFreezeCandidate(null);
          setFreezeError(null);
        }}
        onConfirm={() => {
          if (!freezeCandidate) return;
          setCardStatus.mutate({ card: freezeCandidate, frozen: true });
        }}
      />
    </AppShell>
  );
}
