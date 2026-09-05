"use client";

import { ArrowLeft, ArrowRight } from "lucide-react";
import * as React from "react";
import Link from "next/link";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { transferSchema as schema, type TransferForm as Form } from "../../lib/validation";
import { AppShell } from "../../components/layout/app-shell";
import { Button } from "../../components/ui/button";
import { Card, CardDescription, CardTitle } from "../../components/ui/card";
import { Field, Input } from "../../components/ui/input";
import { Select } from "../../components/ui/select";
import { useResultToast } from "../../components/feedback/use-result-toast";
import { TxStatusBadge } from "../../components/ui/tx-status-badge";
import { useAccounts, useBeneficiaries, useTransfer, type TransferInput } from "../../lib/queries";
import { accountLabel, usd } from "../../lib/format";
import { Routes } from "../../lib/routes";

export default function TransfersPage() {
  const accounts = useAccounts();
  const beneficiaries = useBeneficiaries();
  const transfer = useTransfer();
  const [receipt, setReceipt] = React.useState<{ id: string; toIban: string; amount: string; status: string } | null>(null);
  const { register, handleSubmit, setValue, watch, reset, formState } = useForm<Form>({
    resolver: zodResolver(schema),
    defaultValues: { fromAccountId: "", toIban: "", amount: "", memo: "" }
  });
  const watchFrom = watch("fromAccountId");
  const watchTo = watch("toIban");
  const watchAmount = watch("amount");
  const chosenBeneficiary = watchTo;
  const { resetIdempotencyKey } = transfer;

  // Editing the transfer is a new intent: the outstanding idempotency key
  // (which exists to make retries of THIS transfer safe) no longer applies.
  const lastIntent = React.useRef({ from: "", to: "", amount: "" });
  React.useEffect(() => {
    const next = { from: watchFrom, to: watchTo, amount: watchAmount };
    const prev = lastIntent.current;
    if (prev.from !== next.from || prev.to !== next.to || prev.amount !== next.amount) {
      lastIntent.current = next;
      resetIdempotencyKey();
    }
  }, [watchFrom, watchTo, watchAmount, resetIdempotencyKey]);

  // Default the source account once, when accounts first arrive. Deliberately
  // not on every refetch: invalidation-driven refetches (e.g. after a transfer
  // or deposit elsewhere) would otherwise overwrite the account the user chose
  // - or the one kept after a successful send.
  const sourceInitialized = React.useRef(false);
  React.useEffect(() => {
    const accs = accounts.data;
    if (!sourceInitialized.current && accs && accs.length > 0) {
      sourceInitialized.current = true;
      setValue("fromAccountId", accs[0].id);
    }
  }, [accounts.data, setValue]);

  // Result → toast wiring lives in the shared owner (the transfer's failure
  // surfaces its own message by default). Success keeps the source account
  // and clears the rest for the next transfer; the owner fires only on the
  // settle transition and reads the current form through `lastIntent`, so a
  // refetch after the send can never replay the receipt or the toast.
  useResultToast(beneficiaries, {
    error: { message: "Couldn't load your beneficiaries - type the IBAN manually." }
  });
  useResultToast(transfer, {
    success: {
      toast: (d) =>
        d.status === "HELD"
          ? { message: "Transfer submitted for review - it is sent once an operator approves it.", tone: "info" }
          : { message: "Transfer posted." },
      run: (d) => {
        setReceipt({ id: d.id, toIban: d.toIban, amount: d.amount, status: d.status });
        reset({ fromAccountId: lastIntent.current.from, toIban: "", amount: "", memo: "" });
      }
    }
  });

  function onSubmit(values: Form) {
    setReceipt(null);
    transfer.mutate(values as TransferInput);
  }

  return (
    <AppShell>
      <p className="text-sm">
        <Link href={Routes.dashboard} className="text-brass-300 hover:underline">
          <ArrowLeft size={14} aria-hidden="true" /> Back to overview
        </Link>
      </p>
      <h1 className="text-2xl font-bold tracking-tight">Send money</h1>
      <p className="muted mt-1 max-w-2xl text-sm">
        Transfers post right away. Amounts of $10,000 or more go to the review
        desk first - nothing leaves your account until an operator approves them.
      </p>

      <div className="mt-4 grid gap-4 md:grid-cols-2">
        <Card className="max-w-xl">
          <form onSubmit={handleSubmit(onSubmit)} className="space-y-4" noValidate>
            <Field label="From account" error={formState.errors.fromAccountId?.message}>
              <Select aria-label="From account" {...register("fromAccountId")}>
                {(accounts.data ?? []).map((a) => (
                  <option key={a.id} value={a.id}>{accountLabel(a, a.balance)}</option>
                ))}
              </Select>
            </Field>
            <Field
              label="Recipient IBAN"
              error={formState.errors.toIban?.message}
              hint="The recipient needs an account opened here - add a beneficiary to fill it in one tap."
            >
              <Input placeholder="DE..." autoComplete="off" {...register("toIban")} />
            </Field>
            <Field
              label="Amount (USD)"
              error={formState.errors.amount?.message}
              hint="Transfers of $10,000 or more are held for review - no money moves until an operator approves."
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
            <Card className="mt-4 border-success-border">
              <div className="flex items-center gap-2">
                <CardTitle>{receipt.status === "HELD" ? "Transfer submitted for review" : "Transfer posted"}</CardTitle>
                <TxStatusBadge status={receipt.status} />
              </div>
              <CardDescription>
                {usd(receipt.amount)} → <span className="mono">{receipt.toIban}</span>
              </CardDescription>
              {receipt.status === "HELD" && (
                <p className="muted mt-2 text-sm">
                  No money has moved yet - the transfer is queued for operator review.
                </p>
              )}
              <p className="mono muted mt-2 text-xs">id {receipt.id}</p>
            </Card>
          )}
        </div>
      </div>
    </AppShell>
  );
}
