import Link from "next/link";
import { ArrowLeft, ArrowRight, Bell, Download, Plus, Search, ShieldCheck, X } from "lucide-react";
import { BrandName } from "../../lib/brand";
import { Routes } from "../../lib/routes";
import { Badge } from "../../components/ui/badge";
import { Button } from "../../components/ui/button";
import { Card, CardDescription, CardTitle } from "../../components/ui/card";
import { EmptyState } from "../../components/ui/empty-state";
import { Field, Input } from "../../components/ui/input";
import { PasswordInput } from "../../components/ui/password-input";
import { Skeleton } from "../../components/ui/skeleton";
import { TD, TH, THead, TRow, Table } from "../../components/ui/table";
import { usd } from "../../lib/format";

/**
 * The design system - the same primitives the app is built from, shown on a
 * public page so anonymous visitors can browse it (it needs no session, so
 * it deliberately renders outside the authenticated shell).
 */
export default function DesignPage() {
  return (
    <div className="min-h-screen bg-ink-900">
      <div className="mx-auto max-w-5xl px-5 py-12">
        <p className="text-sm">
          <Link href={Routes.home} className="text-brass-300 hover:underline">
            <ArrowLeft size={14} aria-hidden="true" className="inline" /> {BrandName}
          </Link>
        </p>
        <h1 className="mt-2 text-3xl font-bold tracking-tight">Design system</h1>
        <p className="muted mt-1 text-sm">The primitives used across the app - one source of truth for the UI.</p>

        <div className="mt-6 grid gap-4 md:grid-cols-2">
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
              <Badge tone="warning">UNDER REVIEW</Badge>
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
              <Field label="Password" hint="Toggle visibility with the eye button.">
                <PasswordInput placeholder="••••••••" />
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
          <Card>
            <CardTitle>Icons</CardTitle>
            <CardDescription>The icon set used across the app.</CardDescription>
            <div className="mt-3 flex flex-wrap items-center gap-4 text-slate-300">
              <Bell size={18} aria-hidden="true" />
              <X size={18} aria-hidden="true" />
              <ArrowLeft size={18} aria-hidden="true" />
              <ArrowRight size={18} aria-hidden="true" />
              <Plus size={18} aria-hidden="true" />
              <Download size={18} aria-hidden="true" />
              <Search size={18} aria-hidden="true" />
              <ShieldCheck size={18} aria-hidden="true" />
            </div>
          </Card>
          <Card>
            <CardTitle>Empty state</CardTitle>
            <CardDescription>Explains what to do next when there is no data.</CardDescription>
            <div className="mt-3">
              <EmptyState title="Nothing here yet" description="Empty states explain what to do next." />
            </div>
          </Card>
        </div>
      </div>
    </div>
  );
}
