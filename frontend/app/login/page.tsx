"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { loginSchema as schema, type LoginForm as Form } from "../../lib/validation";
import { AuthShell } from "../../components/layout/auth-shell";
import { Button } from "../../components/ui/button";
import { Field, Input } from "../../components/ui/input";
import { PasswordInput } from "../../components/ui/password-input";
import { useToast } from "../../components/feedback/toast";
import { api, setToken } from "../../lib/api";
import type { AuthResponse } from "../../lib/api-types";
import { stashMfaToken } from "../../lib/mfa";
import { Routes } from "../../lib/routes";

// A successful password check returns AuthResponse; when the account has
// TOTP enabled the server answers 202 with a purpose-bound MFA challenge.
type LoginOutcome = AuthResponse | { mfaToken: string };

export default function LoginPage() {
  const router = useRouter();
  const { push } = useToast();
  const { register, handleSubmit, formState } = useForm<Form>({ resolver: zodResolver(schema) });

  async function onSubmit(values: Form) {
    try {
      const data = await api<LoginOutcome>("/v1/auth/login", { method: "POST", body: JSON.stringify(values) });
      if ("mfaToken" in data) {
        if (data.mfaToken) {
          stashMfaToken(data.mfaToken);
          router.push(Routes.loginMfa);
        } else {
          push("Login challenge is missing - try again.", "error");
        }
        return;
      }
      setToken(data.accessToken);
      push("Welcome back.", "success");
      router.push(Routes.dashboard);
    } catch (err) {
      push(err instanceof Error ? err.message : "Login failed", "error");
    }
  }

  return (
    <AuthShell
      title="Log in"
      subtitle="Secure access to your accounts."
      footer={<>No account? <Link className="text-brass-300" href={Routes.register}>Register</Link></>}
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
