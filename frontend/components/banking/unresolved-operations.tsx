"use client";

import * as React from "react";
import { useQueryClient } from "@tanstack/react-query";
import { Badge } from "../ui/badge";
import { Button } from "../ui/button";
import { Card, CardDescription, CardTitle } from "../ui/card";
import { useToast } from "../feedback/toast";
import { fmtDate, maskIban, usd } from "../../lib/format";
import {
  resolveUnresolvedOperation,
  useUnresolvedOperations,
  type UnresolvedOperation
} from "../../lib/queries";
import type { Deposit, Tx } from "../../lib/api-types";

/** One row's stable id (user + kind + key - the store's own namespace). */
function rowId(op: UnresolvedOperation): string {
  return op.userId + ":" + op.kind + ":" + op.key;
}

/** Truthful copy for an operation the server has authoritatively answered. */
function resolvedMessage(op: UnresolvedOperation, status: Tx["status"], data: Tx | Deposit): string {
  const amount = usd(op.amount ?? "");
  if (op.kind === "deposit") {
    // The deposit envelope nests the account it funded; name it when present.
    const account = (data as Deposit).account;
    const to = account ? " to " + account.type + " " + (maskIban(account.iban) ?? "") : "";
    return "Deposit posted. " + amount + " was credited" + to + ".";
  }
  if (status === "HELD") {
    return "Transfer submitted for review. No money has moved yet; an operator must approve it.";
  }
  if (status === "CANCELLED") {
    return "This transfer was cancelled. Nothing moved.";
  }
  const destination = maskIban(op.toIban);
  return "Transfer posted. " + amount + (destination ? " to " + destination : "") + ".";
}

/**
 * The visible resolution surface for interrupted money operations. Renders
 * nothing when this session has no saved operation whose answer never
 * arrived; when it does, each row explains the outcome is unknown, that a
 * retry is safe (the same keyed request can never move money twice), and
 * offers a single resolve action that re-sends the identical request and
 * reports the server's answer. Resolved and definitively-rejected items are
 * cleared from the store (and the money feed/accounts refresh); a still
 * unknown one stays listed with its truthful copy until it is resolved.
 */
export function UnresolvedOperations() {
  const { push } = useToast();
  const qc = useQueryClient();
  const { items } = useUnresolvedOperations();
  const [busy, setBusy] = React.useState<string | null>(null);
  const [notes, setNotes] = React.useState<Record<string, { message: string; tone: "info" | "error" }>>({});

  if (items.length === 0) return null;

  async function resolve(op: UnresolvedOperation) {
    const id = rowId(op);
    setBusy(id);
    // Clear any stale note from a previous attempt at this operation.
    setNotes((n) => {
      if (!(id in n)) return n;
      const next = { ...n };
      delete next[id];
      return next;
    });
    try {
      const outcome = await resolveUnresolvedOperation(qc, op);
      if (outcome.kind === "resolved") {
        push(resolvedMessage(op, outcome.status, outcome.data), "success");
      } else if (outcome.kind === "rejected") {
        // The server recorded nothing, so the record is gone - the rejection
        // must be heard as a toast, not left inline on a disappearing row.
        push(outcome.message, "error");
      } else {
        setNotes((n) => ({ ...n, [id]: { message: outcome.message, tone: "info" } }));
      }
    } finally {
      setBusy(null);
    }
  }

  return (
    <Card className="mt-4">
      <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
        <CardTitle>Unresolved operations</CardTitle>
        <Badge tone="warning">Outcome unknown</Badge>
      </div>
      <CardDescription className="mb-3">
        A deposit or transfer you sent ended without a confirmed answer. Nothing was lost:
        checking it again re-sends the exact same request, so the server returns the original
        result. Money can never move twice.
      </CardDescription>
      <ul className="space-y-2">
        {items.map((op) => {
          const id = rowId(op);
          const note = notes[id];
          const isBusy = busy === id;
          const destination = maskIban(op.toIban);
          return (
            <li
              key={id}
              className="flex flex-wrap items-center justify-between gap-3 rounded-md border border-line bg-ink-800/40 p-3"
            >
              <div className="min-w-0">
                <p className="text-sm font-medium">
                  {op.kind === "deposit" ? "Deposit" : "Transfer"}
                  {" · "}
                  {usd(op.amount ?? "")}
                  {destination ? " to " + destination : ""}
                  {op.memo ? <span className="muted"> · {op.memo}</span> : null}
                </p>
                <p className="muted text-xs">
                  Sent {fmtDate(new Date(op.createdAt).toISOString())} · the answer never arrived
                </p>
                {note ? (
                  <p role="status" className={"mt-1 text-xs " + (note.tone === "error" ? "text-rose" : "text-sky")}>
                    {note.message}
                  </p>
                ) : null}
              </div>
              <Button type="button" variant="secondary" size="sm" disabled={isBusy} onClick={() => void resolve(op)}>
                {isBusy ? "Checking..." : "Check again"}
              </Button>
            </li>
          );
        })}
      </ul>
    </Card>
  );
}
