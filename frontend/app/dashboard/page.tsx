"use client";

import * as React from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { AppShell } from "../../components/layout/app-shell";
import { UnresolvedOperations } from "../../components/banking/unresolved-operations";
import { DepositDialog } from "../../components/banking/deposit-dialog";
import { OpenAccountDialog } from "../../components/banking/open-account-dialog";
import { Badge } from "../../components/ui/badge";
import { Button } from "../../components/ui/button";
import { Card, CardBody, CardHead, CardTitle } from "../../components/ui/card";
import { EmptyState } from "../../components/ui/empty-state";
import { LoadFailed } from "../../components/ui/load-failed";
import { Select } from "../../components/ui/select";
import { Skeleton } from "../../components/ui/skeleton";
import { TD, TH, THead, TRow, Table } from "../../components/ui/table";
import { TxStatusBadge } from "../../components/ui/tx-status-badge";
import { TxWhen } from "../../components/ui/tx-when";
import { SpendingChart } from "../../components/charts/spending-chart";
import { Routes } from "../../lib/routes";
import { useAccounts, useMe, useSummary, useTransactions } from "../../lib/queries";
import { accountLabel, maskIban, signedUsd, toTenThousandths, totalUsd, usd, usdReview } from "../../lib/format";
import { cn } from "../../lib/cn";
import type { Account } from "../../lib/api-types";

/** "CHECKING" → "Checking", "LOAN" → "Loan" - the account's readable name. */
function typeName(type: string): string {
  return type.charAt(0) + type.slice(1).toLowerCase();
}

/** Exact debt test on the server's policy-derived total: a 0.0001 obligation
 *  is debt even though it rounds to $0.00. Never derive debt from a rounded
 *  balance figure. */
function owesAnything(a: Account): boolean {
  const owed = toTenThousandths(a.totalOwed ?? "0.0000");
  return owed !== null && owed > 0n;
}

/** A labelled figure on the summary band: small label over the number, and
 *  when the figure is a debt the color says so. */
function StripCell({
  label,
  value,
  sub,
  danger = false
}: {
  label: string;
  value: string;
  sub?: string;
  danger?: boolean;
}) {
  return (
    <div className="md:border-l md:border-divider md:pl-4 first:md:border-l-0 first:md:pl-0">
      <p className="muted text-sm">{label}</p>
      <p className={cn("nums mt-1 text-[26px] leading-8 font-semibold", danger && "text-danger")}>
        {value}
      </p>
      {sub ? <p className="muted mt-1 text-xs">{sub}</p> : null}
    </div>
  );
}

/** Balance cell content for ONE account - shared by the desktop table row and
 *  the narrow stacked row so the two views can never disagree. */
function AccountAmount({ account }: { account: Account }) {
  if (account.type === "LOAN") {
    if (owesAnything(account)) {
      return (
        <>
          <p className="nums text-right text-base font-semibold text-danger">
            {usdReview(account.totalOwed ?? "0.0000")}
          </p>
          <p className="nums muted mt-0.5 text-right text-xs">
            Principal {usdReview(account.principalOwed ?? "0.0000")} · Interest{" "}
            {usdReview(account.interestOwed ?? "0.0000")} · Credit left{" "}
            {usdReview(account.availableCredit ?? "0.0000")}
          </p>
        </>
      );
    }
    return (
      <>
        <p className="nums text-right text-base font-semibold">
          {usdReview(account.totalOwed ?? "0.0000")}
        </p>
        <p className="nums muted mt-0.5 text-right text-xs">
          Nothing owed · Credit {usdReview(account.availableCredit ?? "0.0000")} available
        </p>
      </>
    );
  }
  return (
    <>
      <p className="nums text-right text-base font-semibold">{usd(account.balance)}</p>
      {account.status === "FROZEN" && (
        <p className="muted mt-0.5 text-right text-xs">Frozen - not spendable</p>
      )}
    </>
  );
}

