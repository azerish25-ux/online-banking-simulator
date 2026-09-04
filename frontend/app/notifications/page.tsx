"use client";

import { ArrowLeft, ArrowRight } from "lucide-react";
import * as React from "react";
import { AppShell } from "../../components/layout/app-shell";
import { Badge } from "../../components/ui/badge";
import { Button } from "../../components/ui/button";
import { Card, CardTitle } from "../../components/ui/card";
import { EmptyState } from "../../components/ui/empty-state";
import { Skeleton } from "../../components/ui/skeleton";
import { useToast } from "../../components/feedback/toast";
import { useMarkAllRead, useMarkNotificationRead, useNotifications } from "../../lib/queries";
import { fmtDate } from "../../lib/format";

const PAGE_SIZE = 10;

export default function NotificationsPage() {
  const { push } = useToast();
  const [pageIndex, setPageIndex] = React.useState(0);
  const notifications = useNotifications(pageIndex);
  const items = notifications.data?.content ?? [];
  const totalPages = notifications.data?.totalPages ?? 1;
  const markRead = useMarkNotificationRead();
  const markAllRead = useMarkAllRead();

  async function markAll() {
    if (items.length === 0) return;
    try {
      const result = await markAllRead.mutateAsync();
      const marked = result?.marked ?? 0;
      push(marked > 0 ? "All caught up." : "Nothing unread.", "success");
    } catch (e) {
      push(e instanceof Error ? e.message : "Could not mark all read", "error");
    }
  }

  return (
    <AppShell>
      <div className="mb-4 flex items-end justify-between">
        <div>
          <h1 className="text-2xl font-bold tracking-tight">Notifications</h1>
          <p className="muted text-sm">Money movement, cards and account events.</p>
        </div>
        <Button variant="secondary" onClick={markAll} disabled={markAllRead.isPending || items.length === 0}>
          {markAllRead.isPending ? "Marking..." : "Mark all read"}
        </Button>
      </div>
      {notifications.isLoading ? (
        <div className="space-y-2"><Skeleton className="h-16" /><Skeleton className="h-16" /></div>
      ) : items.length === 0 ? (
        <EmptyState title="All quiet" description="Transfers and interest post here." />
      ) : (
        <>
          <Card>
            <ul className="divide-y divide-line">
              {items.map((n) => (
                <li key={n.id} className="flex items-start justify-between gap-3 py-3 first:pt-0 last:pb-0">
                  <div>
                    <p className="text-sm">
                      {!n.read && <span className="mr-2 inline-block h-2 w-2 rounded-full bg-brass-400" aria-label="unread" />}
                      <strong>{n.title}</strong>
                    </p>
                    <p className="muted text-sm">{n.body}</p>
                    <p className="muted mt-1 text-xs"><Badge tone="neutral">{n.type}</Badge> · {fmtDate(n.createdAt)}</p>
                  </div>
                  {!n.read && (
                    <Button size="sm" variant="ghost" disabled={markRead.isPending} onClick={() => markRead.mutate(n.id)}>
                      Mark read
                    </Button>
                  )}
                </li>
              ))}
            </ul>
          </Card>
          <div className="mt-3 flex items-center justify-between text-sm">
            <span className="muted">Page {pageIndex + 1} of {Math.max(1, totalPages)}</span>
            <div className="flex gap-2">
              <Button variant="secondary" size="sm" disabled={pageIndex === 0} onClick={() => setPageIndex((i) => i - 1)}>
                <ArrowLeft size={14} aria-hidden="true" /> Prev
              </Button>
              <Button
                variant="secondary"
                size="sm"
                disabled={pageIndex + 1 >= totalPages}
                onClick={() => setPageIndex((i) => i + 1)}
              >
                Next <ArrowRight size={14} aria-hidden="true" />
              </Button>
            </div>
          </div>
        </>
      )}
    </AppShell>
  );
}
