"use client";

import { ArrowLeft, Copy, Check } from "lucide-react";
import * as React from "react";
import Link from "next/link";
import { AppShell } from "../../../components/layout/app-shell";
import { Badge } from "../../../components/ui/badge";
import { Button } from "../../../components/ui/button";
import { Card, CardBody, CardHead, CardTitle } from "../../../components/ui/card";
import { ConfirmDialog } from "../../../components/ui/confirm-dialog";
import { EmptyState } from "../../../components/ui/empty-state";
import { LoadFailed } from "../../../components/ui/load-failed";
import { Skeleton } from "../../../components/ui/skeleton";
import { TD, TH, THead, TRow, Table } from "../../../components/ui/table";
import { TxStatusBadge } from "../../../components/ui/tx-status-badge";
import { TxWhen } from "../../../components/ui/tx-when";
import type { CardItem, IssuedCard } from "../../../lib/api-types";
import { useResultToast } from "../../../components/feedback/use-result-toast";
import { useToast } from "../../../components/feedback/toast";
import { useAccount, useCards, useIssueCard, useSetCardStatus, useTransactions } from "../../../lib/queries";
import { signedUsd, usd, usdReview } from "../../../lib/format";
import { Routes } from "../../../lib/routes";
import { cn } from "../../../lib/cn";

/** "CHECKING" → "Checking", "LOAN" → "Loan" - the account's readable name. */
function typeName(type: string): string {
  return type.charAt(0) + type.slice(1).toLowerCase();
}



/** One labelled loan figure - authoritative, policy-derived (never a
 *  client-side balance subtraction). */
function LoanFigure({ label, value, danger = false }: { label: string; value: string; danger?: boolean }) {
  return (
    <div>
      <dt className="muted text-sm">{label}</dt>
      <dd className={cn("nums mt-1 text-2xl font-semibold", danger && "text-danger")}>{value}</dd>
    </div>
  );
}

