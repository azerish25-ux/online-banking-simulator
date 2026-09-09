"use client";

import { Card, CardTitle } from "../../components/ui/card";
import { LoadFailed } from "../../components/ui/load-failed";
import { Skeleton } from "../../components/ui/skeleton";
import { TD, TH, THead, TRow, Table } from "../../components/ui/table";
import { useAdminDailyTotals } from "../../lib/queries";
import { usd } from "../../lib/format";

export function TotalsSection() {
  const totals = useAdminDailyTotals();
  const rows = totals.data ?? [];

  return (
    <Card>
      <CardTitle>Daily totals · last 14 days</CardTitle>
      <div className="mt-3">
        {totals.isError && totals.data == null ? (
          <LoadFailed
            title="Couldn't load daily totals"
            onRetry={() => totals.refetch()}
          />
        ) : totals.isLoading && totals.data == null ? (
          <div className="space-y-2"><Skeleton className="h-8" /><Skeleton className="h-8" /></div>
        ) : (
          <Table>
            <THead>
              <TRow>
                <TH>Date</TH>
                <TH className="text-right">Transfers</TH>
                <TH className="text-right">Volume</TH>
                <TH className="text-right">Deposits</TH>
              </TRow>
            </THead>
            <tbody>
              {rows.slice(-14).map((d) => (
                <TRow key={d.date}>
                  <TD className="whitespace-nowrap">{d.date}</TD>
                  <TD className="nums text-right">{d.transfers}</TD>
                  <TD className="nums text-right">{usd(d.transferVolume)}</TD>
                  <TD className="nums text-right">{d.deposits}</TD>
                </TRow>
              ))}
            </tbody>
          </Table>
        )}
      </div>
    </Card>
  );
}
