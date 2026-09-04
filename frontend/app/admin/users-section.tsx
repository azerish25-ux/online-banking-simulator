"use client";

import * as React from "react";
import { Badge } from "../../components/ui/badge";
import { Button } from "../../components/ui/button";
import { Card, CardDescription, CardTitle } from "../../components/ui/card";
import { ConfirmDialog } from "../../components/ui/confirm-dialog";
import { Input } from "../../components/ui/input";
import { Skeleton } from "../../components/ui/skeleton";
import { Pager } from "../../components/ui/pager";
import { useToast } from "../../components/feedback/toast";
import { useAdminSetAccountStatus, useAdminUserAccounts, useAdminUsers } from "../../lib/queries";
import type { Account } from "../../lib/api-types";
import { adminStatementUrl } from "../../lib/statements";
import { downloadAuthed } from "../../lib/download";
import { usd } from "../../lib/format";

/**
 * Customer directory: search users, pick one, then inspect or freeze their
 * accounts (freeze needs a confirm - it refuses deposits and transfers).
 * The selected user's accounts are their own cached query, so picking a
 * different user does not refetch the search results.
 */
export function UsersSection() {
  const { push } = useToast();
  const [query, setQuery] = React.useState("");
  const [submittedQuery, setSubmittedQuery] = React.useState("");
  const [usersPage, setUsersPage] = React.useState(0);
  const [selectedId, setSelectedId] = React.useState("");
  const [freezeTarget, setFreezeTarget] = React.useState<{ account: Account; userId: string } | null>(null);

  const usersQuery = useAdminUsers(submittedQuery, usersPage);
  const accounts = useAdminUserAccounts(selectedId);
  const setStatus = useAdminSetAccountStatus();

  const userRows = usersQuery.data?.content ?? [];
  const accountRows = accounts.data ?? [];
  const selected = usersQuery.data?.content.find((u) => u.id === selectedId) ?? null;

  React.useEffect(() => {
    if (setStatus.isSuccess && setStatus.data) {
      push(setStatus.data.status === "FROZEN" ? "Account frozen." : "Account re-activated.", "success");
    }
  }, [setStatus.isSuccess, setStatus.data, push]);

  async function downloadAccountPdf(accountId: string) {
    try {
      await downloadAuthed(adminStatementUrl(accountId), "statement.pdf");
      push("Statement downloaded.", "success");
    } catch (e) {
      push(e instanceof Error ? e.message : "Export failed", "error");
    }
  }

  return (
    <Card>
      <CardTitle>Users</CardTitle>
      <form
        onSubmit={(e) => { e.preventDefault(); setSubmittedQuery(query); setUsersPage(0); }}
        className="mt-3 flex gap-2"
      >
        <Input aria-label="Search users" placeholder="email or name..." value={query} onChange={(e) => setQuery(e.target.value)} />
        <Button type="submit" variant="secondary">Search</Button>
      </form>
      {usersQuery.isLoading ? (
        <div className="mt-3 space-y-2"><Skeleton className="h-10" /><Skeleton className="h-10" /></div>
      ) : (
        <ul className="mt-3 space-y-1">
          {userRows.map((u) => (
            <li key={u.id}>
              <button
                type="button"
                onClick={() => setSelectedId(u.id)}
                className={"flex w-full items-center justify-between rounded-md border p-3 text-left hover:bg-ink-700 " + (selectedId === u.id ? "border-brass-500" : "border-line")}
              >
                <span>
                  <span className="block text-sm font-medium">{u.fullName}</span>
                  <span className="mono muted">{u.email}</span>
                </span>
                <Badge tone={u.role === "ADMIN" ? "info" : "neutral"}>{u.role}</Badge>
              </button>
            </li>
          ))}
        </ul>
      )}
      {userRows.length > 0 && (
        <Pager page={usersPage} totalPages={usersQuery.data?.totalPages ?? 1} onChange={setUsersPage} />
      )}
      {selected && (
        <div className="mt-4 border-t border-line pt-3">
          <CardTitle>Accounts · {selected.fullName}</CardTitle>
          {accountRows.length === 0 && <CardDescription>No accounts.</CardDescription>}
          {accountRows.map((a) => (
            <div key={a.id} className="mt-2 flex items-center justify-between rounded-md border border-line p-3">
              <div>
                <p className="mono text-sm">{a.iban}</p>
                <p className="text-sm">{usd(a.balance)} · <Badge tone={a.status === "ACTIVE" ? "success" : "danger"}>{a.status}</Badge></p>
              </div>
              <div className="flex gap-2">
                <Button size="sm" variant="secondary" onClick={() => downloadAccountPdf(a.id)}>PDF</Button>
                {a.status === "ACTIVE" ? (
                  <Button
                    size="sm"
                    variant="danger"
                    disabled={setStatus.isPending}
                    onClick={() => setFreezeTarget({ account: a, userId: selected.id })}
                  >
                    Freeze
                  </Button>
                ) : (
                  <Button
                    size="sm"
                    variant="secondary"
                    disabled={setStatus.isPending}
                    onClick={() => setStatus.mutate({ account: a, frozen: false, userId: selected.id })}
                  >
                    Unfreeze
                  </Button>
                )}
              </div>
            </div>
          ))}
        </div>
      )}

      <ConfirmDialog
        open={freezeTarget != null}
        title="Freeze this account?"
        confirmLabel="Freeze account"
        busy={setStatus.isPending}
        body={
          freezeTarget ? (
            <>
              <span className="mono">{freezeTarget.account.iban}</span> ({usd(freezeTarget.account.balance)})
              will refuse deposits and transfers until an operator re-activates it.
            </>
          ) : null
        }
        onClose={() => setFreezeTarget(null)}
        onConfirm={() => {
          if (!freezeTarget) return;
          setStatus.mutate(
            { account: freezeTarget.account, frozen: true, userId: freezeTarget.userId },
            { onSuccess: () => setFreezeTarget(null) }
          );
        }}
      />
    </Card>
  );
}
