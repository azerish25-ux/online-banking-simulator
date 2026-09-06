"use client";

import { Card, CardDescription, CardTitle } from "../../components/ui/card";
import { LoadFailed } from "../../components/ui/load-failed";
import { Skeleton } from "../../components/ui/skeleton";
import { TD, TH, THead, TRow, Table } from "../../components/ui/table";
import { useAdminTransactions } from "../../lib/queries";
import { fmtDate, maskIban, usd } from "../../lib/format";

export function ActivitySection() {
  const recent = useAdminTransactions();
  const rows = recent.data ?? [];

  return (
    <Card>
      <CardTitle>Latest transfers</CardTitle>
      {recent.isError && recent.data == null ? (
        <LoadFailed
          title="Couldn't load the transfer feed"
          onRetry={() => recent.refetch()}
        />
      ) : recent.isLoading && recent.data == null ? (
        <div className="mt-3 space-y-2"><Skeleton className="h-8" /><Skeleton className="h-8" /></div>
      ) : rows.length === 0 ? (
        <CardDescription>No transfers yet.</CardDescription>
      ) : (
        <div className="mt-3">
          <Table>
            <THead>
              <TRow>
                <TH>When</TH>
                <TH>Route</TH>
                <TH className="text-right">Amount</TH>
              </TRow>
            </THead>
            <tbody>
              {rows.map((t) => (
                <TRow key={t.id}>
                  <TD className="whitespace-nowrap">{fmtDate(t.createdAt)}</TD>
                  <TD className="mono">{maskIban(t.fromIban) ?? "DEP"} → {maskIban(t.toIban) ?? "-"}</TD>
                  <TD className="text-right font-semibold tabular-nums">{usd(t.amount)}</TD>
                </TRow>
              ))}
            </tbody>
          </Table>
        </div>
      )}
    </Card>
  );
}
