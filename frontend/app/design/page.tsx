import { AppShell } from "../../components/layout/app-shell";
import { Badge } from "../../components/ui/badge";
import { Button } from "../../components/ui/button";
import { Card, CardDescription, CardTitle } from "../../components/ui/card";
import { EmptyState } from "../../components/ui/empty-state";
import { Field, Input } from "../../components/ui/input";
import { Skeleton } from "../../components/ui/skeleton";
import { TD, TH, THead, TRow, Table } from "../../components/ui/table";
import { usd } from "../../lib/format";

export default function DesignPage() {
  return (
    <AppShell>
      <h1 className="text-2xl font-bold tracking-tight">Design system</h1>
      <p className="muted mt-1 text-sm">Primitives used across the app. One source of truth for UI.</p>

      <div className="mt-4 grid gap-4 md:grid-cols-2">
        <Card>
          <CardTitle>Buttons</CardTitle>
          <CardDescription>Variants and sizes.</CardDescription>
          <div className="mt-3 flex flex-wrap gap-2">
            <Button>Primary</Button>
            <Button variant="secondary">Secondary</Button>
            <Button variant="ghost">Ghost</Button>
            <Button variant="danger">Danger</Button>
            <Button size="sm">Small</Button>
          </div>
        </Card>
        <Card>
          <CardTitle>Badges</CardTitle>
          <CardDescription>Status tones.</CardDescription>
          <div className="mt-3 flex flex-wrap gap-2">
            <Badge tone="info">PENDING</Badge>
            <Badge tone="success">POSTED</Badge>
            <Badge tone="danger">FAILED</Badge>
            <Badge tone="neutral">DRAFT</Badge>
          </div>
        </Card>
        <Card>
          <CardTitle>Fields</CardTitle>
          <CardDescription>Label, input, hint and error states.</CardDescription>
          <div className="mt-3 space-y-3">
            <Field label="Account nickname" hint="Optional, up to 40 characters.">
              <Input placeholder="Holiday fund" />
            </Field>
            <Field label="Amount" error="Enter a positive amount.">
              <Input placeholder="0.00" />
            </Field>
          </div>
        </Card>
        <Card>
          <CardTitle>Money</CardTitle>
          <CardDescription>Single formatter, no float math in views.</CardDescription>
          <p className="mt-3 text-3xl font-bold tabular-nums">{usd("1234.5")}</p>
          <p className="mono muted mt-1 text-xs">usd(balance: string)</p>
        </Card>
      </div>

      <Card className="mt-4">
        <CardTitle>Table</CardTitle>
        <CardDescription>Transaction rows.</CardDescription>
        <div className="mt-3">
          <Table>
            <THead>
              <TRow><TH>When</TH><TH>Memo</TH><TH className="text-right">Amount</TH></TRow>
            </THead>
            <tbody>
              <TRow><TD>Sep 2, 5:30 PM</TD><TD>Seed transfer</TD><TD className="text-right font-semibold tabular-nums">{usd("250")}</TD></TRow>
              <TRow><TD>Sep 2, 5:31 PM</TD><TD>Rent</TD><TD className="text-right font-semibold tabular-nums">{usd("120")}</TD></TRow>
            </tbody>
          </Table>
        </div>
      </Card>

      <div className="mt-4 grid gap-4 md:grid-cols-2">
        <Card>
          <CardTitle>Skeletons</CardTitle>
          <CardDescription>Loading placeholders.</CardDescription>
          <div className="mt-3 space-y-2">
            <Skeleton className="h-6 w-2/3" />
            <Skeleton className="h-6 w-1/2" />
          </div>
        </Card>
        <EmptyState title="Nothing here yet" description="Empty states explain what to do next." />
      </div>
    </AppShell>
  );
}
