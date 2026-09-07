"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { AuthShell } from "../../../components/layout/auth-shell";
import { Button } from "../../../components/ui/button";
import { Field, Input } from "../../../components/ui/input";
import { useToast } from "../../../components/feedback/toast";
import { api, setToken, ApiError } from "../../../lib/api";
import type { AuthResponse } from "../../../lib/api-types";
import { clearMfaToken, peekMfaToken } from "../../../lib/mfa";
import { Routes } from "../../../lib/routes";

export default function MfaChallengePage() {
  const router = useRouter();
  const { push } = useToast();
  const [code, setCode] = useState("");
  const [busy, setBusy] = useState(false);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    const token = peekMfaToken();
    if (!token) {
      push("Your login challenge expired. Please log in again.", "error");
      router.push(Routes.login);
      return;
    }
    setBusy(true);
    try {
      const data = await api<AuthResponse>("/v1/auth/mfa/verify", {
        method: "POST",
        body: JSON.stringify({ mfaToken: token, code: code.trim() })
      });
      clearMfaToken();
      setToken(data.accessToken, data.expiresInSeconds);
      push("Welcome back.", "success");
      router.push(Routes.dashboard);
    } catch (err) {
      // F30: the server burns attempts on wrong codes and locks the budget
      // (429 Retry-After) once exhausted. This challenge can no longer
      // succeed - pretending otherwise with endless retries is dishonest - so
      // send the user back for a fresh challenge after a clear explanation.
      if (err instanceof ApiError && err.status === 429) {
        clearMfaToken();
        push("Too many incorrect attempts. Log in again for a fresh challenge.", "error");
        router.push(Routes.login);
        return;
      }
      push(err instanceof Error ? err.message : "Verification failed", "error");
      setBusy(false);
    }
  }

  return (
    <AuthShell
      title="Two-factor check"
      subtitle="Enter the six-digit code from your authenticator app."
      footer={
        <>        Wrong device? <Link className="text-action" href={Routes.login}>Log in again</Link></>
      }
    >
      <form onSubmit={submit} className="space-y-4" noValidate>
        <Field label="Authenticator code" hint="6 digits">
          <Input
            inputMode="numeric"
            autoComplete="one-time-code"
            maxLength={6}
            placeholder="000000"
            value={code}
            onChange={(e) => setCode(e.target.value.replace(/\D/g, ""))}
            className="font-mono text-lg tracking-[0.4em]"
          />
        </Field>
        <Button type="submit" className="w-full" disabled={busy || code.length !== 6}>
          {busy ? "Verifying..." : "Verify and continue"}
        </Button>
      </form>
    </AuthShell>
  );
}
