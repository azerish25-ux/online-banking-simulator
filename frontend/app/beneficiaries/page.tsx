"use client";

import * as React from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { beneficiarySchema as schema, type BeneficiaryForm as Form } from "../../lib/validation";
import { AppShell } from "../../components/layout/app-shell";
import { Button } from "../../components/ui/button";
import { Card, CardTitle } from "../../components/ui/card";
import { EmptyState } from "../../components/ui/empty-state";
import { Field, Input } from "../../components/ui/input";
import { Modal } from "../../components/ui/modal";
import { Skeleton } from "../../components/ui/skeleton";
import { useToast } from "../../components/feedback/toast";
import { useAddBeneficiary, useBeneficiaries, useRemoveBeneficiary } from "../../lib/queries";
import type { Beneficiary } from "../../lib/api-types";




export default function BeneficiariesPage() {
  const { push } = useToast();
  const beneficiaries = useBeneficiaries();
  const items = beneficiaries.data;
  const add = useAddBeneficiary();
  const removeBeneficiary = useRemoveBeneficiary();
  const [confirm, setConfirm] = React.useState<Beneficiary | null>(null);
  const { register, handleSubmit, reset, formState } = useForm<Form>({ resolver: zodResolver(schema) });

  React.useEffect(() => {
    if (add.isSuccess) {
      push("Beneficiary saved.", "success");
      reset({ nickname: "", iban: "" });
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [add.isSuccess]);

  React.useEffect(() => {
    if (add.isError) push(add.error.message, "error");
  }, [add.isError, add.error, push]);

  React.useEffect(() => {
    if (removeBeneficiary.isSuccess) {
      push("Beneficiary removed.", "success");
      setConfirm(null);
    }
  }, [removeBeneficiary.isSuccess, push]);

  function onSubmit(values: Form) {
    add.mutate(values);
  }

  function remove() {
    if (!confirm) return;
    removeBeneficiary.mutate(confirm.id);
  }

  return (
    <AppShell>
      <h1 className="text-2xl font-bold tracking-tight">Beneficiaries</h1>
      <p className="muted mt-1 text-sm">Your transfer address book.</p>

      <div className="mt-4 grid gap-4 md:grid-cols-2">
        <Card>
          <CardTitle>Add beneficiary</CardTitle>
          <form onSubmit={handleSubmit(onSubmit)} className="mt-3 space-y-4" noValidate>
            <Field label="Nickname" error={formState.errors.nickname?.message}>
              <Input placeholder="Landlord" {...register("nickname")} />
            </Field>
            <Field label="IBAN" error={formState.errors.iban?.message}>
              <Input placeholder="DE..." autoComplete="off" {...register("iban")} />
            </Field>
            <Button type="submit" disabled={add.isPending}>
              {add.isPending ? "Saving..." : "Save beneficiary"}
            </Button>
          </form>
        </Card>

        <div>
          {beneficiaries.isLoading || items == null ? (
            <div className="space-y-2"><Skeleton className="h-16" /><Skeleton className="h-16" /></div>
          ) : items.length === 0 ? (
            <EmptyState title="No beneficiaries" description="Save one to send money in one tap." />
          ) : (
            <ul className="space-y-2">
              {items.map((b) => (
                <li key={b.id} className="panel flex items-center justify-between p-4">
                  <div>
                    <p className="font-medium">{b.nickname}</p>
                    <p className="mono muted">{b.iban}</p>
                  </div>
                  <Button size="sm" variant="danger" onClick={() => setConfirm(b)}>Remove</Button>
                </li>
              ))}
            </ul>
          )}
        </div>
      </div>

      <Modal open={confirm != null} onClose={() => setConfirm(null)} title="Remove beneficiary?">
        <p className="text-sm">
          Remove <strong>{confirm?.nickname}</strong> (<span className="mono">{confirm?.iban}</span>)? This cannot be undone.
        </p>
        <div className="mt-4 flex justify-end gap-2">
          <Button variant="secondary" onClick={() => setConfirm(null)}>Cancel</Button>
          <Button variant="danger" onClick={remove}>Remove</Button>
        </div>
      </Modal>
    </AppShell>
  );
}
