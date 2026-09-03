import Link from "next/link";
import { ArrowRight } from "lucide-react";
import { Button } from "../components/ui/button";
import { Routes } from "../lib/routes";
import { HeroStats } from "./hero-stats";

/**
 * Landing page - statement-paper editorial. Static server component; the
 * numbers live in <HeroStats/>, a small client island, so this tree
 * prerenders fully at build time.
 */
export default function Home() {
  return (
    <div className="min-h-screen bg-ink-900">
      <div className="mx-auto max-w-5xl px-5 py-20">
        <p className="caps text-brass-400">Northbank · Core banking engine</p>

        <h1 className="display mt-5 max-w-3xl text-5xl font-semibold leading-[1.05] tracking-tight md:text-6xl">
          Money that moves
          <span className="block text-brass-300">like it&rsquo;s owed.</span>
        </h1>

        <p className="muted mt-6 max-w-xl text-base leading-relaxed">
          A full banking core - accounts, atomic transfers, interest, cards, audit -
          built in the open on Next.js and Spring Boot. Not a mock: every figure on
          this page is served by the running system.
        </p>

        <div className="mt-8 flex flex-wrap gap-3">
          <Link href={Routes.register}>
            <Button size="lg">Open an account <ArrowRight size={16} aria-hidden="true" /></Button>
          </Link>
          <Link href={Routes.login}><Button size="lg" variant="secondary">Log in</Button></Link>
        </div>

        <div className="mt-14 border-t border-line pt-10">
          <HeroStats />
        </div>

        <div className="mt-14 grid gap-px overflow-hidden rounded-md border border-line bg-line md:grid-cols-3">
          <Feature
            title="Atomic by construction"
            body="Transfers lock both ledgers in ID order and settle inside one transaction. Concurrent opposite-direction flows are proven to conserve every cent."
          />
          <Feature
            title="Idempotent on retry"
            body="Every transfer carries an idempotency key. Replay it once or a hundred times - the money moves exactly once. There is a test that races it."
          />
          <Feature
            title="Audited and reviewed"
            body="Every mutation writes an audit row with amounts and IBANs. Large transfers hold in a review queue that operators clear - visible in the UI, not buried in docs."
          />
        </div>

        <div className="mt-10 flex flex-wrap items-center justify-between gap-4">
          <p className="muted text-xs">
            health: <code className="mono">GET /api/health</code> · contract:{" "}
            <code className="mono">openapi.json</code> · demo:{" "}
            <code className="mono">.\start-all.ps1</code>
          </p>
          <Link href={Routes.design} className="text-sm text-brass-300 hover:underline">
            Design system →
          </Link>
        </div>
      </div>
    </div>
  );
}

function Feature({ title, body }: { title: string; body: string }) {
  return (
    <div className="bg-ink-850 p-6">
      <h2 className="display text-lg font-semibold text-brass-200">{title}</h2>
      <p className="muted mt-2 text-sm leading-relaxed">{body}</p>
    </div>
  );
}
