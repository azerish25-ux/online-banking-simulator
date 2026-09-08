"use client";

import { ArrowLeft, Copy, Check } from "lucide-react";
import * as React from "react";
import Link from "next/link";
import { AppShell } from "../../../components/layout/app-shell";
import { Badge } from "../../../components/ui/badge";
import { Button } from "../../../components/ui/button";
import { Card, CardDescription, CardTitle } from "../../../components/ui/card";
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

function statusText(status: string): string {
  return status.charAt(0) + status.slice(1).toLowerCase();
}

/** One labelled loan figure - authoritative, policy-derived (never a
 *  client-side balance subtraction). */
function LoanFigure({ label, value, danger = false }: { label: string; value: string; danger?: boolean }) {
  return (
    <div>
      <dt className="muted text-sm">{label}</dt>
      <dd className={cn("mt-1 text-2xl font-semibold tabular-nums", danger && "text-danger")}>{value}</dd>
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
              <h1 className="text-[24px] leading-[30px] font-semibold tracking-tight md:text-[28px] md:leading-[34px]">
                {typeName(account.data.type)}
              </h1>
              <Badge tone={frozen ? "warning" : "neutral"}>{statusText(account.data.status)}</Badge>
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
                <CardTitle>Loan position</CardTitle>
                <CardDescription>
                  Drawn principal and unpaid interest are kept separate by the loan policy; credit
                  available is headroom on principal.
                </CardDescription>
                <dl className="mt-4 grid gap-x-8 gap-y-5 sm:grid-cols-2 lg:grid-cols-4">
                  <LoanFigure label="Total owed" value={usdReview(account.data.totalOwed ?? "0.0000")} danger />
                  <LoanFigure label="Principal owed" value={usdReview(account.data.principalOwed ?? "0.0000")} />
                  <LoanFigure label="Interest owed" value={usdReview(account.data.interestOwed ?? "0.0000")} />
                  <LoanFigure label="Available credit" value={usdReview(account.data.availableCredit ?? "0.0000")} />
                </dl>
                <p className="muted mt-4 text-xs">
                  Simulator loan, USD. Borrow by sending money from this account to another of yours;
                  repay by sending money back to this account. Interest is posted monthly on each
                  day&apos;s outstanding principal. {frozen ? "This loan is frozen - repayments and new draws are disabled." : ""}
                </p>
              </>
            ) : (
              <>
                <CardTitle>Balance</CardTitle>
                <CardDescription>
                  {account.data.type === "SAVINGS"
                    ? "Earns monthly interest, posted automatically."
                    : "Simulated funds. The balance updates when a deposit or transfer posts."}
                </CardDescription>
                <p className="mt-3 text-[32px] leading-10 font-semibold tabular-nums">{usd(account.data.balance)}</p>
                <p className="muted mt-2 text-xs">
                  USD. {frozen ? "This account is frozen - money cannot be sent from it until an operator unfreezes it." : "Available to spend."}
                </p>
              </>
            )}
          </Card>

          <div className="mt-4 grid gap-4 lg:grid-cols-3">
            {/* Activity is primary content for an account. */}
            <Card className="lg:col-span-2">
              <CardTitle>Recent activity</CardTitle>
              <CardDescription>
                Posted rows show their posting date; a row awaiting review shows when it was requested.
              </CardDescription>
              {recent.isLoading && recent.data == null ? (
                <div className="mt-3 space-y-2"><Skeleton className="h-10" /><Skeleton className="h-10" /><Skeleton className="h-10" /></div>
              ) : recent.isError && recent.data == null ? (
                <div className="mt-3">
                  <LoadFailed
                    title="Couldn't load recent activity"
                    onRetry={() => recent.refetch()}
                  />
                </div>
              ) : (recent.data?.items ?? []).length === 0 ? (
                <div className="mt-3">
                  <CardDescription>No transactions yet.</CardDescription>
                </div>
              ) : (
                <div className="mt-3">
                  <Table>
                    <THead><TRow><TH>When</TH><TH>Memo</TH><TH>Status</TH><TH className="text-right">Amount</TH></TRow></THead>
                    <tbody>
                      {(recent.data?.items ?? []).map((t) => (
                        <TRow key={t.id}>
                          <TD><TxWhen tx={t} /></TD>
                          <TD className="max-w-40 truncate">{t.memo ?? (t.fromIban ? "Transfer" : "Deposit")}</TD>
                          <TD><TxStatusBadge status={t.status} /></TD>
                          <TD className="text-right font-semibold tabular-nums">{signedUsd(t.amount, t.fromIban, t.toIban, account.data.iban)}</TD>
                        </TRow>
                      ))}
                    </tbody>
                  </Table>
                </div>
              )}
              <p className="mt-3 text-sm">
                <Link href={Routes.activity + "?account=" + encodeURIComponent(id)} className="text-action hover:underline">
                  Full history and statement export (CSV / PDF)
                </Link>
              </p>
            </Card>

            {/* Virtual cards stay available but sit beside, never ahead of,
                the account's financial record. */}
            {!isLoan && (
              <Card>
                <div className="mb-3 flex items-center justify-between">
                  <CardTitle>Virtual cards</CardTitle>
                  <Button size="sm" onClick={() => issue.mutate(id)} disabled={issue.isPending}>
                    {issue.isPending ? "Issuing..." : "Issue card"}
                  </Button>
                </div>
                {issued && (
                  <div className="mb-3 rounded-md border border-success-border bg-success-surface p-4" role="status">
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
                  <CardDescription>No cards on this account yet.</CardDescription>
                ) : (
                  <ul className="space-y-2">
                    {(cards.data ?? []).map((c) => (
                      <li key={c.id} className="flex items-center justify-between rounded-md border border-divider p-3">
                        <div>
                          <p className="mono">•••• •••• •••• {c.last4}</p>
                          <p className="muted text-xs">Exp {c.expMonth}/{c.expYear} · <Badge tone={c.status === "ACTIVE" ? "success" : "danger"}>{statusText(c.status)}</Badge></p>
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
