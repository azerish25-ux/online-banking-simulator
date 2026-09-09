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
import { ArrowLeft, ArrowRight, Search } from "lucide-react";
import { Skeleton } from "../../components/ui/skeleton";
import { TD, TH, THead, TRow, Table } from "../../components/ui/table";
import { TxStatusBadge } from "../../components/ui/tx-status-badge";
import { TxWhen } from "../../components/ui/tx-when";
import { useToast } from "../../components/feedback/toast";
import { useAccounts, useTransactions, type HistoryFilters } from "../../lib/queries";
import { statementUrl } from "../../lib/statements";
import { downloadAuthed } from "../../lib/download";
import { accountLabel, maskIban, signedUsd } from "../../lib/format";
import { Routes } from "../../lib/routes";

const SIZE = 10;

type InitialParams = {
  account?: string;
  from?: string;
  to?: string;
  min?: string;
  max?: string;
  kind?: string;
  status?: string;
  q?: string;
};

/** A URL date filter is only trusted when it is a real ISO calendar date;
 *  anything else is dropped (validated recoverable state). */
function validDate(value: string | undefined): string {
  return value && /^\d{4}-\d{2}-\d{2}$/.test(value) ? value : "";
}

/** An amount filter is only trusted when it is a non-negative decimal with at
 *  most four fraction digits - exactly what the ledger stores. */
function validAmount(value: string | undefined): string {
  const v = (value ?? "").trim();
  return v && /^\d+(\.\d{1,4})?$/.test(v) ? v : "";
}

const KINDS = ["TRANSFER", "DEPOSIT", "INTEREST", "REVERSAL"] as const;
const STATUSES = ["POSTED", "HELD", "CANCELLED"] as const;

function validKind(value: string | undefined): string {
  return value && (KINDS as readonly string[]).includes(value) ? value : "";
}

function validStatus(value: string | undefined): string {
  return value && (STATUSES as readonly string[]).includes(value) ? value : "";
}

/** The APPLIED predicate set: everything the server was asked for. Empty
 *  members are open filters, and the whole set travels in the URL. */
type Applied = {
  from: string;
  to: string;
  min: string;
  max: string;
  kind: string;
  status: string;
  q: string;
};

function appliedFromInitial(initial: InitialParams): Applied {
  return {
    from: validDate(initial.from),
    to: validDate(initial.to),
    min: validAmount(initial.min),
    max: validAmount(initial.max),
    kind: validKind(initial.kind),
    status: validStatus(initial.status),
    q: (initial.q ?? "").trim().slice(0, 200)
  };
}

