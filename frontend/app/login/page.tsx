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
import { authSessionSchema, mfaChallengeSchema } from "../../lib/guards";
import { stashMfaToken } from "../../lib/mfa";
import { Routes } from "../../lib/routes";

export default function LoginPage() {
  const router = useRouter();
  const { push } = useToast();
  const { register, handleSubmit, formState } = useForm<Form>({ resolver: zodResolver(schema) });

  async function onSubmit(values: Form) {
    try {
      // Two explicit outcomes (contract: 200 AuthResponse, 202 MfaRequired):
      // the body discriminates them, and both shapes are validated before the
      // session is branched - an unexpected body is an error, not a guess.
      const data = await api<unknown>("/v1/auth/login", { method: "POST", body: JSON.stringify(values) });
      const mfa = mfaChallengeSchema.safeParse(data);
      if (mfa.success) {
        stashMfaToken(mfa.data.mfaToken);
        router.push(Routes.loginMfa);
        return;
      }
      const session = authSessionSchema.safeParse(data);
      if (session.success) {
        setToken(session.data.accessToken);
        push("Welcome back.", "success");
        router.push(Routes.dashboard);
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
