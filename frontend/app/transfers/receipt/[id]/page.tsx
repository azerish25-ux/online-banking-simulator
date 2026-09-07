"use client";

import { ArrowLeft } from "lucide-react";
import * as React from "react";
import Link from "next/link";
import { AppShell } from "../../../../components/layout/app-shell";
import { Button } from "../../../../components/ui/button";
import { Card, CardDescription, CardTitle } from "../../../../components/ui/card";
import { EmptyState } from "../../../../components/ui/empty-state";
import { LoadFailed } from "../../../../components/ui/load-failed";
import { Skeleton } from "../../../../components/ui/skeleton";
import { TxStatusBadge } from "../../../../components/ui/tx-status-badge";
import { useTransferReceipt } from "../../../../lib/queries";
import { maskIban, usdReview } from "../../../../lib/format";
import { Routes } from "../../../../lib/routes";

/**
 * Durable, bookmarkable receipt (F11). The URL carries only the operation id;
 * the page fetches the caller's own operation from the authorized lookup
 * (`GET /v1/transfers/{id}`), so a reload - or a HELD→POSTED transition made
 * later - shows the CURRENT authoritative status and posting time, never a
 * component-state snapshot. Unknown or foreign ids answer the same 404.
 */
export default function TransferReceiptPage({ params }: { params: Promise<{ id: string }> }) {
  // Next 15+ pages receive `params` as a Promise - unwrap it before use.
  const { id } = React.use(params);
  const receipt = useTransferReceipt(id);
  // A 404/410 means the operation does not exist or is not the caller's; any
  // other failure is a load problem with a retry (F10).
  const notFound =
    receipt.isError && (receipt.error?.status === 404 || receipt.error?.status === 410);

  const tx = receipt.data;
  const isDeposit = tx?.kind === "DEPOSIT" || (!tx?.fromIban && Boolean(tx?.toIban));

  function fullTime(iso?: string | null): string | null {
    if (!iso) return null;
    const parsed = new Date(iso);
    return Number.isNaN(parsed.getTime())
      ? iso
      : parsed.toLocaleString(undefined, {
          dateStyle: "medium",
          timeStyle: "short"
        });
  }

  return (
    <AppShell>
      <p className="text-sm">
        <Link href={Routes.transfers} className="text-action hover:underline">
          <ArrowLeft size={14} aria-hidden="true" /> Back to transfers
        </Link>
      </p>

      {notFound ? (
        <div className="mt-4">
          <EmptyState
            title="Receipt not found"
            description="This link looks wrong, or the operation belongs to another account. Check the address or go back to your transfers."
          />
        </div>
      ) : receipt.isError ? (
        <div className="mt-4">
          <LoadFailed
            title="Couldn't load this receipt"
            description="The request did not go through. Try again in a moment."
            onRetry={() => receipt.refetch()}
          />
        </div>
      ) : receipt.isLoading || tx == null ? (
        <div className="mt-4 space-y-2"><Skeleton className="h-24" /><Skeleton className="h-40" /></div>
      ) : (
        <Card className="mt-4">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <CardTitle>{isDeposit ? "Deposit receipt" : "Transfer receipt"}</CardTitle>
            <TxStatusBadge status={tx.status} />
          </div>
          <CardDescription className="mt-1">
            Reference <span className="mono">{tx.id}</span>
          </CardDescription>

          <p className="mt-4 text-[32px] leading-10 font-semibold tabular-nums">{usdReview(tx.amount)}</p>

          <dl className="mt-4 space-y-2 border-t border-divider pt-3 text-sm">
            {!isDeposit && tx.fromIban ? (
              <div className="flex justify-between gap-4">
                <dt className="label text-content-secondary">From</dt>
                <dd className="mono text-right">{maskIban(tx.fromIban)}</dd>
              </div>
            ) : null}
            <div className="flex justify-between gap-4">
              <dt className="label text-content-secondary">{isDeposit ? "Deposited to" : "To"}</dt>
              <dd className="mono text-right">{tx.toIban ? maskIban(tx.toIban) : "-"}</dd>
            </div>
            <div className="flex justify-between gap-4">
              <dt className="label text-content-secondary">Memo</dt>
              <dd className="text-right">{tx.memo || "-"}</dd>
            </div>
            <div className="flex justify-between gap-4">
              <dt className="label text-content-secondary">Requested</dt>
              <dd className="text-right">{fullTime(tx.createdAt) ?? "-"}</dd>
            </div>
            <div className="flex justify-between gap-4">
              <dt className="label text-content-secondary">Posted</dt>
              <dd className="text-right">{fullTime(tx.postedAt) ?? "Not yet posted"}</dd>
            </div>
          </dl>

          <p className="mt-4 rounded-md border border-divider bg-surface-subtle px-3 py-2 text-sm">
            {tx.status === "HELD" ? (
              <>This transfer is awaiting operator review. No money has moved yet. Once an
                operator approves it, the posting time above will appear here. If they decline
                it, nothing moves.</>
            ) : tx.status === "CANCELLED" ? (
              <>This transfer was declined and never settled. No money moved.</>
            ) : tx.status === "POSTED" ? (
              <>Settled. The money moved on {fullTime(tx.postedAt) ?? "posting"}.</>
            ) : (
              <>Status “{String(tx.status ?? "unknown")}” is not recognized. Verify before acting.</>
            )}
          </p>

          <div className="mt-4">
            <Link href={Routes.activity}>
              <Button variant="secondary">View in activity</Button>
            </Link>
          </div>
        </Card>
      )}
    </AppShell>
  );
}
