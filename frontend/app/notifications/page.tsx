"use client";

import * as React from "react";
import { AppShell } from "../../components/layout/app-shell";
import { Button } from "../../components/ui/button";
import { Card } from "../../components/ui/card";
import { Pager } from "../../components/ui/pager";
import { EmptyState } from "../../components/ui/empty-state";
import { LoadFailed } from "../../components/ui/load-failed";
import { Skeleton } from "../../components/ui/skeleton";
import { useToast } from "../../components/feedback/toast";
import { useMarkAllRead, useMarkNotificationRead, useNotifications } from "../../lib/queries";
import { fmtDate } from "../../lib/format";

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
          <h1 className="text-xl leading-7">Notifications</h1>
          <p className="muted text-sm">Money movement, cards and account events.</p>
        </div>
        <Button variant="secondary" onClick={markAll} disabled={markAllRead.isPending || items.length === 0}>
          {markAllRead.isPending ? "Marking..." : "Mark all read"}
        </Button>
      </div>
      {notifications.isError && notifications.data == null ? (
        <LoadFailed
          title="Couldn't load notifications"
          description="Nothing changed on your side. The request failed. Try again."
          onRetry={() => notifications.refetch()}
        />
      ) : notifications.isLoading && notifications.data == null ? (
        <div className="space-y-2"><Skeleton className="h-16" /><Skeleton className="h-16" /></div>
      ) : items.length === 0 ? (
        <EmptyState title="All quiet" description="Transfers and interest post here." />
      ) : (
        <>
          <Card>
            <ul className="divide-y divide-divider">
              {items.map((n) => (
                <li key={n.id} className="flex items-start justify-between gap-3 py-3 first:pt-0 last:pb-0">
                  <div>
                    <p className="text-sm">
                      {!n.read && <span role="img" aria-label="Unread" className="mr-2 inline-block h-2 w-2 rounded-full bg-danger" />}
                      <strong>{n.title}</strong>
                    </p>
                    <p className="muted text-sm">{n.body}</p>
                    <p className="muted mt-1 text-xs">{n.type} · {fmtDate(n.createdAt)}</p>
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
          <Pager page={pageIndex} totalPages={Math.max(1, totalPages)} onChange={setPageIndex} />
        </>
      )}
    </AppShell>
  );
}
