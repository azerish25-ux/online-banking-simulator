"use client";

import * as React from "react";

import Link from "next/link";
import { useForm } from "react-hook-form";
import { z } from "zod";
import { zodResolver } from "@hookform/resolvers/zod";
import { AppShell } from "../../components/layout/app-shell";
import { Badge } from "../../components/ui/badge";
import { Button } from "../../components/ui/button";
import { Card, CardDescription, CardTitle } from "../../components/ui/card";
import { Field, Input } from "../../components/ui/input";
import { useToast } from "../../components/feedback/toast";
import { api } from "../../lib/api";
import { usd } from "../../lib/format";

const schema = z.object({
  toIban: z.string().trim().min(8, "Enter the full recipient IBAN").max(34),
  amount: z.string().regex(/^\d+(\.\d{1,4})?$/, "Positive amount, up to 4 decimals"),
  memo: z.string().max(140, "Max 140 characters").optional()
});

type Form = z.infer<typeof schema>;

export default function TransfersPage() {
  const { push } = useToast();
  const { register, handleSubmit, reset, formState } = useForm<Form>({ resolver: zodResolver(schema) });
  const [receipt, setReceipt] = React.useState<{ id: string; toIban: string; amount: string } | null>(null);

  async function onSubmit(values: Form) {
    setReceipt(null);
    try {
      const data = await api("/v1/transfers", {
        method: "POST",
        headers: { "Idempotency-Key": crypto.randomUUID() },
        body: JSON.stringify({ toIban: values.toIban, amount: values.amount, memo: values.memo || undefined })
      });
      setReceipt({ id: data.id, toIban: data.toIban, amount: data.amount });
      push("Transfer posted.", "success");
      reset({ toIban: "", amount: "", memo: "" });
    } catch (err) {
      push(err instanceof Error ? err.message : "Transfer failed", "error");
    }
  }

  return (
    <AppShell>
      <h1 className="text-2xl font-bold tracking-tight">Send money</h1>
      <p className="muted mt-1 text-sm">
        Debited and credited atomically. Retries with the same key never double-send.{" "}
        <Link href="/dashboard" className="text-brand-300 hover:underline">Back to overview</Link>
      </p>

      <Card className="mt-4 max-w-xl">
        <form onSubmit={handleSubmit(onSubmit)} className="space-y-4" noValidate>
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

      {receipt && (
        <Card className="mt-4 max-w-xl border-emerald-800">
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
    </AppShell>
  );
}

