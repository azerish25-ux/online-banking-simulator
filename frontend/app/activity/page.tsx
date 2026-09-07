"use client";

import * as React from "react";
import { useRouter } from "next/navigation";
import { AppShell } from "../../components/layout/app-shell";
import { UnresolvedOperations } from "../../components/banking/unresolved-operations";
import { Button } from "../../components/ui/button";
import { Card } from "../../components/ui/card";
import { EmptyState } from "../../components/ui/empty-state";
import { LoadFailed } from "../../components/ui/load-failed";
import { Field, Input } from "../../components/ui/input";
import { Select } from "../../components/ui/select";
import { ArrowLeft, ArrowRight } from "lucide-react";
import { Skeleton } from "../../components/ui/skeleton";
import { TD, TH, THead, TRow, Table } from "../../components/ui/table";
import { TxStatusBadge } from "../../components/ui/tx-status-badge";
import { TxWhen } from "../../components/ui/tx-when";
import { useToast } from "../../components/feedback/toast";
import { useAccounts, useTransactions } from "../../lib/queries";
import { statementUrl } from "../../lib/statements";
import { downloadAuthed } from "../../lib/download";
import { accountLabel, maskIban, signedUsd } from "../../lib/format";
import { Routes } from "../../lib/routes";

const SIZE = 10;

type InitialParams = { account?: string; from?: string; to?: string };

/** A URL date filter is only trusted when it is a real ISO calendar date;
 *  anything else is dropped (validated recoverable state, section 14). */
function validDate(value: string | undefined): string {
  return value && /^\d{4}-\d{2}-\d{2}$/.test(value) ? value : "";
}