function DashboardContent({ initialAccountId }: { initialAccountId?: string }) {
  const router = useRouter();
  const me = useMe();
  const accounts = useAccounts();

  // The feed and chart scope is EXPLICIT - never an invisible first-account
  // coupling. The choice lives in the URL so it survives navigation to
  // account detail or Activity; the fallback is the first account only when
  // the URL names none.
  const [scopeOverride, setScopeOverride] = React.useState("");
  const accountList = accounts.data ?? [];
  const validInitial = accountList.some((a) => a.id === initialAccountId) ? initialAccountId : "";
  const accountId = scopeOverride || validInitial || accountList[0]?.id || "";
  const scopedAccount = accountList.find((a) => a.id === accountId);

  const recent = useTransactions(accountId, "", 6);
  const summary = useSummary(accountId, 6);

  const [depositOpen, setDepositOpen] = React.useState(false);
  const [openOpen, setOpenOpen] = React.useState(false);

  const depositAccounts = accountList.filter((a) => a.type !== "LOAN");
  const activeDepositAccounts = depositAccounts.filter((a) => a.status === "ACTIVE");
  const frozenDepositCount = depositAccounts.length - activeDepositAccounts.length;
  const loanAccounts = accountList.filter((a) => a.type === "LOAN");
  const hasDebt = loanAccounts.some(owesAnything);
  const canDeposit = activeDepositAccounts.length > 0;

  // Exact arithmetic on the server's decimal strings: spendable = ACTIVE
  // deposit balances; booked = every deposit balance (frozen money is on
  // deposit but NOT available to spend); debt = the policy-derived totals.
  const availableUsd = totalUsd(activeDepositAccounts.map((a) => a.balance));
  const bookedUsd = totalUsd(depositAccounts.map((a) => a.balance));
  const loanDebtUsd = totalUsd(loanAccounts.map((a) => a.totalOwed ?? "0.0000"));

  function selectAccount(id: string) {
    setScopeOverride(id);
    const first = accountList[0]?.id;
    const next = id && id !== first ? Routes.dashboard + "?account=" + encodeURIComponent(id) : Routes.dashboard;
    router.replace(next, { scroll: false });
  }

  const user = me.data;
  const loading = accountList.length === 0 && accounts.data == null && !accounts.isError;
  const accountsFailed = accounts.data == null && accounts.isError;

  return (
    <AppShell>
      <div className="mb-5 flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="text-xl leading-7">Overview</h1>
          <p className="muted mt-1 text-sm">
            {user ? "Welcome back, " + user.fullName + ". Everything here uses simulated funds." : "Loading..."}
          </p>
        </div>
        <div className="flex gap-2">
          <Button variant="secondary" onClick={() => setOpenOpen(true)}>Open account</Button>
          <Button variant="secondary" onClick={() => setDepositOpen(true)} disabled={!canDeposit}>
            Deposit funds
          </Button>
          <Link href={Routes.transfers}>
            <Button>Send money</Button>
          </Link>
        </div>
      </div>

      {accountsFailed ? (
        <Card className="mt-4">
          <CardBody>
            <LoadFailed
              title="Couldn't load your accounts"
              description="Balances, recent activity, and the chart all depend on the account list. Check your connection and try again."
              onRetry={() => accounts.refetch()}
            />
          </CardBody>
        </Card>
      ) : loading ? (
        <div className="mt-4 space-y-4">
          <Skeleton className="h-24" />
          <Skeleton className="h-52" />
        </div>
      ) : accountList.length === 0 ? (
        <Card className="mt-4">
          <CardBody>
            <EmptyState
              title="No accounts yet"
              description="Open a checking, savings, or loan account to get started. Deposits and transfers land here."
            />
          </CardBody>
        </Card>
      ) : (
        <>
          {/* The account record. Position band first - usable funds and debt
              are separate figures; net position is never presented as
              spendable money. Then the account list. */}
          <Card className="mt-5">
            <div className="grid gap-4 px-4 py-3.5 md:grid-cols-3">
              <StripCell
                label="Available funds"
                value={availableUsd}
                sub="Active deposit accounts, ready to spend"
              />
              {frozenDepositCount > 0 && (
                <StripCell
                  label="Booked deposits"
                  value={bookedUsd}
                  sub={frozenDepositCount + (frozenDepositCount === 1 ? " frozen account is" : " frozen accounts are") + " on deposit but not spendable"}
                />
              )}
              {loanAccounts.length > 0 && (
                <StripCell
                  label="Loan debt"
                  value={hasDebt ? loanDebtUsd : "$0.00"}
                  sub={hasDebt ? "Principal and interest you owe" : "No amount owed on your loans"}
                  danger={hasDebt}
                />
              )}
            </div>
          </Card>

          <Card className="mt-5">
            <CardHead>
              <CardTitle>Accounts</CardTitle>
              <p className="muted text-sm">USD · loan splits from the loan policy</p>
            </CardHead>

            {/* Desktop table (deliberately hidden below md). */}
            <div className="hidden md:block">
              <Table>
                <THead>
                  <TRow>
                    <TH>Account</TH>
                    <TH>Status</TH>
                    <TH className="text-right">Balance (USD)</TH>
                  </TRow>
                </THead>
                <tbody>
                  {accountList.map((a) => (
                    <TRow key={a.id}>
                      <TD>
                        <Link href={Routes.account(a.id)} className="font-medium text-content hover:text-action hover:underline">
                          {typeName(a.type)}
                        </Link>
                        <p className="mono muted mt-0.5">{maskIban(a.iban)}</p>
                      </TD>
                      <TD>
                        {a.status === "FROZEN" ? <Badge tone="warning">Frozen</Badge> : <span className="muted text-sm">Active</span>}
                      </TD>
                      <TD className="text-right align-top">
                        <AccountAmount account={a} />
                      </TD>
                    </TRow>
                  ))}
                </tbody>
              </Table>
            </div>

            {/* Narrow stacked rows - the same essential information, never a
                horizontally scrolling table that hides status or amounts. */}
            <ul className="divide-y divide-divider md:hidden">
              {accountList.map((a) => (
                <li key={a.id} className="p-3">
                  <div className="flex items-start justify-between gap-3">
                    <div>
                      <Link href={Routes.account(a.id)} className="font-medium text-content hover:text-action hover:underline">
                        {typeName(a.type)}
                      </Link>
                      <p className="mono muted mt-0.5">{maskIban(a.iban)}</p>
                      <p className="mt-1">
                        {a.status === "FROZEN" ? <Badge tone="warning">Frozen</Badge> : <span className="muted text-sm">Active</span>}
                      </p>
                    </div>
                    <div className="min-w-0 shrink text-right">
                      <AccountAmount account={a} />
                    </div>
                  </div>
                </li>
              ))}
            </ul>
          </Card>

          <UnresolvedOperations />

          <Card className="mt-5">
            <CardHead>
              <CardTitle>Recent activity</CardTitle>
              <div className="flex items-center gap-3">
                <Select
                  aria-label="Account shown in recent activity and the chart"
                  value={accountId}
                  onChange={(e) => selectAccount(e.target.value)}
                  className="h-8 w-auto text-sm"
                >
                  {accountList.map((a) => (
                    <option key={a.id} value={a.id}>{accountLabel(a)}</option>
                  ))}
                </Select>
                <Link href={Routes.activity + "?account=" + encodeURIComponent(accountId)} className="whitespace-nowrap text-sm text-action hover:underline">
                  Full activity
                </Link>
              </div>
            </CardHead>
            <p className="muted border-b border-divider px-4 py-2 text-xs">
              {scopedAccount
                ? typeName(scopedAccount.type) + " · " + (maskIban(scopedAccount.iban) ?? "") + " · USD"
                : "Money in and out of the selected account."}
            </p>

            {recent.isLoading && recent.data == null ? (
              <div className="space-y-2 px-4 py-3"><Skeleton className="h-9" /><Skeleton className="h-9" /><Skeleton className="h-9" /></div>
            ) : recent.isError && recent.data == null ? (
              <div className="px-4 py-3">
                <LoadFailed
                  title="Couldn't load recent activity"
                  description="The history request failed. Try again."
                  onRetry={() => recent.refetch()}
                />
              </div>
            ) : (recent.data?.items ?? []).length === 0 ? (
              <div className="px-4 py-3">
                <EmptyState title="No transactions yet" description="Send your first transfer to see it here." />
              </div>
            ) : (
              <Table>
                <THead>
                  <TRow>
                    <TH>When</TH><TH>From</TH><TH>To</TH><TH>Memo</TH><TH>Status</TH><TH className="text-right">Amount</TH>
                  </TRow>
                </THead>
                <tbody>
                  {(recent.data?.items ?? []).map((t) => (
                    <TRow key={t.id}>
                      <TD><TxWhen tx={t} /></TD>
                      <TD className="mono">{maskIban(t.fromIban) ?? "DEPOSIT"}</TD>
                      <TD className="mono">{maskIban(t.toIban) ?? "-"}</TD>
                      <TD className="max-w-40 truncate">{t.memo ?? "-"}</TD>
                      <TD><TxStatusBadge status={t.status} /></TD>
                      <TD className="nums text-right font-semibold">{signedUsd(t.amount, t.fromIban, t.toIban, scopedAccount?.iban ?? "")}</TD>
                    </TRow>
                  ))}
                </tbody>
              </Table>
            )}
          </Card>

          {/* The chart stays below the money and the account record. */}
          <Card className="mt-5">
            <CardHead>
              <CardTitle>
                Money flow · {scopedAccount ? accountLabel(scopedAccount) : "selected account"} · last 6 months
              </CardTitle>
              <p className="muted text-sm">Monthly totals, USD</p>
            </CardHead>
            <CardBody>
              {summary.data == null ? (
                summary.isError ? (
                  <LoadFailed
                    title="Couldn't load the chart"
                    description="The monthly summary request failed. Try again."
                    onRetry={() => summary.refetch()}
                  />
                ) : (
                  <Skeleton className="h-48" />
                )
              ) : (
                <SpendingChart data={summary.data} />
              )}
            </CardBody>
          </Card>

          <OpenAccountDialog open={openOpen} onClose={() => setOpenOpen(false)} hasLoan={loanAccounts.length > 0} />
          <DepositDialog open={depositOpen} onClose={() => setDepositOpen(false)} accounts={accountList} />
        </>
      )}
    </AppShell>
  );
}

export default function DashboardPage({
  searchParams
}: {
  searchParams?: Promise<{ account?: string | string[] }>;
}) {
  // Next 15+ delivers searchParams as a Promise - unwrap it, mirroring the
  // account-detail page pattern (kept optional so tests render without it).
  const params = searchParams ? React.use(searchParams) : null;
  const initial = typeof params?.account === "string" ? params.account : "";
  return <DashboardContent initialAccountId={initial} />;
}
