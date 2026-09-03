"use client";

import { ArrowLeft, ArrowRight } from "lucide-react";
import * as React from "react";
import { AppShell } from "../../components/layout/app-shell";
import { Badge } from "../../components/ui/badge";
import { Button } from "../../components/ui/button";
import { Card, CardTitle } from "../../components/ui/card";
import { EmptyState } from "../../components/ui/empty-state";
import { Field, Input } from "../../components/ui/input";
import { Skeleton } from "../../components/ui/skeleton";
import { TD, TH, THead, TRow, Table } from "../../components/ui/table";
import { useToast } from "../../components/feedback/toast";
import { api, getToken } from "../../lib/api";
import { useAccounts, useTransactions } from "../../lib/queries";
import { statementUrl } from "../../lib/statements";
import { fmtDate, usd } from "../../lib/format";

const SIZE = 10;

export default function ActivityPage() {
  const { push } = useToast();
  const accounts = useAccounts();
  const accountId = accounts.data?.[0]?.id ?? "";
  const [from, setFrom] = React.useState("");
  const [to, setTo] = React.useState("");
  const [applied, setApplied] = React.useState({ from: "", to: "" });
  const [index, setIndex] = React.useState(0);

  const page = useTransactions(accountId, index, SIZE, applied.from, applied.to);

  async function download(kind: "csv" | "pdf") {
    try {
      const res = await fetch(statementUrl(accountId, kind, applied), {
        headers: { Authorization: "Bearer " + (getToken() ?? "") }
      });
      if (!res.ok) throw new Error("Export failed: " + res.status);
      const blob = await res.blob();
      const url = URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.href = url;
      a.download = "statement." + kind;
      a.click();
      URL.revokeObjectURL(url);
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
    setIndex(0);
    setApplied({ from, to });
  }

  const rows = page.data?.content ?? [];
  const totalPages = page.data?.totalPages ?? 1;

  return (
    <AppShell>
      <div className="mb-5 flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="text-2xl font-bold tracking-tight">Activity</h1>
          <p className="muted text-sm">Full transaction history with statement export.</p>
        </div>
        <div className="flex gap-2">
          <select
            aria-label="Account"
            value={accountId}
            onChange={() => setIndex(0)}
            className="h-10 rounded-md border border-line bg-ink-950/70 px-3 text-sm focus:border-brass-500 focus:outline-none"
          >
            {(accounts.data ?? []).map((a) => (
              <option key={a.id} value={a.id}>{a.type} ...{a.iban.slice(-6)}</option>
            ))}
          </select>
          <Button variant="secondary" onClick={() => download("csv")} disabled={!accountId}>CSV</Button>
          <Button variant="secondary" onClick={() => download("pdf")} disabled={!accountId}>PDF</Button>
        </div>
      </div>

      <Card className="mb-4">
        <form onSubmit={applyRange} className="flex flex-wrap items-end gap-3">
          <Field label="From"><Input type="date" value={from} onChange={(e) => setFrom(e.target.value)} /></Field>
          <Field label="To"><Input type="date" value={to} onChange={(e) => setTo(e.target.value)} /></Field>
          <Button type="submit" variant="secondary">Apply</Button>
          {(applied.from || applied.to) && (
            <Button type="button" variant="ghost" onClick={() => { setFrom(""); setTo(""); setApplied({ from: "", to: "" }); setIndex(0); }}>
              Clear
            </Button>
          )}
        </form>
      </Card>

      <Card>
        {page.isLoading ? (
          <div className="space-y-2"><Skeleton className="h-10" /><Skeleton className="h-10" /><Skeleton className="h-10" /></div>
        ) : rows.length === 0 ? (
          <EmptyState title="No transactions" description="Transfers and deposits will appear here." />
        ) : (
          <>
            <Table>
              <THead>
                <TRow><TH>When</TH><TH>From</TH><TH>To</TH><TH>Memo</TH><TH>Status</TH><TH className="text-right">Amount</TH></TRow>
              </THead>
              <tbody>
                {rows.map((t) => (
                  <TRow key={t.id}>
                    <TD className="whitespace-nowrap">{fmtDate(t.createdAt)}</TD>
                    <TD className="mono">{t.fromIban ? "..." + t.fromIban.slice(-6) : "DEPOSIT"}</TD>
                    <TD className="mono">{t.toIban ? "..." + t.toIban.slice(-6) : "-"}</TD>
                    <TD className="max-w-48 truncate">{t.memo ?? "-"}</TD>
                    <TD>{t.flagged ? <Badge tone="danger">FLAGGED</Badge> : <span className="muted text-xs">posted</span>}</TD>
                    <TD className="text-right font-semibold tabular-nums">{usd(t.amount)}</TD>
                  </TRow>
                ))}
              </tbody>
            </Table>
            <div className="mt-3 flex items-center justify-between text-sm">
              <span className="muted">Page {index + 1} of {Math.max(1, totalPages)}</span>
              <div className="flex gap-2">
                <Button variant="secondary" size="sm" disabled={index === 0} onClick={() => setIndex((i) => i - 1)}><ArrowLeft size={14} aria-hidden="true" /> Prev</Button>
                <Button variant="secondary" size="sm" disabled={index + 1 >= totalPages} onClick={() => setIndex((i) => i + 1)}>Next <ArrowRight size={14} aria-hidden="true" /></Button>
              </div>
            </div>
          </>
        )}
      </Card>
    </AppShell>
  );
}
