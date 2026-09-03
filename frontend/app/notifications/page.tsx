"use client";

import * as React from "react";
import { AppShell } from "../../components/layout/app-shell";
import { Badge } from "../../components/ui/badge";
import { Button } from "../../components/ui/button";
import { Card, CardTitle } from "../../components/ui/card";
import { EmptyState } from "../../components/ui/empty-state";
import { Skeleton } from "../../components/ui/skeleton";
import { useToast } from "../../components/feedback/toast";
import { api } from "../../lib/api";
import type { NotificationItem } from "../../lib/api-types";
import { fmtDate } from "../../lib/format";


export default function NotificationsPage() {
  const { push } = useToast();
  const [items, setItems] = React.useState<NotificationItem[] | null>(null);

  const load = React.useCallback(async () => {
    const page = await api("/v1/notifications?size=30");
    setItems(page.content ?? []);
  }, []);

  React.useEffect(() => {
    load().catch((e) => push(e instanceof Error ? e.message : "Failed to load notifications", "error"));
  }, [load, push]);

  async function markAllRead() {
    if (!items) return;
    try {
      await Promise.all(items.filter((n) => !n.read).map((n) => api("/v1/notifications/" + n.id + "/read", { method: "POST" })));
      push("All caught up.", "success");
      await load();
    } catch (e) {
      push(e instanceof Error ? e.message : "Could not mark all read", "error");
    }
  }

  async function markOne(id: string) {
    try {
      await api("/v1/notifications/" + id + "/read", { method: "POST" });
      await load();
    } catch (e) {
      push(e instanceof Error ? e.message : "Could not mark read", "error");
    }
  }

  return (
    <AppShell>
      <div className="mb-4 flex items-end justify-between">
        <div>
          <h1 className="text-2xl font-bold tracking-tight">Notifications</h1>
          <p className="muted text-sm">Money movement, cards and account events.</p>
        </div>
        <Button variant="secondary" onClick={markAllRead}>Mark all read</Button>
      </div>
      {items == null ? (
        <div className="space-y-2"><Skeleton className="h-16" /><Skeleton className="h-16" /></div>
      ) : items.length === 0 ? (
        <EmptyState title="All quiet" description="Transfers and interest post here." />
      ) : (
        <Card>
          <ul className="divide-y divide-line">
            {items.map((n) => (
              <li key={n.id} className="flex items-start justify-between gap-3 py-3 first:pt-0 last:pb-0">
                <div>
                  <p className="text-sm">
                    {!n.read && <span className="mr-2 inline-block h-2 w-2 rounded-full bg-brand-400" aria-label="unread" />}
                    <strong>{n.title}</strong>
                  </p>
                  <p className="muted text-sm">{n.body}</p>
                  <p className="muted mt-1 text-xs"><Badge tone="neutral">{n.type}</Badge> · {fmtDate(n.createdAt)}</p>
                </div>
                {!n.read && <Button size="sm" variant="ghost" onClick={() => markOne(n.id)}>Mark read</Button>}
              </li>
            ))}
          </ul>
        </Card>
      )}
    </AppShell>
  );
}
