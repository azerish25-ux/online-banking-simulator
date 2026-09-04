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

  // Surface failures once per mutation, not on every render.
  React.useEffect(() => {
    if (setup.isError) push(setup.error.message, "error");
    if (enable.isError) push(enable.error.message, "error");
    if (disable.isError) push(disable.error.message, "error");
  }, [setup.isError, setup.error, enable.isError, enable.error, disable.isError, disable.error, push]);

  React.useEffect(() => {
    if (setup.isSuccess && setup.data) {
      setPendingSetup(setup.data);
      setEnableCode("");
    }
  }, [setup.isSuccess, setup.data, push]);

  React.useEffect(() => {
    if (enable.isSuccess) {
      setPendingSetup(null);
      push("Two-factor authentication is on. You'll be asked for a code at your next login.", "success");
    }
  }, [enable.isSuccess, push]);

  React.useEffect(() => {
    if (disable.isSuccess) {
      setDisableOpen(false);
      setDisableCode("");
      push("Two-factor authentication is off.", "success");
    }
  }, [disable.isSuccess, push]);

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
                  <p className="caps mb-1 text-slate-400 normal-case">Secret key</p>
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
          <Field label="Authenticator code" hint="6 digits">
            <Input
              inputMode="numeric"
              autoComplete="one-time-code"
              maxLength={6}
              placeholder="000000"
              value={disableCode}
              onChange={(e) => setDisableCode(e.target.value.replace(/\D/g, ""))}
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
