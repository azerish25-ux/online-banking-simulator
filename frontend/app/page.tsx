import Link from "next/link";
import { Button } from "../components/ui/button";
import { Routes } from "../lib/routes";
import { BrandName } from "../lib/brand";
import { AboutDemoLink } from "../components/layout/demo-seam";
import { HeroStats } from "./hero-stats";

/**
 * Landing page - one factual statement of what the product is, the simulated
 * money disclosure, and the two real entry points. No
 * engineering feature cards, no slogans: implementation details live in the
 * "About this demo" seam and the repository documentation.
 */
export default function Home() {
  return (
    <div className="min-h-screen bg-workspace">
      <div className="mx-auto max-w-5xl px-5 py-16 md:py-24">
        <h1 className="max-w-3xl text-[28px] leading-[34px] font-semibold tracking-tight md:text-[40px] md:leading-[48px]">
          {BrandName}
        </h1>

        <p className="muted mt-6 max-w-xl text-base leading-relaxed">
          A working online banking demo: checking, savings and loan accounts,
          transfers, monthly interest, virtual cards, two-factor sign-in, and
          an operator console. All money is simulated fictional funds in USD -
          nothing here moves real money.
        </p>

        <div className="mt-8 flex flex-wrap gap-3">
          <Link href={Routes.register}>
            <Button size="lg">Create demo account</Button>
          </Link>
          <Link href={Routes.login}>
            <Button size="lg" variant="secondary">Sign in</Button>
          </Link>
        </div>

        <div className="mt-16 max-w-2xl border-t border-divider pt-8">
          <HeroStats />
        </div>

        <div className="mt-10">
          <AboutDemoLink className="text-sm text-action hover:underline" />
        </div>
      </div>
    </div>
  );
}
