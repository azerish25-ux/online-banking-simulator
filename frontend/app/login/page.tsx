"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { loginSchema as schema, type LoginForm as Form } from "../../lib/validation";
import { AuthShell } from "../../components/layout/auth-shell";
import { Button } from "../../components/ui/button";
import { Field, Input } from "../../components/ui/input";
import { useToast } from "../../components/feedback/toast";
import { api, setToken } from "../../lib/api";



export default function LoginPage() {
  const router = useRouter();
  const { push } = useToast();
  const { register, handleSubmit, formState } = useForm<Form>({ resolver: zodResolver(schema) });

  async function onSubmit(values: Form) {
    try {
      const data = await api("/v1/auth/login", { method: "POST", body: JSON.stringify(values) });
      setToken(data.accessToken);
      push("Welcome back.", "success");
      router.push("/dashboard");
    } catch (err) {
      push(err instanceof Error ? err.message : "Login failed", "error");
    }
  }

  return (
    <AuthShell
      title="Log in to Northbank"
      subtitle="Secure access to your accounts."
      footer={<>No account? <Link className="text-brand-300" href="/register">Register</Link></>}
    >
      <form onSubmit={handleSubmit(onSubmit)} className="space-y-4" noValidate>
        <Field label="Email" error={formState.errors.email?.message}>
          <Input type="email" autoComplete="email" placeholder="you@example.com" {...register("email")} />
        </Field>
        <Field label="Password" error={formState.errors.password?.message}>
          <Input type="password" autoComplete="current-password" {...register("password")} />
        </Field>
        <Button type="submit" className="w-full" disabled={formState.isSubmitting}>
          {formState.isSubmitting ? "Logging in..." : "Log in"}
        </Button>
      </form>
    </AuthShell>
  );
}
