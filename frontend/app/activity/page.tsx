"use client";

import * as React from "react";
import { AppShell } from "../../components/layout/app-shell";
import { Button } from "../../components/ui/button";
import { Card, CardTitle } from "../../components/ui/card";
import { EmptyState } from "../../components/ui/empty-state";
import { Skeleton } from "../../components/ui/skeleton";
import { TD, TH, THead, TRow, Table } from "../../components/ui/table";
import { useToast } from "../../components/feedback/toast";
import { api, getToken } from "../../lib/api";
import { fmtDate, usd } from "../../lib/format";

type Account = { id: string; iban: string; type: string; balance: string };
type Tx = { id: string; fromIban: string | null; toIban: string | null; amount: string; currency: string; memo: string | null; createdAt: string };
type Page<T> = { content: T[]; totalPages: number; number: number };

const SIZE = 10;

export default function ActivityPage() {
  const { push } = useToast();
  const [accounts, setAccounts] = React.useState<Account[]>([]);
  const [accountId, setAccountId] = React.useState("");
  const [page, setPage] = React.useState<Page<Tx> | null>(null);
  const [index, setIndex] = React.useState(0);

  React.useEffect(() => {
    api("/v1/accounts").then((accs: Account[]) => {
      setAccounts(accs);
      if (accs.length > 0) setAccountId(accs[0].id);
    }).catch((e) => push(e instanceof Error ? e.message : "Failed to load accounts", "error"));
  }, [push]);

  React.useEffect(() => {
    if (!accountId) return;
    setPage(null);
    api("/v1/transactions?accountId=" + accountId + "&page=" + index + "&size=" + SIZE)
      .then(setPage)
      .catch((e) => push(e instanceof Error ? e.message : "Failed to load activity", "error"));
  }, [accountId, index, push]);

  async function downloadCsv() {
    try {
      const res = await fetch("/backend/accounts/" + accountId + "/statement.csv", {
        headers: { Authorization: "Bearer " + (getToken() ?? "") }
      });
      if (!res.ok) throw new Error("Export failed: " + res.status);
      const blob = await res.blob();
      const url = URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.href = url;
      a.download = "statement.csv";
      a.click();
      URL.revokeObjectURL(url);
      push("Statement downloaded.", "success");
    } catch (e) {
      push(e instanceof Error ? e.message : "Export failed", "error");
    }
  }

  return (
    <AppShell>
      <div className="mb-5 flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="text-2xl font-bold tracking-tight">Activity</h1>
          <p className="muted text-sm">Full transaction history with CSV export.</p>
        </div>
        <div className="flex gap-2">
          <select
            aria-label="Account"
            value={accountId}
            onChange={(e) => { setAccountId(e.target.value); setIndex(0); }}
            className="h-10 rounded-lg border border-line bg-ink-950 px-3 text-sm"
          >
            {accounts.map((a) => (
              <option key={a.id} value={a.id}>{a.type} ...{a.iban.slice(-6)}</option>
            ))}
          </select>
          <Button variant="secondary" onClick={downloadCsv} disabled={!accountId}>
            Export CSV
          </Button>
        </div>
      </div>

      <Card>
        {page == null ? (
          <div className="space-y-2"><Skeleton className="h-10" /><Skeleton className="h-10" /><Skeleton className="h-10" /></div>
        ) : page.content.length === 0 ? (
          <EmptyState title="No transactions" description="Transfers and deposits will appear here." />
        ) : (
          <>
            <Table>
              <THead>
                <TRow><TH>When</TH><TH>From</TH><TH>To</TH><TH>Memo</TH><TH className="text-right">Amount</TH></TRow>
              </THead>
              <tbody>
                {page.content.map((t) => (
                  <TRow key={t.id}>
                    <TD className="whitespace-nowrap">{fmtDate(t.createdAt)}</TD>
                    <TD className="mono">{t.fromIban ? "..." + t.fromIban.slice(-6) : "DEPOSIT"}</TD>
                    <TD className="mono">{t.toIban ? "..." + t.toIban.slice(-6) : "-"}</TD>
                    <TD className="max-w-48 truncate">{t.memo ?? "-"}</TD>
                    <TD className="text-right font-semibold tabular-nums">{usd(t.amount)}</TD>
                  </TRow>
                ))}
              </tbody>
            </Table>
            <div className="mt-3 flex items-center justify-between text-sm">
              <span className="muted">Page {(page.number ?? 0) + 1} of {Math.max(1, page.totalPages ?? 1)}</span>
              <div className="flex gap-2">
                <Button variant="secondary" size="sm" disabled={index === 0} onClick={() => setIndex((i) => i - 1)}>← Prev</Button>
                <Button variant="secondary" size="sm" disabled={index + 1 >= (page.totalPages ?? 1)} onClick={() => setIndex((i) => i + 1)}>Next →</Button>
              </div>
            </div>
          </>
        )}
      </Card>
    </AppShell>
  );
}
