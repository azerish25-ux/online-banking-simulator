"use client";

import { Card, CardTitle } from "../../components/ui/card";
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
        {totals.isLoading ? (
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
                  <TD className="text-right tabular-nums">{d.transfers}</TD>
                  <TD className="text-right tabular-nums">{usd(d.transferVolume)}</TD>
                  <TD className="text-right tabular-nums">{d.deposits}</TD>
                </TRow>
              ))}
            </tbody>
          </Table>
        )}
      </div>
    </Card>
  );
}
