import Link from "next/link";
import { Badge } from "../components/ui/badge";
import { Button } from "../components/ui/button";
import { Card, CardDescription, CardTitle } from "../components/ui/card";
import { Routes } from "../lib/routes";

export default function Home() {
  return (
    <div className="min-h-screen bg-ink-900">
      <div className="mx-auto max-w-5xl px-5 py-16">
        <Badge tone="info">Part 4 - Design system</Badge>
        <h1 className="mt-4 max-w-2xl text-4xl font-bold tracking-tight md:text-5xl">
          Northbank<span className="text-brand-400">.</span> Enterprise banking, built in the open.
        </h1>
        <p className="muted mt-4 max-w-xl">
          Next.js + TypeScript up front, Spring Boot + PostgreSQL behind. Transfers settle atomically
          with idempotency keys; every mutation leaves an audit trail.
        </p>
        <div className="mt-6 flex flex-wrap gap-2">
          <Link href={Routes.register}><Button size="lg">Create account</Button></Link>
          <Link href={Routes.login}><Button size="lg" variant="secondary">Log in</Button></Link>
          <Link href={Routes.design}><Button size="lg" variant="ghost">Design system</Button></Link>
        </div>

        <div className="mt-10 grid gap-4 md:grid-cols-3">
          <Card>
            <CardTitle>Atomic transfers</CardTitle>
            <CardDescription>Pessimistic locking in ID order. No deadlocks, no partial debits.</CardDescription>
          </Card>
          <Card>
            <CardTitle>Idempotent API</CardTitle>
            <CardDescription>Retry safely - the same key always returns the original transfer.</CardDescription>
          </Card>
          <Card>
            <CardTitle>Audit everything</CardTitle>
            <CardDescription>Registrations, deposits and transfers each write an audit row.</CardDescription>
          </Card>
        </div>

        <p className="muted mt-8 text-sm">
          Backend health: <code className="mono">GET /api/health</code> · Local demo: <code className="mono">.\start-all.ps1</code> then <code className="mono">.\seed-demo.ps1</code>
        </p>
      </div>
    </div>
  );
}
