"use client";

import { Card, CardDescription, CardTitle } from "../../components/ui/card";
import { TD, TH, THead, TRow, Table } from "../../components/ui/table";
import { useAdminTransactions } from "../../lib/queries";
import { fmtDate, usd } from "../../lib/format";

export function ActivitySection() {
  const recent = useAdminTransactions();
  const rows = recent.data ?? [];

  return (
    <Card>
      <CardTitle>Latest transfers</CardTitle>
      {rows.length === 0 ? (
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
                  <TD className="mono">{t.fromIban ? "..." + t.fromIban.slice(-6) : "DEP"} → {t.toIban ? "..." + t.toIban.slice(-6) : "-"}</TD>
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
