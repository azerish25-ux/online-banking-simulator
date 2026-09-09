import Link from "next/link";
import { ArrowLeft, ArrowRight, Bell, Download, Plus, Search, ShieldCheck, X } from "lucide-react";
import { BrandName } from "../../lib/brand";
import { Routes } from "../../lib/routes";
import { Badge } from "../../components/ui/badge";
import { Button } from "../../components/ui/button";
import { Card, CardBody, CardHead, CardTitle } from "../../components/ui/card";
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
 *
 * The system's stance: a controlled financial record, not a marketing
 * surface. Sections are square, flush and divided by rules; figures set
 * themselves in tabular numerals; color states are notation, not decoration.
 */
export default function DesignPage() {
  return (
    <div className="min-h-screen bg-workspace">
      <div className="mx-auto max-w-5xl px-5 py-12">
        <p className="text-sm">
          <Link href={Routes.home} className="text-action hover:underline">
            <ArrowLeft size={14} aria-hidden="true" className="inline" /> {BrandName}
          </Link>
        </p>
        <h1 className="mt-2 text-xl leading-7">Design system</h1>
        <p className="muted mt-1 text-sm">The primitives used across the app, kept as one source of truth for the UI.</p>

        <div className="mt-6 grid gap-4 md:grid-cols-2">
          <Card>
            <CardHead>
              <CardTitle>Buttons</CardTitle>
            </CardHead>
            <CardBody>
              <div className="flex flex-wrap gap-2">
                <Button>Primary</Button>
                <Button variant="secondary">Secondary</Button>
                <Button variant="ghost">Ghost</Button>
                <Button variant="danger">Danger</Button>
                <Button size="sm">Small</Button>
              </div>
            </CardBody>
          </Card>
          <Card>
            <CardHead>
              <CardTitle>Status notation</CardTitle>
            </CardHead>
            <CardBody>
              <div className="flex flex-wrap items-center gap-2">
                <Badge tone="info">Pending</Badge>
                <Badge tone="warning">Awaiting review</Badge>
                <Badge tone="danger">Cancelled</Badge>
                <Badge tone="success">Credited</Badge>
                <span className="text-sm text-content-secondary">Posted (plain text)</span>
              </div>
              <p className="muted mt-2 text-xs">
                A settled state is plain text; only states needing attention get a
                token. A ledger marks exceptions - it does not decorate every row.
              </p>
            </CardBody>
          </Card>
          <Card>
            <CardHead>
              <CardTitle>Fields</CardTitle>
            </CardHead>
            <CardBody>
              <div className="space-y-3">
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
            </CardBody>
          </Card>
          <Card>
            <CardHead>
              <CardTitle>Figures</CardTitle>
            </CardHead>
            <CardBody>
              <p className="nums text-[28px] leading-9 font-semibold">{usd("1234.5")}</p>
              <p className="mono muted mt-1 text-xs">.nums - tabular numerals on every money figure</p>
            </CardBody>
          </Card>
        </div>

        <Card className="mt-4">
          <CardHead>
            <CardTitle>Ledger</CardTitle>
          </CardHead>
          <Table>
            <THead>
              <TRow><TH>When</TH><TH>Memo</TH><TH className="text-right">Amount</TH></TRow>
            </THead>
            <tbody>
              <TRow><TD>Sep 2, 5:30 PM</TD><TD>Seed transfer</TD><TD className="nums text-right font-semibold">{usd("250")}</TD></TRow>
              <TRow><TD>Sep 2, 5:31 PM</TD><TD>Rent</TD><TD className="nums text-right font-semibold">{usd("120")}</TD></TRow>
            </tbody>
          </Table>
        </Card>

        <div className="mt-4 grid gap-4 md:grid-cols-2">
          <Card>
            <CardHead>
              <CardTitle>Skeletons</CardTitle>
            </CardHead>
            <CardBody>
              <div className="space-y-2">
                <Skeleton className="h-6 w-2/3" />
                <Skeleton className="h-6 w-1/2" />
              </div>
            </CardBody>
          </Card>
          <Card>
            <CardHead>
              <CardTitle>Icons</CardTitle>
            </CardHead>
            <CardBody>
              <div className="flex flex-wrap items-center gap-4 text-content-secondary">
                <Bell size={18} aria-hidden="true" />
                <X size={18} aria-hidden="true" />
                <ArrowLeft size={18} aria-hidden="true" />
                <ArrowRight size={18} aria-hidden="true" />
                <Plus size={18} aria-hidden="true" />
                <Download size={18} aria-hidden="true" />
                <Search size={18} aria-hidden="true" />
                <ShieldCheck size={18} aria-hidden="true" />
              </div>
            </CardBody>
          </Card>
          <Card>
            <CardHead>
              <CardTitle>Empty state</CardTitle>
            </CardHead>
            <CardBody>
              <EmptyState title="Nothing here yet" description="Empty states explain what to do next." />
            </CardBody>
          </Card>
        </div>

        <div className="mt-4 grid gap-4 md:grid-cols-2">
          <Card>
            <CardHead>
              <CardTitle>Color tokens</CardTitle>
            </CardHead>
            <CardBody>
              <p className="muted text-sm">
                Every color a view can name comes from tailwind.config.ts. Default
                Tailwind hues and raw hex literals are banned by
                <span className="mono"> scripts/check-design-tokens.mjs</span>.
              </p>
              <div className="mt-3 grid grid-cols-2 gap-x-4 gap-y-2">
                <Swatch className="bg-surface-subtle" name="surface / wells" />
                <Swatch className="bg-action" name="action / primary" />
                <Swatch className="bg-content" name="content / primary text" />
                <Swatch className="bg-content-secondary" name="content / secondary" />
                <Swatch className="bg-success-surface border-success-border" name="success surface" />
                <Swatch className="bg-danger-surface border-danger-border" name="danger surface" />
                <Swatch className="bg-warning-surface border-warning-border" name="warning surface" />
                <Swatch className="bg-info-surface border-info-border" name="info surface" />
              </div>
            </CardBody>
          </Card>
          <Card>
            <CardHead>
              <CardTitle>Motion</CardTitle>
            </CardHead>
            <CardBody>
              <ul className="space-y-2 text-sm">
                <li>Overlay fades in over 160 ms; dialog rises 6 px over 180 ms.</li>
                <li>Toasts slide up over 200 ms and can be dismissed by hand.</li>
                <li>Skeleton pulse is a motion cue, so it respects
                  <span className="mono"> prefers-reduced-motion</span>.</li>
              </ul>
              <p className="muted mt-3 text-xs">
                Every animation is applied with motion-safe variants, so nothing
                moves for users who ask not to see motion.
              </p>
            </CardBody>
          </Card>
        </div>

        <Card className="mt-4">
          <CardHead>
            <CardTitle>Voice</CardTitle>
          </CardHead>
          <CardBody>
            <ul className="grid list-disc gap-2 pl-5 text-sm md:grid-cols-2">
              <li className="muted">Product copy reads like a bank&rsquo;s: deposits,
                review desk, statements. No &ldquo;idempotent&rdquo; or
                &ldquo;atomic&rdquo; on customer screens.</li>
              <li className="muted">The machinery lives behind the &ldquo;About this
                demo&rdquo; seam and here in this gallery.</li>
              <li className="muted">State a fact, not a promise: balances say what
                they are, holds say what happens next.</li>
              <li className="muted">If a demo quirk matters (funding is simulated,
                recipients must open an account here), it is said out loud where
                the user will trip on it.</li>
            </ul>
          </CardBody>
        </Card>
      </div>
    </div>
  );
}

function Swatch({ className, name }: { className: string; name: string }) {
  return (
    <div className="flex items-center gap-2 text-sm">
      <span className={"h-6 w-10 shrink-0 border border-divider " + className} aria-hidden="true" />
      <span className="muted">{name}</span>
    </div>
  );
}
