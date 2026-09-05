"use client";

import * as React from "react";
import { AppShell } from "../../components/layout/app-shell";
import { Badge } from "../../components/ui/badge";
import { Button } from "../../components/ui/button";
import { Card, CardDescription, CardTitle } from "../../components/ui/card";
import { Field, Input } from "../../components/ui/input";
import { Modal } from "../../components/ui/modal";
import { Skeleton } from "../../components/ui/skeleton";
import { useToast } from "../../components/feedback/toast";
import { useResultToast } from "../../components/feedback/use-result-toast";
import { useMe, useTotpDisable, useTotpEnable, useTotpSetup } from "../../lib/queries";

export default function SettingsPage() {
  const { push } = useToast();
  const me = useMe();
  const totpEnabled = me.data?.totpEnabled ?? false;

  const setup = useTotpSetup();
  const enable = useTotpEnable();
  const disable = useTotpDisable();

  const [pendingSetup, setPendingSetup] = React.useState<{ secret: string; qrDataUri: string } | null>(null);
  const [enableCode, setEnableCode] = React.useState("");
  const [disableOpen, setDisableOpen] = React.useState(false);
  const [disableCode, setDisableCode] = React.useState("");
  // A wrong code is rejected while the modal stays open with the bad value
  // still in the field - the message renders under it, not in a corner toast.
  const [disableError, setDisableError] = React.useState<string | null>(null);

  // A fresh attempt never carries a previous rejection.
  React.useEffect(() => {
    if (disableOpen) setDisableError(null);
  }, [disableOpen]);

  // Result → feedback wiring lives in the shared owner. Setup succeeds into
  // a state (the QR pane), so it has no toast - only its failure speaks.
  // The enable pane is page-level, so its wrong-code failure stays a corner
  // toast; the disable modal renders its rejection inline under the code.
  useResultToast(setup, {
    success: {
      run: (d) => {
        setPendingSetup(d);
        setEnableCode("");
      }
    }
  });
  useResultToast(enable, {
    success: {
      toast: { message: "Two-factor authentication is on. You'll be asked for a code at your next login." },
      run: () => setPendingSetup(null)
    }
  });
  useResultToast(disable, {
    error: false,
    onFailure: setDisableError,
    success: {
      toast: { message: "Two-factor authentication is off." },
      run: () => {
        setDisableOpen(false);
        setDisableCode("");
        setDisableError(null);
      }
    }
  });

  async function copySecret() {
    if (!pendingSetup) return;
    try {
      await navigator.clipboard.writeText(pendingSetup.secret);
      push("Secret copied.", "success");
    } catch {
      push("Couldn't copy - select the secret manually.", "error");
    }
  }

  return (
    <AppShell>
      <h1 className="text-2xl font-bold tracking-tight">Security</h1>
      <p className="muted mt-1 text-sm">Two-factor authentication for your sign-in.</p>

      {me.isLoading || me.data == null ? (
        <Skeleton className="mt-4 h-40" />
      ) : (
        <Card className="mt-4 max-w-2xl">
          <div className="flex items-center justify-between">
            <div>
              <CardTitle>Authenticator app (TOTP)</CardTitle>
              <CardDescription>
                {totpEnabled
                  ? "Active - a six-digit code is required when you log in."
                  : "Add a second factor so a stolen password alone can't open your account."}
              </CardDescription>
            </div>
            <Badge tone={totpEnabled ? "success" : "neutral"}>{totpEnabled ? "ON" : "OFF"}</Badge>
          </div>

          {!totpEnabled && !pendingSetup && (
            <Button
              className="mt-4"
              disabled={setup.isPending}
              onClick={() => setup.mutate()}
            >
              {setup.isPending ? "Preparing..." : "Set up authenticator"}
            </Button>
          )}

          {totpEnabled && (
            <Button variant="secondary" className="mt-4" onClick={() => setDisableOpen(true)}>
              Turn off two-factor
            </Button>
          )}

          {pendingSetup && (
            <div className="mt-4 rounded-md border border-line p-4">
              <p className="text-sm font-medium">Scan with your authenticator app</p>
              <p className="muted mt-1 text-sm">
                Open your authenticator, scan the QR code (or type the secret), then enter the
                six-digit code to confirm. Don&rsquo;t close this window until it&rsquo;s enabled.
              </p>
              <div className="mt-3 flex flex-wrap items-center gap-4">
                {/* eslint-disable-next-line @next/next/no-img-element */}
                <img
                  src={pendingSetup.qrDataUri}
                  alt="QR code to add Online Banking Simulator to your authenticator app"
                  width={176}
                  height={176}
                  className="rounded-md border border-line"
                />
                <div className="min-w-0">
                  <p className="label mb-1 text-content-muted">Secret key</p>
                  <p className="mono break-all rounded-md border border-line bg-ink-950/60 px-3 py-2">{pendingSetup.secret}</p>
                  <Button size="sm" variant="secondary" className="mt-2" onClick={copySecret}>
                    Copy secret
                  </Button>
                </div>
              </div>
              <div className="mt-4 max-w-xs">
                <Field label="Authenticator code" hint="6 digits">
                  <Input
                    inputMode="numeric"
                    autoComplete="one-time-code"
                    maxLength={6}
                    placeholder="000000"
                    value={enableCode}
                    onChange={(e) => setEnableCode(e.target.value.replace(/\D/g, ""))}
                    className="font-mono tracking-[0.4em]"
                  />
                </Field>
              </div>
              <div className="mt-3 flex gap-2">
                <Button
                  disabled={enable.isPending || enableCode.length !== 6}
                  onClick={() => enable.mutate(enableCode.trim())}
                >
                  {enable.isPending ? "Verifying..." : "Enable two-factor"}
                </Button>
                <Button variant="ghost" onClick={() => { setPendingSetup(null); setup.reset(); }}>
                  Cancel
                </Button>
              </div>
            </div>
          )}
        </Card>
      )}

      <Modal open={disableOpen} onClose={() => setDisableOpen(false)} title="Turn off two-factor?">
        <p className="text-sm">
          Enter your current authenticator code to confirm. This weakens your account
          security - consider re-enabling it afterwards.
        </p>
        <div className="mt-4">
          <Field label="Authenticator code" hint="6 digits" error={disableError ?? undefined}>
            <Input
              inputMode="numeric"
              autoComplete="one-time-code"
              maxLength={6}
              placeholder="000000"
              value={disableCode}
              onChange={(e) => {
                setDisableCode(e.target.value.replace(/\D/g, ""));
                setDisableError(null);
              }}
              className="font-mono tracking-[0.4em]"
            />
          </Field>
        </div>
        <div className="mt-4 flex justify-end gap-2">
          <Button variant="secondary" onClick={() => setDisableOpen(false)}>Cancel</Button>
          <Button variant="danger" disabled={disable.isPending || disableCode.length !== 6} onClick={() => disable.mutate(disableCode.trim())}>
            {disable.isPending ? "Disabling..." : "Turn off"}
          </Button>
        </div>
      </Modal>
    </AppShell>
  );
}
