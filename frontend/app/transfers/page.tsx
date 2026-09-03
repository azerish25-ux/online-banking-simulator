"use client";

import * as React from "react";
import Link from "next/link";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { transferSchema as schema, type TransferForm as Form } from "../../lib/validation";
import { AppShell } from "../../components/layout/app-shell";
import { Badge } from "../../components/ui/badge";
import { Button } from "../../components/ui/button";
import { Card, CardDescription, CardTitle } from "../../components/ui/card";
import { Field, Input } from "../../components/ui/input";
import { useToast } from "../../components/feedback/toast";
import { api } from "../../lib/api";
import { usd } from "../../lib/format";
import { Routes } from "../../lib/routes";
import type { Account, Beneficiary } from "../../lib/api-types";


export default function TransfersPage() {
  const { push } = useToast();
  const [accounts, setAccounts] = React.useState<Account[]>([]);
  const [beneficiaries, setBeneficiaries] = React.useState<Beneficiary[]>([]);
  const [receipt, setReceipt] = React.useState<{ id: string; toIban: string; amount: string } | null>(null);
  const { register, handleSubmit, setValue, watch, reset, formState } = useForm<Form>({
    resolver: zodResolver(schema),
    defaultValues: { fromAccountId: "", toIban: "", amount: "", memo: "" }
  });
  const chosenBeneficiary = watch("toIban");

  React.useEffect(() => {
    api("/v1/accounts").then((accs: Account[]) => {
      setAccounts(accs);
      if (accs.length > 0) setValue("fromAccountId", accs[0].id);
    }).catch((e) => push(e instanceof Error ? e.message : "Failed to load accounts", "error"));
    api("/v1/beneficiaries").then(setBeneficiaries).catch(() => {});
  }, [push, setValue]);

  async function onSubmit(values: Form) {
    setReceipt(null);
    try {
      const data = await api("/v1/transfers", {
        method: "POST",
        headers: { "Idempotency-Key": crypto.randomUUID() },
        body: JSON.stringify({ ...values, memo: values.memo || undefined })
      });
      setReceipt({ id: data.id, toIban: data.toIban, amount: data.amount });
      push("Transfer posted.", "success");
      reset({ fromAccountId: values.fromAccountId, toIban: "", amount: "", memo: "" });
    } catch (err) {
      push(err instanceof Error ? err.message : "Transfer failed", "error");
    }
  }

  return (
    <AppShell>
      <h1 className="text-2xl font-bold tracking-tight">Send money</h1>
      <p className="muted mt-1 text-sm">
        Debited and credited atomically. Retries with the same key never double-send.{" "}
        <Link href={Routes.dashboard} className="text-brand-300 hover:underline">Back to overview</Link>
      </p>

      <div className="mt-4 grid gap-4 md:grid-cols-2">
        <Card className="max-w-xl">
          <form onSubmit={handleSubmit(onSubmit)} className="space-y-4" noValidate>
            <Field label="From account" error={formState.errors.fromAccountId?.message}>
              <select aria-label="From account" {...register("fromAccountId")} className="h-10 w-full rounded-lg border border-line bg-ink-950 px-3 text-sm">
                {accounts.map((a) => (
                  <option key={a.id} value={a.id}>{a.type} ...{a.iban.slice(-6)} · {usd(a.balance)}</option>
                ))}
              </select>
            </Field>
            <Field label="Recipient IBAN" error={formState.errors.toIban?.message}>
              <Input placeholder="DE..." autoComplete="off" {...register("toIban")} />
            </Field>
            <Field label="Amount (USD)" error={formState.errors.amount?.message}>
              <Input placeholder="10.00" inputMode="decimal" {...register("amount")} />
            </Field>
            <Field label="Memo (optional)" error={formState.errors.memo?.message}>
              <Input placeholder="Rent, dinner..." maxLength={140} {...register("memo")} />
            </Field>
            <Button type="submit" disabled={formState.isSubmitting}>
              {formState.isSubmitting ? "Sending..." : "Send transfer"}
            </Button>
          </form>
        </Card>

        <div>
          <Card>
            <CardTitle>Beneficiaries</CardTitle>
            <CardDescription>Tap to fill the recipient.</CardDescription>
            {beneficiaries.length === 0 ? (
              <p className="muted mt-3 text-sm">
                None saved. <Link href={Routes.beneficiaries} className="text-brand-300 hover:underline">Add one →</Link>
              </p>
            ) : (
              <ul className="mt-3 space-y-2">
                {beneficiaries.map((b) => (
                  <li key={b.id}>
                    <button
                      type="button"
                      onClick={() => setValue("toIban", b.iban, { shouldValidate: true })}
                      className={"w-full rounded-lg border p-3 text-left transition-colors hover:bg-ink-700 " + (chosenBeneficiary === b.iban ? "border-brand-500" : "border-line")}
                    >
                      <span className="block text-sm font-medium">{b.nickname}</span>
                      <span className="mono muted">{b.iban}</span>
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </Card>

          {receipt && (
            <Card className="mt-4 border-emerald-800">
              <div className="flex items-center gap-2">
                <CardTitle>Transfer posted</CardTitle>
                <Badge tone="success">POSTED</Badge>
              </div>
              <CardDescription>
                {usd(receipt.amount)} → <span className="mono">{receipt.toIban}</span>
              </CardDescription>
              <p className="mono muted mt-2 text-xs">id {receipt.id}</p>
            </Card>
          )}
        </div>
      </div>
    </AppShell>
  );
}
