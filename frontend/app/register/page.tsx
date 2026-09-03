"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { registerSchema as schema, type RegisterForm as Form } from "../../lib/validation";
import { AuthShell } from "../../components/layout/auth-shell";
import { Button } from "../../components/ui/button";
import { Field, Input } from "../../components/ui/input";
import { useToast } from "../../components/feedback/toast";
import { api, setToken } from "../../lib/api";



export default function RegisterPage() {
  const router = useRouter();
  const { push } = useToast();
  const { register, handleSubmit, formState } = useForm<Form>({ resolver: zodResolver(schema) });

  async function onSubmit(values: Form) {
    try {
      const data = await api("/v1/auth/register", { method: "POST", body: JSON.stringify(values) });
      setToken(data.accessToken);
      push("Account created. A checking account is ready.", "success");
      router.push("/dashboard");
    } catch (err) {
      push(err instanceof Error ? err.message : "Registration failed", "error");
    }
  }

  return (
    <AuthShell
      title="Create your account"
      subtitle="A checking account is opened automatically."
      footer={<>Have an account? <Link className="text-brand-300" href="/login">Log in</Link></>}
    >
      <form onSubmit={handleSubmit(onSubmit)} className="space-y-4" noValidate>
        <Field label="Full name" error={formState.errors.fullName?.message}>
          <Input autoComplete="name" placeholder="Ada Lovelace" {...register("fullName")} />
        </Field>
        <Field label="Email" error={formState.errors.email?.message}>
          <Input type="email" autoComplete="email" placeholder="you@example.com" {...register("email")} />
        </Field>
        <Field label="Password" error={formState.errors.password?.message} hint="Minimum 8 characters.">
          <Input type="password" autoComplete="new-password" {...register("password")} />
        </Field>
        <Button type="submit" className="w-full" disabled={formState.isSubmitting}>
          {formState.isSubmitting ? "Creating..." : "Create account"}
        </Button>
      </form>
    </AuthShell>
  );
}