function ActivityContent({ initial }: { initial: InitialParams }) {
  const router = useRouter();
  const { push } = useToast();
  const accounts = useAccounts();
  // The selector is real state backed by the URL: choosing an account drives
  // history + exports, and a reload or a later visit restores the same view.
  const [paramId, setParamId] = React.useState(initial.account ?? "");
  const list = accounts.data ?? [];
  const paramValid = list.some((a) => a.id === paramId);
  const accountId = paramValid ? paramId : list[0]?.id ?? "";
  const [from, setFrom] = React.useState(validDate(initial.from));
  const [to, setTo] = React.useState(validDate(initial.to));
  // The APPLIED range (what the server was asked for) is what travels in the
  // URL - edits in the fields are a draft until "Apply" commits them.
  const [applied, setApplied] = React.useState({ from: validDate(initial.from), to: validDate(initial.to) });
  // Keyset paging (F26): page i is fetched with the opaque cursor that page
  // i-1 returned (blank = the newest page). The cursor is a position, so
  // previously loaded pages never duplicate or skip even when new rows land
  // mid-browse; a window/account change starts a fresh browse at the newest
  // page.
  const [index, setIndex] = React.useState(0);
  const [cursors, setCursors] = React.useState<Record<number, string>>({ 0: "" });
  const cursor = cursors[index] ?? "";
  const page = useTransactions(accountId, cursor, SIZE, applied.from, applied.to);

  /** The URL is the recoverable home of account + applied date range. */
  function syncUrl(next: { account: string; from: string; to: string }) {
    const params = new URLSearchParams();
    if (next.account) params.set("account", next.account);
    if (next.from) params.set("from", next.from);
    if (next.to) params.set("to", next.to);
    const qs = params.toString();
    router.replace(qs ? Routes.activity + "?" + qs : Routes.activity, { scroll: false });
  }

  function selectAccount(id: string) {
    setParamId(id);
    setCursors({ 0: "" });
    setIndex(0);
    syncUrl({ account: id, from: applied.from, to: applied.to });
  }

  function resetBrowse() {
    setCursors({ 0: "" });
    setIndex(0);
  }

  function older() {
    const next = page.data?.nextCursor;
    if (!next) return;
    // Remember which cursor opens the next page before moving to it.
    setCursors((m) => ({ ...m, [index + 1]: next }));
    setIndex(index + 1);
  }

  function newer() {
    if (index === 0) return;
    setIndex(index - 1);
  }

  async function download(kind: "csv" | "pdf") {
    if (!accountId) return;
    try {
      await downloadAuthed(statementUrl(accountId, kind, applied), "statement." + kind);
      push("Statement downloaded.", "success");
    } catch (e) {
      push(e instanceof Error ? e.message : "Export failed", "error");
    }
  }

  function applyRange(e: React.FormEvent) {
    e.preventDefault();
    if (from && to && from > to) {
      push("Start date must be before end date.", "error");
      return;
    }
    resetBrowse();
    setApplied({ from, to });
    syncUrl({ account: accountId, from, to });
  }

  function clearRange() {
    setFrom("");
    setTo("");
    resetBrowse();
    setApplied({ from: "", to: "" });
    syncUrl({ account: accountId, from: "", to: "" });
  }

  const rows = page.data?.items ?? [];
  const hasNext = page.data?.nextCursor != null;
  const totalPages = Math.max(1, Math.ceil((page.data?.total ?? 0) / SIZE));
  // The server's cursor is the authority for "one more page exists"; the
  // total-based page count can lag it when rows land mid-browse.
  const canGoOlder = hasNext || index + 1 < totalPages;
  // Ledger direction needs the viewed account's IBAN: inbound rows are
  // credits, outbound rows are debits, regardless of who else is in the row.
  const viewedAccount = list.find((a) => a.id === accountId);

  const pageHeading = (
    <>
      <h1 className="text-[24px] leading-[30px] font-semibold tracking-tight md:text-[28px] md:leading-[34px]">Activity</h1>
      <p className="muted text-sm">Full transaction history with statement export.</p>
    </>
  );

  // Truthful account states (F10): a failed account list is an error with a
  // retry (never "no accounts"); loading has skeletons; a genuinely empty
  // account list is the one case that says "open an account first".
  if (accounts.isError && accounts.data == null) {
    return (
      <AppShell>
        {pageHeading}
        <Card className="mt-4">
          <LoadFailed
            title="Couldn't load your accounts"
            description="Account history needs the account list. Check your connection and try again."
            onRetry={() => accounts.refetch()}
          />
        </Card>
      </AppShell>
    );
  }
  if (accounts.data == null) {
    return (
      <AppShell>
        {pageHeading}
        <Card className="mt-4"><Skeleton className="h-10" /><Skeleton className="mt-3 h-40" /></Card>
      </AppShell>
    );
  }
  if (accounts.data.length === 0) {
    return (
      <AppShell>
        {pageHeading}
        <Card className="mt-4">
          <EmptyState
            title="No account to show yet"
            description="Open an account on the overview and your activity will appear here."
          />
        </Card>
      </AppShell>
    );
  }

  return (
    <AppShell>
      <div className="mb-5 flex flex-wrap items-end justify-between gap-3">
        <div>{pageHeading}</div>
        <div className="flex gap-2">
          <Select
            aria-label="Account"
            value={accountId}
            onChange={(e) => selectAccount(e.target.value)}
            className="h-10 rounded-md"
          >
            {list.map((a) => (
              <option key={a.id} value={a.id}>{accountLabel(a)}</option>
            ))}
          </Select>
          <Button variant="secondary" onClick={() => download("csv")} disabled={!accountId}>CSV</Button>
          <Button variant="secondary" onClick={() => download("pdf")} disabled={!accountId}>PDF</Button>
        </div>
      </div>

      {/* The recovery surface renders only while a saved operation's answer is
          genuinely unknown (money may have moved and the user must be able to
          resolve it, never left guessing). */}
      <UnresolvedOperations />

      <Card className="mb-4">
        <form onSubmit={applyRange} className="flex flex-wrap items-end gap-3">
          <Field label="From"><Input type="date" value={from} onChange={(e) => setFrom(e.target.value)} /></Field>
          <Field label="To"><Input type="date" value={to} onChange={(e) => setTo(e.target.value)} /></Field>
          <Button type="submit" variant="secondary">Apply</Button>
          {(applied.from || applied.to) && (
            <Button type="button" variant="ghost" onClick={clearRange}>
              Clear
            </Button>
          )}
        </form>
      </Card>

      <Card>
        {page.isLoading && page.data == null ? (
          <div className="space-y-2"><Skeleton className="h-10" /><Skeleton className="h-10" /><Skeleton className="h-10" /></div>
        ) : page.isError && page.data == null ? (
          <LoadFailed
            title="Couldn't load transactions"
            description="Nothing changed on your side. The history request failed. Try again."
            onRetry={() => page.refetch()}
          />
        ) : !accountId ? (
          <EmptyState
            title="No account to show yet"
            description="Open an account on the overview and activity will appear here."
          />
        ) : rows.length === 0 ? (
          <EmptyState title="No transactions" description="Transfers and deposits will appear here." />
        ) : (
          <>
            {page.isError && page.data != null ? (
              <div className="mb-3 flex items-center justify-between gap-2 rounded-md border border-divider bg-surface-subtle px-3 py-2 text-sm">
                <p className="text-content-secondary">Couldn&apos;t refresh. Showing the last loaded page.</p>
                <Button type="button" variant="ghost" size="sm" onClick={() => page.refetch()}>Retry</Button>
              </div>
            ) : null}
            <Table>
              <THead>
                <TRow><TH>When</TH><TH>From</TH><TH>To</TH><TH>Memo</TH><TH>Status</TH><TH className="text-right">Amount</TH></TRow>
              </THead>
              <tbody>
                {rows.map((t) => (
                  <TRow key={t.id}>
                    {/* A posted row's date is its posting time; HELD/CANCELLED
                        rows show the request time and say they never posted. */}
                    <TD><TxWhen tx={t} /></TD>
                    <TD className="mono">{maskIban(t.fromIban) ?? "DEPOSIT"}</TD>
                    <TD className="mono">{maskIban(t.toIban) ?? "-"}</TD>
                    <TD className="max-w-48 truncate">{t.memo ?? "-"}</TD>
                    <TD><TxStatusBadge status={t.status} /></TD>
                    <TD className="text-right font-semibold tabular-nums">{signedUsd(t.amount, t.fromIban, t.toIban, viewedAccount?.iban ?? "")}</TD>
                  </TRow>
                ))}
              </tbody>
            </Table>
            {(index > 0 || hasNext) && (
              <div className="mt-3 flex items-center justify-between text-sm">
                <span className="text-content-secondary">Page {index + 1} of {totalPages}</span>
                <div className="flex gap-2">
                  <Button variant="secondary" size="sm" disabled={index === 0} onClick={newer}>
                    <ArrowLeft size={14} aria-hidden="true" /> Newer
                  </Button>
                  <Button variant="secondary" size="sm" disabled={!canGoOlder} onClick={older}>
                    Older <ArrowRight size={14} aria-hidden="true" />
                  </Button>
                </div>
              </div>
            )}
          </>
        )}
      </Card>
    </AppShell>
  );
}

export default function ActivityPage({
  searchParams
}: {
  searchParams?: Promise<{ account?: string | string[]; from?: string | string[]; to?: string | string[] }>;
}) {
  // Next 15+ delivers searchParams as a Promise - unwrap it, mirroring the
  // account-detail/dashboard page pattern (kept optional for tests).
  const params = searchParams ? React.use(searchParams) : null;
  const one = (v: string | string[] | undefined) => (typeof v === "string" ? v : "");
  return (
    <ActivityContent
      initial={{ account: one(params?.account), from: validDate(one(params?.from)), to: validDate(one(params?.to)) }}
    />
  );
}
