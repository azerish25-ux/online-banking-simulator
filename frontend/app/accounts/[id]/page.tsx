"use client";

import * as React from "react";
import Link from "next/link";
import { AppShell } from "../../../components/layout/app-shell";
import { Badge } from "../../../components/ui/badge";
import { Button } from "../../../components/ui/button";
import { Card, CardDescription, CardTitle } from "../../../components/ui/card";
import { EmptyState } from "../../../components/ui/empty-state";
import { Skeleton } from "../../../components/ui/skeleton";
import { TD, TH, THead, TRow, Table } from "../../../components/ui/table";
import { useToast } from "../../../components/feedback/toast";
import { api } from "../../../lib/api";
import { fmtDate, usd } from "../../../lib/format";

type Account = { id: string; iban: string; type: string; balance: string; status: string };
type Tx = { id: string; fromIban: string | null; toIban: string | null; amount: string; currency: string; memo: string | null; createdAt: string };
type CardItem = { id: string; last4: string; expMonth: number; expYear: number; status: string };
type Issued = { id: string; pan: string; cvv: string; expMonth: number; expYear: number };

export default function AccountDetailPage({ params }: { params: { id: string } }) {
  const { push } = useToast();
  const [account, setAccount] = React.useState<Account | null>(null);
  const [recent, setRecent] = React.useState<Tx[]>([]);
  const [cards, setCards] = React.useState<CardItem[]>([]);
  const [issued, setIssued] = React.useState<Issued | null>(null);
  const [issuing, setIssuing] = React.useState(false);

  const load = React.useCallback(async () => {
    setAccount(await api("/v1/accounts/" + params.id));
    const page = await api("/v1/transactions?accountId=" + params.id + "&size=8");
    setRecent(page.content ?? []);
    if ((await api("/v1/accounts/" + params.id)).type !== "LOAN") {
      setCards(await api("/v1/accounts/" + params.id + "/cards"));
    }
  }, [params.id]);

  React.useEffect(() => {
    load().catch((e) => push(e instanceof Error ? e.message : "Failed to load account", "error"));
  }, [load, push]);

  async function issueCard() {
    setIssuing(true);
    setIssued(null);
    try {
      const data: Issued = await api("/v1/accounts/" + params.id + "/cards", { method: "POST" });
      setIssued(data);
      push("Virtual card issued. Copy it now - it is never shown again.", "success");
      setCards(await api("/v1/accounts/" + params.id + "/cards"));
    } catch (e) {
      push(e instanceof Error ? e.message : "Could not issue card", "error");
    } finally {
      setIssuing(false);
    }
  }

  async function setCardStatus(card: CardItem, frozen: boolean) {
    try {
      const updated: CardItem = await api(
        "/v1/cards/" + card.id + (frozen ? "/freeze" : "/unfreeze"), { method: "POST" });
      setCards((prev) => prev.map((c) => (c.id === updated.id ? updated : c)));
      push(frozen ? "Card frozen." : "Card active.", "success");
    } catch (e) {
      push(e instanceof Error ? e.message : "Card update failed", "error");
    }
  }

  return (
    <AppShell>
      <p className="text-sm"><Link href="/dashboard" className="text-brand-300 hover:underline">← Overview</Link></p>
      {account == null ? (
        <div className="mt-3 space-y-2"><Skeleton className="h-24" /><Skeleton className="h-40" /></div>
      ) : (
        <>
          <div className="mt-2 flex flex-wrap items-center gap-3">
            <h1 className="mono text-2xl font-bold">{account.iban}</h1>
            <Badge tone="info">{account.type}</Badge>
            <Badge tone={account.status === "ACTIVE" ? "success" : "danger"}>{account.status}</Badge>
          </div>
          <p className="mt-1 text-4xl font-bold tabular-nums">{usd(account.balance)}</p>
          {account.type === "SAVINGS" && <p className="muted text-sm">Earns monthly interest, posted automatically.</p>}
          {account.type === "LOAN" && <p className="muted text-sm">Negative balance is what you owe. Interest accrues monthly while negative.</p>}

          <div className="mt-4 grid gap-4 lg:grid-cols-2">
            <Card>
              <div className="mb-3 flex items-center justify-between">
                <CardTitle>Virtual cards</CardTitle>
                {account.type !== "LOAN" && (
                  <Button size="sm" onClick={issueCard} disabled={issuing}>
                    {issuing ? "Issuing..." : "Issue card"}
                  </Button>
                )}
              </div>
              {issued && (
                <div className="mb-3 rounded-lg border border-emerald-800 bg-emerald-950/40 p-4" role="status">
                  <p className="text-sm font-medium text-mint">Copy now - shown only once.</p>
                  <p className="mono mt-2 text-xl tracking-widest">{issued.pan}</p>
                  <p className="mono muted text-sm">CVV {issued.cvv} · Exp {issued.expMonth}/{issued.expYear}</p>
                </div>
              )}
              {cards.length === 0 ? (
                <CardDescription>No cards on this account yet.</CardDescription>
              ) : (
                <ul className="space-y-2">
                  {cards.map((c) => (
                    <li key={c.id} className="flex items-center justify-between rounded-lg border border-line p-3">
                      <div>
                        <p className="mono">•••• •••• •••• {c.last4}</p>
                        <p className="muted text-xs">Exp {c.expMonth}/{c.expYear} · <Badge tone={c.status === "ACTIVE" ? "success" : "danger"}>{c.status}</Badge></p>
                      </div>
                      {c.status === "ACTIVE" ? (
                        <Button size="sm" variant="secondary" onClick={() => setCardStatus(c, true)}>Freeze</Button>
                      ) : (
                        <Button size="sm" variant="secondary" onClick={() => setCardStatus(c, false)}>Unfreeze</Button>
                      )}
                    </li>
                  ))}
                </ul>
              )}
            </Card>

            <Card>
              <CardTitle>Recent activity</CardTitle>
              {recent.length === 0 ? (
                <CardDescription>No transactions yet.</CardDescription>
              ) : (
                <div className="mt-3">
                  <Table>
                    <THead><TRow><TH>When</TH><TH>Memo</TH><TH className="text-right">Amount</TH></TRow></THead>
                    <tbody>
                      {recent.map((t) => (
                        <TRow key={t.id}>
                          <TD className="whitespace-nowrap">{fmtDate(t.createdAt)}</TD>
                          <TD className="max-w-40 truncate">{t.memo ?? (t.fromIban ? "Transfer" : "Deposit")}</TD>
                          <TD className="text-right font-semibold tabular-nums">{usd(t.amount)}</TD>
                        </TRow>
                      ))}
                    </tbody>
                  </Table>
                </div>
              )}
            </Card>
          </div>
        </>
      )}
    </AppShell>
  );
}
