"use client";

import * as React from "react";
import { Badge } from "../../components/ui/badge";
import { Button } from "../../components/ui/button";
import { Card, CardDescription, CardTitle } from "../../components/ui/card";
import { Field, Input } from "../../components/ui/input";
import { Pager } from "../../components/ui/pager";
import { useAdminAudits } from "../../lib/queries";
import { fmtDate, usd } from "../../lib/format";
import type { Audit } from "../../lib/api-types";

/** Last-six of an IBAN, or "external" when a party is absent. */
function shortIban(iban?: string): string {
  return iban ? "..." + iban.slice(-6) : "external";
}

/**
 * Turns stored audit metadata into one readable line: "$12,000.00 · ...sender
 * → ...recipient", falling back to a generic key: value list for events that
 * carry other context (email, account, month, last4, ...).
 */
function metaSummary(meta: Audit["metadata"]): string | null {
  if (!meta) return null;
  const parts: string[] = [];
  if (meta.amount) parts.push(usd(meta.amount));
  if (meta.from || meta.to) {
    parts.push(
      (meta.from ? shortIban(meta.from) : "external") +
        (meta.from && meta.to ? " → " : "") +
        (meta.to ? shortIban(meta.to) : "")
    );
  }
  for (const key of ["email", "iban", "account", "month", "last4", "type", "transaction"]) {
    if (meta[key]) parts.push(key + ": " + meta[key]);
  }
  return parts.length > 0 ? parts.join(" · ") : null;
}

export function AuditSection() {
  const [draft, setDraft] = React.useState("");
  const [action, setAction] = React.useState("");
  const [page, setPage] = React.useState(0);

  const audits = useAdminAudits(action, page);
  const rows = audits.data?.content ?? [];
  const totalPages = audits.data?.totalPages ?? 1;

  return (
    <Card>
      <div className="flex items-center justify-between gap-2">
        <CardTitle>Audit log</CardTitle>
        <form
          onSubmit={(e) => { e.preventDefault(); setAction(draft.trim().toUpperCase()); setPage(0); }}
          className="flex gap-2"
        >
          <Field label="">
            <Input aria-label="Filter by action" placeholder="TRANSFER_POSTED..." value={draft} onChange={(e) => setDraft(e.target.value)} />
          </Field>
          <Button type="submit" variant="secondary" size="sm">Filter</Button>
        </form>
      </div>
      {rows.length === 0 ? (
        <CardDescription>No audit rows for that filter.</CardDescription>
      ) : (
        <>
          <ul className="mt-3 space-y-1.5 text-sm">
            {rows.map((a) => {
              const meta = metaSummary(a.metadata);
              return (
                <li key={a.id} className="rounded-md border border-line px-3 py-2">
                  <div className="flex items-center justify-between gap-2">
                    <span>
                      <Badge tone="neutral">{a.action}</Badge>{" "}
                      <span className="muted">{a.entity} </span>
                      <span className="mono">{a.entityId.slice(0, 8)}...</span>
                    </span>
                    <span className="muted whitespace-nowrap text-xs">{fmtDate(a.createdAt)}</span>
                  </div>
                  {meta && (
                    <p className="muted mt-1 truncate text-xs" title={meta}>{meta}</p>
                  )}
                </li>
              );
            })}
          </ul>
          <Pager page={page} totalPages={totalPages} onChange={setPage} />
        </>
      )}
    </Card>
  );
}
