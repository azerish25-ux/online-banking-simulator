"use client";

import { ArrowLeft, ArrowRight } from "lucide-react";
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
import { useAccounts, useBeneficiaries, useTransfer, type TransferInput } from "../../lib/queries";
import { usd } from "../../lib/format";
import { Routes } from "../../lib/routes";


export default function TransfersPage() {
  const { push } = useToast();
  const accounts = useAccounts();
  const beneficiaries = useBeneficiaries();
  const transfer = useTransfer();
  const [receipt, setReceipt] = React.useState<{ id: string; toIban: string; amount: string; flagged: boolean } | null>(null);
  const { register, handleSubmit, setValue, watch, reset, formState } = useForm<Form>({
    resolver: zodResolver(schema),
    defaultValues: { fromAccountId: "", toIban: "", amount: "", memo: "" }
  });
  const chosenBeneficiary = watch("toIban");

  // Default the source account once accounts arrive; beneficiaries load
  // failures surface as a hint instead of vanishing silently.
  React.useEffect(() => {
    const accs = accounts.data;
    if (accs && accs.length > 0) setValue("fromAccountId", accs[0].id);
  }, [accounts.data, setValue]);

  React.useEffect(() => {
    if (beneficiaries.isError) push("Couldn't load your beneficiaries - type the IBAN manually.", "error");
  }, [beneficiaries.isError, push]);

  React.useEffect(() => {
    if (transfer.isSuccess && transfer.data) {
      const d = transfer.data;
      setReceipt({ id: d.id, toIban: d.toIban, amount: d.amount, flagged: Boolean(d.flagged) });
      push("Transfer posted.", "success");
      reset({ fromAccountId: d.id ? watch("fromAccountId") : "", toIban: "", amount: "", memo: "" });
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [transfer.isSuccess]);

  React.useEffect(() => {
    if (transfer.isError) push(transfer.error.message, "error");
  }, [transfer.isError, transfer.error, push]);

  function onSubmit(values: Form) {
    setReceipt(null);
    transfer.mutate(values as TransferInput);
  }

  return (
    <AppShell>
      <h1 className="text-2xl font-bold tracking-tight">Send money</h1>
      <p className="muted mt-1 text-sm">
        Debited and credited atomically. Retries with the same key never double-send.{" "}
        <Link href={Routes.dashboard} className="text-brass-300 hover:underline"><ArrowLeft size={14} aria-hidden="true" /> Back to overview</Link>
      </p>

      <div className="mt-4 grid gap-4 md:grid-cols-2">
        <Card className="max-w-xl">
          <form onSubmit={handleSubmit(onSubmit)} className="space-y-4" noValidate>
            <Field label="From account" error={formState.errors.fromAccountId?.message}>
              <select aria-label="From account" {...register("fromAccountId")} className="h-10 w-full rounded-md border border-line bg-ink-950/70 px-3 text-sm focus:border-brass-500 focus:outline-none">
                {(accounts.data ?? []).map((a) => (
                  <option key={a.id} value={a.id}>{a.type} ...{a.iban.slice(-6)} · {usd(a.balance)}</option>
                ))}
              </select>
            </Field>
            <Field label="Recipient IBAN" error={formState.errors.toIban?.message}>
              <Input placeholder="DE..." autoComplete="off" {...register("toIban")} />
            </Field>
            <Field
              label="Amount (USD)"
              error={formState.errors.amount?.message}
              hint="Transfers of $10,000 or more are held for operator review."
            >
              <Input placeholder="10.00" inputMode="decimal" {...register("amount")} />
            </Field>
            <Field label="Memo (optional)" error={formState.errors.memo?.message}>
              <Input placeholder="Rent, dinner..." maxLength={140} {...register("memo")} />
            </Field>
            <Button type="submit" disabled={transfer.isPending}>
              {transfer.isPending ? "Sending..." : "Send transfer"}
            </Button>
          </form>
        </Card>

        <div>
          <Card>
            <CardTitle>Beneficiaries</CardTitle>
            <CardDescription>Tap to fill the recipient.</CardDescription>
            {(beneficiaries.data ?? []).length === 0 ? (
              <p className="muted mt-3 text-sm">
                None saved. <Link href={Routes.beneficiaries} className="text-brass-300 hover:underline">Add one <ArrowRight size={14} aria-hidden="true" /></Link>
              </p>
            ) : (
              <ul className="mt-3 space-y-2">
                {(beneficiaries.data ?? []).map((b) => (
                  <li key={b.id}>
                    <button
                      type="button"
                      onClick={() => setValue("toIban", b.iban, { shouldValidate: true })}
                      className={"w-full rounded-md border p-3 text-left transition-colors hover:bg-ink-700 " + (chosenBeneficiary === b.iban ? "border-brass-500" : "border-line")}
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
            <Card className="mt-4 border-emerald-900/60">
              <div className="flex items-center gap-2">
                <CardTitle>Transfer posted</CardTitle>
                {receipt.flagged ? <Badge tone="warning">UNDER REVIEW</Badge> : <Badge tone="success">POSTED</Badge>}
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
