"use client";

import * as React from "react";
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
import { useToast } from "../../components/feedback/toast";
import { useAccounts, useTransactions } from "../../lib/queries";
import { statementUrl } from "../../lib/statements";
import { downloadAuthed } from "../../lib/download";
import { accountLabel, fmtDate, maskIban, signedUsd } from "../../lib/format";

const SIZE = 10;

export default function ActivityPage() {
  const { push } = useToast();
  const accounts = useAccounts();
  // The selector is real state: choosing an account drives history + exports.
  const [selectedId, setSelectedId] = React.useState("");
  const accountId = selectedId || accounts.data?.[0]?.id || "";
  const [from, setFrom] = React.useState("");
  const [to, setTo] = React.useState("");
  const [applied, setApplied] = React.useState({ from: "", to: "" });
  // Keyset paging (F26): page i is fetched with the opaque cursor that page
  // i-1 returned (blank = the newest page). The cursor is a position, so
  // previously loaded pages never duplicate or skip even when new rows land
  // mid-browse; a window/account change starts a fresh browse at the newest
  // page.
  const [index, setIndex] = React.useState(0);
  const [cursors, setCursors] = React.useState<Record<number, string>>({ 0: "" });
  const cursor = cursors[index] ?? "";
  const page = useTransactions(accountId, cursor, SIZE, applied.from, applied.to);

  function selectAccount(id: string) {
    setSelectedId(id);
    setCursors({ 0: "" });
    setIndex(0);
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
  }

  const rows = page.data?.items ?? [];
  const hasNext = page.data?.nextCursor != null;
  const totalPages = Math.max(1, Math.ceil((page.data?.total ?? 0) / SIZE));
  // The server's cursor is the authority for "one more page exists"; the
  // total-based page count can lag it when rows land mid-browse.
  const canGoOlder = hasNext || index + 1 < totalPages;
  // Ledger direction needs the viewed account's IBAN: inbound rows are
  // credits, outbound rows are debits, regardless of who else is in the row.
  const viewedAccount = (accounts.data ?? []).find((a) => a.id === accountId);

  // Truthful account states (F10): a failed account list is an error with a
  // retry (never "no accounts"); loading has skeletons; a genuinely empty
  // account list is the one case that says "open an account first".
  if (accounts.isError && accounts.data == null) {
    return (
      <AppShell>
        <h1 className="text-2xl font-bold tracking-tight">Activity</h1>
        <p className="muted text-sm">Full transaction history with statement export.</p>
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
        <h1 className="text-2xl font-bold tracking-tight">Activity</h1>
        <Card className="mt-4"><Skeleton className="h-10" /><Skeleton className="mt-3 h-40" /></Card>
      </AppShell>
    );
  }
  if (accounts.data.length === 0) {
    return (
      <AppShell>
        <h1 className="text-2xl font-bold tracking-tight">Activity</h1>
        <p className="muted text-sm">Full transaction history with statement export.</p>
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
        <div>
          <h1 className="text-2xl font-bold tracking-tight">Activity</h1>
          <p className="muted text-sm">Full transaction history with statement export.</p>
        </div>
        <div className="flex gap-2">
          <Select
            aria-label="Account"
            value={accountId}
            onChange={(e) => selectAccount(e.target.value)}
            className="h-10 rounded-md"
          >
            {(accounts.data ?? []).length === 0 && <option value="">No accounts</option>}
            {(accounts.data ?? []).map((a) => (
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
            <Button type="button" variant="ghost" onClick={() => { setFrom(""); setTo(""); resetBrowse(); setApplied({ from: "", to: "" }); }}>
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
            description="Nothing changed on your side - the history request failed. Try again."
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
              <div className="mb-3 flex items-center justify-between gap-2 rounded-md border border-line bg-ink-800/60 px-3 py-2 text-sm">
                <p className="text-content-muted">Couldn&apos;t refresh - showing the last loaded page.</p>
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
                    <TD className="whitespace-nowrap">{fmtDate(t.createdAt)}</TD>
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
                <span className="text-content-muted">Page {index + 1} of {totalPages}</span>
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
