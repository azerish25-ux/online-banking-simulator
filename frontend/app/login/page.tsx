"use client";

import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import * as React from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { loginSchema as schema, type LoginForm as Form } from "../../lib/validation";
import { AuthShell } from "../../components/layout/auth-shell";
import { Button } from "../../components/ui/button";
import { Field, Input } from "../../components/ui/input";
import { PasswordInput } from "../../components/ui/password-input";
import { useToast } from "../../components/feedback/toast";
import { api, setToken } from "../../lib/api";
import { authSessionSchema, mfaChallengeSchema } from "../../lib/guards";
import { stashMfaToken } from "../../lib/mfa";
import { Routes } from "../../lib/routes";

export default function LoginPage() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const { push } = useToast();
  const { register, handleSubmit, formState } = useForm<Form>({ resolver: zodResolver(schema) });
  const repair = React.useRef(false);

  // Session repair: the HttpOnly refresh cookie is scoped to /backend/v1/auth
  // (never readable by page navigations), so the middleware's bank_token gate
  // can bounce a perfectly-alive session here once its short-lived access
  // cookie expires. Before showing the form, spend ONE silent refresh: a live
  // session is repaired and the visitor continues to the intended destination
  // (?next=); a dead one fails the call and the form renders as usual.
  React.useEffect(() => {
    if (repair.current) return;
    repair.current = true;
    let cancelled = false;
    const next = searchParams.get("next");
    (async () => {
      try {
        const res = await fetch("/backend/v1/auth/refresh", { method: "POST" });
        if (!res.ok) return;
        const data = await res.json();
        if (cancelled || typeof data?.accessToken !== "string" || data.accessToken.length === 0) return;
        setToken(data.accessToken, data.expiresInSeconds);
        const target = next && next.startsWith("/") ? next : Routes.dashboard;
        router.replace(target);
      } catch {
        // No recoverable session: stay on the login form.
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [router, searchParams]);

  async function onSubmit(values: Form) {
    try {
      // Two explicit outcomes (contract: 200 AuthResponse, 202 MfaRequired):
      // the body discriminates them, and both shapes are validated before the
      // session is branched: an unexpected body is an error, not a guess.
      const data = await api<unknown>("/v1/auth/login", { method: "POST", body: JSON.stringify(values) });
      const mfa = mfaChallengeSchema.safeParse(data);
      if (mfa.success) {
        stashMfaToken(mfa.data.mfaToken);
        router.push(Routes.loginMfa);
        return;
      }
      const session = authSessionSchema.safeParse(data);
      if (session.success) {
        setToken(session.data.accessToken, session.data.expiresInSeconds);
        push("Welcome back.", "success");
        const next = searchParams.get("next");
        router.push(next && next.startsWith("/") ? next : Routes.dashboard);
        return;
      }
      push("Unexpected login response. Try again.", "error");
    } catch (err) {
      push(err instanceof Error ? err.message : "Login failed", "error");
    }
  }

  return (
    <AuthShell
      title="Log in"
      subtitle="Secure access to your accounts."
      footer={<>No account? <Link className="text-action" href={Routes.register}>Register</Link></>}
    >
      <form onSubmit={handleSubmit(onSubmit)} className="space-y-4" noValidate>
        <Field label="Email" error={formState.errors.email?.message}>
          <Input type="email" autoComplete="email" placeholder="you@example.com" {...register("email")} />
        </Field>
        <Field label="Password" error={formState.errors.password?.message}>
          <PasswordInput autoComplete="current-password" {...register("password")} />
        </Field>
        <Button type="submit" className="w-full" disabled={formState.isSubmitting}>
          {formState.isSubmitting ? "Logging in..." : "Log in"}
        </Button>
      </form>
    </AuthShell>
  );
}
