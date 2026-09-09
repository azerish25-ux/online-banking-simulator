"use client";

import * as React from "react";
import { AppShell } from "../../components/layout/app-shell";
import { Badge } from "../../components/ui/badge";
import { Button } from "../../components/ui/button";
import { Card, CardDescription, CardTitle } from "../../components/ui/card";
import { Field, Input } from "../../components/ui/input";
import { PasswordInput } from "../../components/ui/password-input";
import { Modal } from "../../components/ui/modal";
import { Skeleton } from "../../components/ui/skeleton";
import { useToast } from "../../components/feedback/toast";
import { useResultToast } from "../../components/feedback/use-result-toast";
import {
  useMe,
  useTotpCancel,
  useTotpDisable,
  useTotpEnable,
  useTotpSetup
} from "../../lib/queries";

const CODE_LENGTH = 6;

export default function SettingsPage() {
  const { push } = useToast();
  const me = useMe();
  const totpEnabled = me.data?.totpEnabled ?? false;

  const setup = useTotpSetup();
  const cancelSetup = useTotpCancel();
  const enable = useTotpEnable();
  const disable = useTotpDisable();

  // The pending-enrollment pane. `isReplacement` is captured when the
  // setup STARTS: the factor is replaced only if one was active then: and
  // drives whether the password + existing-factor proof fields appear.
  const [pendingSetup, setPendingSetup] = React.useState<{ secret: string; qrDataUri: string } | null>(null);
  const [isReplacement, setIsReplacement] = React.useState(false);
  const [enableCode, setEnableCode] = React.useState("");
  const [enableCurrentCode, setEnableCurrentCode] = React.useState("");
  const [enablePassword, setEnablePassword] = React.useState("");
  const [enableError, setEnableError] = React.useState<string | null>(null);

  const [disableOpen, setDisableOpen] = React.useState(false);
  const [disablePassword, setDisablePassword] = React.useState("");
  const [disableCode, setDisableCode] = React.useState("");
  // Rejections render inline under the fields, never as a corner toast behind
  // the scrim, so the bad value stays visible next to the message.
  const [disableError, setDisableError] = React.useState<string | null>(null);

  // A fresh attempt never carries a previous rejection.
  React.useEffect(() => {
    if (disableOpen) {
      setDisableError(null);
      setDisablePassword("");
      setDisableCode("");
    }
  }, [disableOpen]);
  React.useEffect(() => {
    if (pendingSetup) setEnableError(null);
  }, [pendingSetup]);

  // Setup succeeds into the QR pane (state, not a toast); only its failure
  // speaks. Enabling stays on the page, so its rejection is rendered inline
  // beside the fields instead of as a corner toast.
  useResultToast(setup, {
    success: {
      run: (d) => {
        setPendingSetup(d);
        setEnableCode("");
      }
    }
  });
  useResultToast(enable, {
    error: false,
    onFailure: setEnableError,
    success: {
      toast: () => ({
        message: isReplacement
          ? "Your authenticator was replaced."
          : "Two-factor authentication is on. You'll be asked for a code at your next login."
      }),
      run: () => {
        setPendingSetup(null);
        setIsReplacement(false);
        setEnableCode("");
        setEnableCurrentCode("");
        setEnablePassword("");
        setEnableError(null);
      }
    }
  });
  useResultToast(cancelSetup, {
    success: { toast: { message: "Setup cancelled. Your current settings are unchanged." } }
  });
  useResultToast(disable, {
    error: false,
    onFailure: setDisableError,
    success: {
      toast: { message: "Two-factor authentication is off." },
      run: () => {
        setDisableOpen(false);
        setDisableCode("");
        setDisablePassword("");
        setDisableError(null);
      }
    }
  });

  function startSetup() {
    // Captured now: if MFA is active this becomes a REPLACEMENT and the
    // backend will require password + existing-factor proof at enable time.
    setIsReplacement(totpEnabled);
    setEnableCode("");
    setEnableCurrentCode("");
    setEnablePassword("");
    setup.mutate();
  }

  function abandonSetup() {
    // Tell the server the pending row is abandoned so it cannot be promoted
    // later from a stale tab; the active factor (if any) is untouched.
    cancelSetup.mutate(undefined, {
      onSettled: () => {
        setPendingSetup(null);
        setIsReplacement(false);
        setEnableError(null);
        setup.reset();
      }
    });
  }

  function submitEnable() {
    enable.mutate(
      isReplacement
        ? {
            code: enableCode.trim(),
            currentPassword: enablePassword,
            currentCode: enableCurrentCode.trim()
          }
        : { code: enableCode.trim() }
    );
  }

  function submitDisable() {
    disable.mutate({ password: disablePassword, code: disableCode.trim() });
  }

  async function copySecret() {
    if (!pendingSetup) return;
    try {
      await navigator.clipboard.writeText(pendingSetup.secret);
      push("Secret copied.", "success");
    } catch {
      push("Couldn't copy. Select the secret manually.", "error");
    }
  }

  const enableReady = isReplacement
    ? enableCode.length === CODE_LENGTH &&
      enableCurrentCode.length === CODE_LENGTH &&
      enablePassword.length > 0
    : enableCode.length === CODE_LENGTH;

  return (
    <AppShell>
      <h1 className="text-xl leading-7">Security</h1>
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
                  ? "Active: a six-digit code is required when you log in."
                  : "Add a second factor so a stolen password alone can't open your account."}
              </CardDescription>
            </div>
            <Badge tone={totpEnabled ? "success" : "neutral"}>{totpEnabled ? "ON" : "OFF"}</Badge>
          </div>

          {!totpEnabled && !pendingSetup && (
            <Button
              className="mt-4"
              disabled={setup.isPending}
              onClick={startSetup}
            >
              {setup.isPending ? "Preparing..." : "Set up authenticator"}
            </Button>
          )}

          {totpEnabled && !pendingSetup && (
            <div className="mt-4 flex flex-wrap gap-2">
              <Button variant="secondary" disabled={setup.isPending} onClick={startSetup}>
                {setup.isPending ? "Preparing..." : "Replace authenticator"}
              </Button>
              <Button variant="secondary" onClick={() => setDisableOpen(true)}>
                Turn off two-factor
              </Button>
            </div>
          )}

          {pendingSetup && (
            <div className="well mt-4 p-4">
              <p className="text-sm font-medium">
                {isReplacement ? "Replace your authenticator" : "Scan with your authenticator app"}
              </p>
              <p className="muted mt-1 text-sm">
                {isReplacement
                  ? "This does not change your current two-factor yet. Scan the QR code (or type the secret), "
                    + "then prove your identity with your password and current authenticator before the new one activates."
                  : "Open your authenticator, scan the QR code (or type the secret), then enter the "
                    + "six-digit code to confirm. Don&rsquo;t close this window until it&rsquo;s enabled."}
              </p>
              <div className="mt-3 flex flex-wrap items-center gap-4">
                {/* eslint-disable-next-line @next/next/no-img-element */}
                <img
                  src={pendingSetup.qrDataUri}
                  alt="QR code to add Online Banking Simulator to your authenticator app"
                  width={176}
                  height={176}
                  className="border border-divider"
                />
                <div className="min-w-0">
                  <p className="label mb-1 text-content-secondary">Secret key</p>
                  <p className="mono well break-all px-3 py-2">{pendingSetup.secret}</p>
                  <Button size="sm" variant="secondary" className="mt-2" onClick={copySecret}>
                    Copy secret
                  </Button>
                </div>
              </div>

              {isReplacement && (
                <div className="mt-4 grid max-w-sm gap-4">
                  <Field label="Password" hint="Confirm your identity to change two-factor">
                    <PasswordInput
                      autoComplete="current-password"
                      value={enablePassword}
                      onChange={(e) => {
                        setEnablePassword(e.target.value);
                        setEnableError(null);
                      }}
                    />
                  </Field>
                  <Field label="Current authenticator code" hint="6 digits, from the factor being replaced">
                    <Input
                      inputMode="numeric"
                      autoComplete="one-time-code"
                      maxLength={6}
                      placeholder="000000"
                      value={enableCurrentCode}
                      onChange={(e) => {
                        setEnableCurrentCode(e.target.value.replace(/\D/g, ""));
                        setEnableError(null);
                      }}
                      className="font-mono tracking-[0.4em]"
                    />
                  </Field>
                </div>
              )}

              <div className="mt-4 max-w-xs">
                <Field label={isReplacement ? "New authenticator code" : "Authenticator code"} hint="6 digits">
                  <Input
                    inputMode="numeric"
                    autoComplete="one-time-code"
                    maxLength={6}
                    placeholder="000000"
                    value={enableCode}
                    onChange={(e) => {
                      setEnableCode(e.target.value.replace(/\D/g, ""));
                      setEnableError(null);
                    }}
                    className="font-mono tracking-[0.4em]"
                  />
                </Field>
              </div>

              {enableError && (
                <p role="alert" className="mt-3 text-sm text-danger">
                  {enableError}
                </p>
              )}

              <div className="mt-3 flex gap-2">
                <Button
                  disabled={enable.isPending || !enableReady}
                  onClick={submitEnable}
                >
                  {enable.isPending
                    ? "Verifying..."
                    : isReplacement
                      ? "Replace authenticator"
                      : "Enable two-factor"}
                </Button>
                <Button variant="ghost" disabled={cancelSetup.isPending} onClick={abandonSetup}>
                  {cancelSetup.isPending ? "Cancelling..." : "Cancel"}
                </Button>
              </div>
            </div>
          )}
        </Card>
      )}

      <Modal open={disableOpen} onClose={() => setDisableOpen(false)} title="Turn off two-factor?">
        <p className="text-sm">
          Enter your password and a current authenticator code to confirm. This weakens your account
          security. Consider re-enabling it afterwards.
        </p>
        <div className="mt-4 space-y-4">
          <Field label="Password" error={disableError?.includes("credential") ? disableError : undefined}>
            <PasswordInput
              autoComplete="current-password"
              value={disablePassword}
              onChange={(e) => {
                setDisablePassword(e.target.value);
                setDisableError(null);
              }}
            />
          </Field>
          <Field label="Authenticator code" hint="6 digits">
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
          {disableError && !disableError.includes("credential") && (
            <p role="alert" className="text-sm text-danger">
              {disableError}
            </p>
          )}
        </div>
        <div className="mt-4 flex justify-end gap-2">
          <Button variant="secondary" onClick={() => setDisableOpen(false)}>Cancel</Button>
          <Button
            variant="danger"
            disabled={
              disable.isPending ||
              disablePassword.length === 0 ||
              disableCode.length !== CODE_LENGTH
            }
            onClick={submitDisable}
          >
            {disable.isPending ? "Disabling..." : "Turn off"}
          </Button>
        </div>
      </Modal>
    </AppShell>
  );
}
