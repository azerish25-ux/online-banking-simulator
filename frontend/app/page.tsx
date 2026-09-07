import Link from "next/link";
import { ArrowRight } from "lucide-react";
import { Button } from "../components/ui/button";
import { Routes } from "../lib/routes";
import { BrandName } from "../lib/brand";
import { AboutDemoLink } from "../components/layout/demo-seam";
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
        <p className="caps text-brass-400">{BrandName} · demo core</p>

        <h1 className="display mt-5 max-w-3xl text-5xl font-semibold leading-[1.05] tracking-tight md:text-6xl">
          Checking, savings, loans, transfers,
          <span className="block text-brass-300">interest, cards, and an operator console.</span>
        </h1>

        <p className="muted mt-6 max-w-xl text-base leading-relaxed">
          A working banking core built with Next.js and Spring Boot. This is
          not a mockup. The numbers on this page are served by the running
          system.
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
            title="Transfers that settle exactly once"
            body="A transfer locks both accounts and settles in one step. You can retry an interrupted send as often as you like. The money still moves exactly once, never twice."
          />
          <Feature
            title="Large transfers stop for a human"
            body="Sends of $10,000 or more are held rather than sent. An operator approves or declines them in the review queue, and you can follow the decision in the product."
          />
          <Feature
            title="Every move leaves a record"
            body="Each deposit, transfer, and approval writes an audit row with amounts and account numbers. Operators can browse them, and you see your own as statements."
          />
        </div>

        <div className="mt-10 flex flex-wrap items-center justify-between gap-4">
          <p className="muted text-xs">
            health: <code className="mono">GET /api/health</code> · contract:{" "}
            <code className="mono">openapi.json</code> · run:{" "}
            <code className="mono">.\start-all.ps1</code>
          </p>
          <div className="flex items-center gap-5 text-sm">
            <AboutDemoLink label="How it works" className="text-brass-300 hover:underline" />
            <Link href={Routes.design} className="text-brass-300 hover:underline">
              Design system →
            </Link>
          </div>
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
