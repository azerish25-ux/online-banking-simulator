"use client";

import { AppShell } from "../../components/layout/app-shell";
import { EmptyState } from "../../components/ui/empty-state";
import { Skeleton } from "../../components/ui/skeleton";
import { useMe } from "../../lib/queries";
import { AuditSection } from "./audit-section";
import { ReviewQueueSection } from "./review-queue-section";
import { TotalsSection } from "./totals-section";
import { UsersSection } from "./users-section";
import { ActivitySection } from "./activity-section";

/**
 * Operations console. Each section below owns its own queries and state
 * (review queue, totals, customer accounts, recent flow, audit trail); this
 * page only gates on the operator role and lays them out.
 */
export default function AdminPage() {
  const me = useMe();

  if (me.isLoading) {
    return (
      <AppShell>
        <div className="space-y-2"><Skeleton className="h-8" /><Skeleton className="h-72" /></div>
      </AppShell>
    );
  }
  if (me.data?.role !== "ADMIN") {
    return (
      <AppShell>
        <EmptyState title="Admins only" description="Your account does not have the operator role." />
      </AppShell>
    );
  }

  return (
    <AppShell>
      <h1 className="text-2xl font-bold tracking-tight">Operations</h1>
      <p className="muted mt-1 text-sm">Users, account status, money flow and the audit trail.</p>

      <div className="mt-4 grid gap-4 lg:grid-cols-2">
        <ReviewQueueSection />
        <TotalsSection />
      </div>

      <div className="mt-4 grid gap-4 lg:grid-cols-2">
        <UsersSection />
        <div className="space-y-4">
          <ActivitySection />
          <AuditSection />
        </div>
      </div>
    </AppShell>
  );
}
