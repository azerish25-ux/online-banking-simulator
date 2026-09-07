"use client";

import * as React from "react";
import { useResultToast } from "../feedback/use-result-toast";
import { Button } from "../ui/button";
import { Field } from "../ui/input";
import { InlineAlert } from "../ui/inline-alert";
import { Modal } from "../ui/modal";
import { Select } from "../ui/select";
import { useOpenAccount } from "../../lib/queries";

/**
 * The open-account dialog. Owns the chosen type (re-defaulted to SAVINGS on
 * every open, exactly as the dashboard button used to reset it), the submit
 * state and the success/error toasts. The page supplies only open state and
 * whether a loan already exists (one loan at a time - the backend refuses a
 * second, and the option must say why).
 */
export function OpenAccountDialog({
  open,
  onClose,
  hasLoan
}: {
  open: boolean;
  onClose: () => void;
  hasLoan: boolean;
}) {
  const openAccount = useOpenAccount();
  const [type, setType] = React.useState("SAVINGS");
  // A server rejection (the one loan-per-customer rule, a race) belongs in
  // the dialog that stayed open - a general failure with no single field.
  const [failure, setFailure] = React.useState<string | null>(null);

  // Reset synchronously while opening: re-rendering before paint means the
  // select never flashes the previous choice (the old dashboard button set
  // the default in its click handler before the dialog appeared), and a
  // rejection from a previous visit is not carried into a fresh attempt.
  const wasOpen = React.useRef(open);
  if (open && !wasOpen.current) {
    setType("SAVINGS");
    setFailure(null);
  }
  wasOpen.current = open;

  // Result → feedback wiring lives in the shared owner. Failures render
  // inline (above); success closes the dialog via the latest `onClose`
  // (kept fresh inside the owner), so a refetch after opening can never
  // replay this toast.
  useResultToast(openAccount, {
    error: false,
    onFailure: setFailure,
    success: { toast: { message: "Account opened." }, run: () => onClose() }
  });

  function submit() {
    setFailure(null);
    openAccount.mutate(type);
  }

  return (
    <Modal open={open} onClose={onClose} title="Open account">
      <form onSubmit={(e) => { e.preventDefault(); submit(); }} className="space-y-4">
        {failure && <InlineAlert>{failure}</InlineAlert>}
        <Field label="Account type">
          <Select value={type} onChange={(e) => setType(e.target.value)}>
            <option value="CHECKING">Checking (everyday money)</option>
            <option value="SAVINGS">Savings (earns monthly interest)</option>
            <option value="LOAN" disabled={hasLoan}>Loan (borrow up to $1,000 by sending money from it)</option>
          </Select>
          {hasLoan && (
            <p className="muted mt-2 text-xs">You already have a loan open. Settle it before taking out another.</p>
          )}
        </Field>
        <div className="flex justify-end gap-2">
          <Button type="button" variant="secondary" disabled={openAccount.isPending} onClick={onClose}>Cancel</Button>
          <Button type="submit" disabled={openAccount.isPending}>
            {openAccount.isPending ? "Opening..." : "Open"}
          </Button>
        </div>
      </form>
    </Modal>
  );
}