const EMPTY_APPLIED: Applied = { from: "", to: "", min: "", max: "", kind: "", status: "", q: "" };

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
  // Drafts vs the APPLIED set: edits are drafts until "Apply" commits them to
  // the URL and the server request (validated recoverable state).
  const [draft, setDraft] = React.useState<Applied>(appliedFromInitial(initial));
  const [applied, setApplied] = React.useState<Applied>(appliedFromInitial(initial));
  // Keyset paging: page i is fetched with the opaque cursor that page
  // i-1 returned (blank = the newest page). The cursor is a position, so
  // previously loaded pages never duplicate or skip even when new rows land
  // mid-browse; a filter/account change starts a fresh browse at the newest
  // page.
  const [index, setIndex] = React.useState(0);
  const [cursors, setCursors] = React.useState<Record<number, string>>({ 0: "" });
  const cursor = cursors[index] ?? "";
  // Server-backed filters: every predicate is a SQL
  // clause over the whole account history - the page never filters what a
  // loaded page already returned. An empty applied set is a normal browse.
  const filters: HistoryFilters | undefined = applied.min || applied.max || applied.kind || applied.status || applied.q
    ? {
        minAmount: applied.min || undefined,
        maxAmount: applied.max || undefined,
        kinds: applied.kind ? [applied.kind] : undefined,
        statuses: applied.status ? [applied.status] : undefined,
        q: applied.q || undefined
      }
    : undefined;
  const page = useTransactions(accountId, cursor, SIZE, applied.from, applied.to, filters);

  /** The URL is the recoverable home of account + applied predicates. */
  function syncUrl(next: { account: string } & Applied) {
    const params = new URLSearchParams();
    if (next.account) params.set("account", next.account);
    if (next.from) params.set("from", next.from);
    if (next.to) params.set("to", next.to);
    if (next.min) params.set("min", next.min);
    if (next.max) params.set("max", next.max);
    if (next.kind) params.set("kind", next.kind);
    if (next.status) params.set("status", next.status);
    if (next.q) params.set("q", next.q);
    const qs = params.toString();
    router.replace(qs ? Routes.activity + "?" + qs : Routes.activity, { scroll: false });
  }

  function selectAccount(id: string) {
    setParamId(id);
    setCursors({ 0: "" });
    setIndex(0);
    syncUrl({ account: id, ...applied });
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
      await downloadAuthed(
        statementUrl(accountId, kind, { from: applied.from, to: applied.to }),
        "statement." + kind
      );
      push("Statement downloaded.", "success");
    } catch (e) {
      push(e instanceof Error ? e.message : "Export failed", "error");
    }
  }

  /** Applies drafts as one validated predicate set (dates + filters). */
  function applyFilters(e: React.FormEvent) {
    e.preventDefault();
    if (draft.from && draft.to && draft.from > draft.to) {
      push("Start date must be before end date.", "error");
      return;
    }
    if (draft.min && draft.max && Number(draft.min) > Number(draft.max)) {
      push("Minimum amount must not exceed the maximum.", "error");
      return;
    }
    resetBrowse();
    setApplied(draft);
    syncUrl({ account: accountId, ...draft });
  }

  function clearFilters() {
    setDraft(EMPTY_APPLIED);
    resetBrowse();
    setApplied(EMPTY_APPLIED);
    syncUrl({ account: accountId, ...EMPTY_APPLIED });
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
  const anyFilter = applied.from || applied.to || applied.min || applied.max || applied.kind || applied.status || applied.q;

  const pageHeading = (
    <>
      <h1 className="text-xl leading-7">Activity</h1>
      <p className="muted text-sm">Full transaction history with statement export.</p>
    </>
  );

  // Truthful account states: a failed account list is an error with a
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
            className="h-10 w-56"
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

      {/* Filters sit in a labelled band - a real fieldset, not another panel
          floating between the toolbar and the ledger. */}
      <fieldset className="well mb-4 px-4 py-3">
        <legend className="px-1 text-sm font-medium text-content-secondary">Filter history</legend>
        <form onSubmit={applyFilters} className="flex flex-wrap items-end gap-3">
          <Field label="From"><Input type="date" value={draft.from} onChange={(e) => setDraft((d) => ({ ...d, from: e.target.value }))} /></Field>
          <Field label="To"><Input type="date" value={draft.to} onChange={(e) => setDraft((d) => ({ ...d, to: e.target.value }))} /></Field>
          <Field label="Min amount"><Input inputMode="decimal" placeholder="0.00" value={draft.min} onChange={(e) => setDraft((d) => ({ ...d, min: e.target.value }))} /></Field>
          <Field label="Max amount"><Input inputMode="decimal" placeholder="0.00" value={draft.max} onChange={(e) => setDraft((d) => ({ ...d, max: e.target.value }))} /></Field>
          <Field label="Type">
            <Select value={draft.kind} onChange={(e) => setDraft((d) => ({ ...d, kind: e.target.value }))} className="w-40">
              <option value="">All</option>
              {KINDS.map((k) => <option key={k} value={k}>{k[0] + k.slice(1).toLowerCase()}</option>)}
            </Select>
          </Field>
          <Field label="State">
            <Select value={draft.status} onChange={(e) => setDraft((d) => ({ ...d, status: e.target.value }))} className="w-36">
              <option value="">All</option>
              {STATUSES.map((s) => <option key={s} value={s}>{s[0] + s.slice(1).toLowerCase()}</option>)}
            </Select>
          </Field>
          <Field label="Search">
            <div className="relative">
              <Search size={14} aria-hidden="true" className="absolute left-2.5 top-1/2 -translate-y-1/2 text-content-tertiary" />
              <Input className="pl-8 w-48" placeholder="Memo or IBAN" value={draft.q} onChange={(e) => setDraft((d) => ({ ...d, q: e.target.value }))} />
            </div>
          </Field>
          <Button type="submit" variant="secondary">Apply</Button>
          {anyFilter && (
            <Button type="button" variant="ghost" onClick={clearFilters}>
              Clear
            </Button>
          )}
        </form>
        <p className="muted mt-2 text-xs">
          Filters run against the whole account history on the server - never just the rows already on screen.
        </p>
      </fieldset>

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
          <EmptyState
            title={anyFilter ? "No matching transactions" : "No transactions"}
            description={anyFilter ? "No rows match these filters. Widen or clear them and try again." : "Transfers and deposits will appear here."}
          />
        ) : (
          <>
            {page.isError && page.data != null ? (
              <div className="well mb-3 flex items-center justify-between gap-2 px-3 py-2 text-sm">
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
                    <TD className="nums text-right font-semibold">{signedUsd(t.amount, t.fromIban, t.toIban, viewedAccount?.iban ?? "")}</TD>
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
  searchParams?: Promise<{
    account?: string | string[];
    from?: string | string[];
    to?: string | string[];
    min?: string | string[];
    max?: string | string[];
    kind?: string | string[];
    status?: string | string[];
    q?: string | string[];
  }>;
}) {
  // Next 15+ delivers searchParams as a Promise - unwrap it, mirroring the
  // account-detail/dashboard page pattern (kept optional for tests).
  const params = searchParams ? React.use(searchParams) : null;
  const one = (v: string | string[] | undefined) => (typeof v === "string" ? v : "");
  return (
    <ActivityContent
      initial={{
        account: one(params?.account),
        from: validDate(one(params?.from)),
        to: validDate(one(params?.to)),
        min: validAmount(one(params?.min)),
        max: validAmount(one(params?.max)),
        kind: validKind(one(params?.kind)),
        status: validStatus(one(params?.status)),
        q: one(params?.q).trim().slice(0, 200)
      }}
    />
  );
}