export function AccountDetailPageContent({ id }: { id: string }) {
  const { push } = useToast();
  const account = useAccount(id);
  // 404/410 mean the account truly does not exist or was closed; any other
  // failure (offline, 429, 500) is a load problem with a retry - it must NOT
  // masquerade as "Account not found".
  const accountNotFound = account.isError && (account.error?.status === 404 || account.error?.status === 410);
  const recent = useTransactions(id, "", 8);
  const isLoan = account.data?.type === "LOAN";
  const frozen = account.data?.status === "FROZEN";
  // LOAN accounts can never hold cards; the query stays idle for them.
  const cards = useCards(isLoan ? "" : id);
  const issue = useIssueCard();
  const setCardStatus = useSetCardStatus();
  const [issued, setIssued] = React.useState<IssuedCard | null>(null);
  const [freezeCandidate, setFreezeCandidate] = React.useState<CardItem | null>(null);
  const [copied, setCopied] = React.useState(false);
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
      toast: { message: "Virtual card issued. Copy it now. It will not be shown again." },
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

  async function copyIban() {
    if (!account.data) return;
    try {
      await navigator.clipboard.writeText(account.data.iban);
      setCopied(true);
      window.setTimeout(() => setCopied(false), 1500);
    } catch {
      push("Copy failed - select the identifier and copy it manually.", "error");
    }
  }

  return (
    <AppShell>
      <p className="text-sm"><Link href={Routes.dashboard} className="text-action hover:underline"><ArrowLeft size={14} aria-hidden="true" /> Overview</Link></p>
      {accountNotFound ? (
        <div className="mt-3">
          <EmptyState
            title="Account not found"
            description="This link looks wrong, or the account is no longer available. Head back to your overview and pick an account from there."
          />
        </div>
      ) : account.isError ? (
        <div className="mt-3">
          <LoadFailed
            title="Couldn't load this account"
            description="The request did not go through. Try again in a moment."
            onRetry={() => account.refetch()}
          />
        </div>
      ) : account.data == null ? (
        <div className="mt-3 space-y-2"><Skeleton className="h-24" /><Skeleton className="h-40" /></div>
      ) : (
        <>
          <div className="mt-2">
            <div className="flex flex-wrap items-center gap-3">
            <h1 className="text-xl leading-7">
              {typeName(account.data.type)}
            </h1>
              {frozen ? <Badge tone="warning">Frozen</Badge> : <span className="muted text-sm">Active</span>}
            </div>
            {/* The identifier sits under the recognizable title with a copy
                control - never the page's giant heading. */}
            <div className="mt-1.5 flex flex-wrap items-center gap-2">
              <p className="mono muted break-all text-sm">{account.data.iban}</p>
              <Button type="button" variant="ghost" size="sm" onClick={() => void copyIban()}>
                {copied ? <Check size={14} aria-hidden="true" /> : <Copy size={14} aria-hidden="true" />}
                {copied ? "Copied" : "Copy"}
              </Button>
            </div>
          </div>

          {/* Labelled financial figures from the authoritative account
              response. A loan shows its policy-derived split (principal,
              interest, total owed, available credit); deposits show the
              balance with an honest state line. */}
          <Card className="mt-4">
            {isLoan ? (
              <>
                <CardHead>
                  <CardTitle>Loan position</CardTitle>
                  <p className="muted text-sm">Principal and interest kept separate by the loan policy</p>
                </CardHead>
                <dl className="grid gap-x-8 gap-y-4 px-4 py-3.5 sm:grid-cols-2 lg:grid-cols-4">
                  <LoanFigure label="Total owed" value={usdReview(account.data.totalOwed ?? "0.0000")} danger />
                  <LoanFigure label="Principal owed" value={usdReview(account.data.principalOwed ?? "0.0000")} />
                  <LoanFigure label="Interest owed" value={usdReview(account.data.interestOwed ?? "0.0000")} />
                  <LoanFigure label="Available credit" value={usdReview(account.data.availableCredit ?? "0.0000")} />
                </dl>
                <p className="muted border-t border-divider px-4 py-2.5 text-xs">
                  Simulator loan, USD. Borrow by sending money from this account to another of yours;
                  repay by sending money back to this account. Interest is posted monthly on each
                  day&apos;s outstanding principal. {frozen ? "This loan is frozen - repayments and new draws are disabled." : ""}
                </p>
              </>
            ) : (
              <>
                <CardHead>
                  <CardTitle>Balance</CardTitle>
                  <p className="muted text-sm">
                    {account.data.type === "SAVINGS" ? "Interest posted monthly" : "Simulated funds, USD"}
                  </p>
                </CardHead>
                <div className="well m-4 border-x-0 border-b-0 px-4 py-2.5">
                  <p className="nums text-[28px] leading-9 font-semibold">{usd(account.data.balance)}</p>
                </div>
                <p className="muted px-4 pb-3 text-xs">
                  {frozen ? "This account is frozen - money cannot be sent from it until an operator unfreezes it." : "Available to spend."}
                </p>
              </>
            )}
          </Card>

          <div className="mt-4 grid gap-4 lg:grid-cols-3">
            {/* Activity is primary content for an account. */}
            <Card className="lg:col-span-2">
              <CardHead>
                <CardTitle>Recent activity</CardTitle>
              </CardHead>
              <p className="muted border-b border-divider px-4 py-2 text-xs">
                Posted rows show their posting date; a row awaiting review shows when it was requested.
              </p>
              {recent.isLoading && recent.data == null ? (
                <div className="space-y-2 px-4 py-3"><Skeleton className="h-9" /><Skeleton className="h-9" /><Skeleton className="h-9" /></div>
              ) : recent.isError && recent.data == null ? (
                <div className="px-4 py-3">
                  <LoadFailed
                    title="Couldn't load recent activity"
                    onRetry={() => recent.refetch()}
                  />
                </div>
              ) : (recent.data?.items ?? []).length === 0 ? (
                <p className="muted px-4 py-3 text-sm">No transactions yet.</p>
              ) : (
                <Table>
                  <THead><TRow><TH>When</TH><TH>Memo</TH><TH>Status</TH><TH className="text-right">Amount</TH></TRow></THead>
                  <tbody>
                    {(recent.data?.items ?? []).map((t) => (
                      <TRow key={t.id}>
                        <TD><TxWhen tx={t} /></TD>
                        <TD className="max-w-40 truncate">{t.memo ?? (t.fromIban ? "Transfer" : "Deposit")}</TD>
                        <TD><TxStatusBadge status={t.status} /></TD>
                        <TD className="nums text-right font-semibold">{signedUsd(t.amount, t.fromIban, t.toIban, account.data.iban)}</TD>
                      </TRow>
                    ))}
                  </tbody>
                </Table>
              )}
              <p className="border-t border-divider px-4 py-2.5 text-sm">
                <Link href={Routes.activity + "?account=" + encodeURIComponent(id)} className="text-action hover:underline">
                  Full history and statement export (CSV / PDF)
                </Link>
              </p>
            </Card>

            {/* Virtual cards stay available but sit beside, never ahead of,
                the account's financial record. */}
            {!isLoan && (
              <Card>
                <CardHead>
                  <CardTitle>Virtual cards</CardTitle>
                  <Button size="sm" onClick={() => issue.mutate(id)} disabled={issue.isPending}>
                    {issue.isPending ? "Issuing..." : "Issue card"}
                  </Button>
                </CardHead>
                <CardBody className="space-y-3">
                {issued && (
                  <div className="border border-success-border bg-success-surface p-4" role="status">
                    <p className="text-sm font-medium text-success">Copy now. This number is shown only once.</p>
                    <p className="mono mt-2 text-xl tracking-widest">{issued.pan}</p>
                    <p className="mono muted text-sm">CVV {issued.cvv} · Exp {issued.expMonth}/{issued.expYear}</p>
                  </div>
                )}
                {cards.isError && cards.data == null ? (
                  <LoadFailed
                    title="Couldn't load cards"
                    onRetry={() => cards.refetch()}
                  />
                ) : cards.data == null ? (
                  <div className="space-y-2"><Skeleton className="h-16" /><Skeleton className="h-16" /></div>
                ) : (cards.data ?? []).length === 0 ? (
                  <p className="muted text-sm">No cards on this account yet.</p>
                ) : (
                  <ul className="divide-y divide-divider">
                    {(cards.data ?? []).map((c) => (
                      <li key={c.id} className="flex items-center justify-between py-2.5 first:pt-0 last:pb-0">
                        <div>
                          <p className="mono">•••• •••• •••• {c.last4}</p>
                          <p className="muted mt-0.5 text-xs">Exp {c.expMonth}/{c.expYear} · {c.status === "ACTIVE" ? <span>Active</span> : <Badge tone="danger">Frozen</Badge>}</p>
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
                </CardBody>
              </Card>
            )}
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
              unfreeze it. You can unfreeze it at any time. This is not permanent.
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


export default function AccountDetailPage({ params: paramsPromise }: { params: Promise<{ id: string }> }) {
  // Next 15+ pages receive `params` as a Promise - unwrap it, then hand the
  // id to the content component (kept separate so tests can render it
  // directly without a Suspense boundary).
  const { id } = React.use(paramsPromise);
  return <AccountDetailPageContent id={id} />;
}
