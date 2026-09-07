import Link from "next/link";
import { ArrowLeft } from "lucide-react";
import { AboutThisDemo, BrandName } from "../../lib/brand";
import { Routes } from "../../lib/routes";
import { Card, CardDescription, CardTitle } from "../../components/ui/card";
import { Badge } from "../../components/ui/badge";

/**
 * The demo seam: what the product surfaces show, and the machinery behind
 * them - written for people curious enough to click "About this demo", so the
 * product pages themselves can stay in bank voice.
 */
export default function AboutPage() {
  return (
    <div className="min-h-screen bg-workspace">
      <div className="mx-auto max-w-4xl px-5 py-12">
        <p className="text-sm">
          <Link href={Routes.home} className="text-action hover:underline">
            <ArrowLeft size={14} aria-hidden="true" className="inline" /> {BrandName}
          </Link>
        </p>
        <h1 className="mt-2 text-[24px] leading-[30px] font-semibold tracking-tight md:text-[28px] md:leading-[34px]">{AboutThisDemo}</h1>
        <p className="muted mt-1 max-w-2xl text-sm">
          What you can do here, what is simulated, and the machinery underneath,
          in plain words.
        </p>

        <Card className="mt-6">
          <CardTitle>What this is</CardTitle>
          <CardDescription>
            {BrandName} is a working banking core with a full interface: checking,
            savings, and loan accounts; transfers with a review desk; monthly
            interest; virtual cards; two-factor sign-in; and an operator console
            with an audit trail. The money is simulated, so no real funds move,
            but the software behaves like a bank&rsquo;s: balances are exact,
            transfers settle atomically, and every movement is recorded.
          </CardDescription>
        </Card>

        <div className="mt-4 grid gap-4 md:grid-cols-2">
          <Card>
            <CardTitle>Transfers that land once</CardTitle>
            <CardDescription>
              A transfer locks both accounts and settles in one step. If a send is
              interrupted and you retry it, the retry is recognised and the money
              still moves exactly once, never twice. Tests prove this by racing
              transfers in opposite directions against a real database.
            </CardDescription>
          </Card>
          <Card>
            <CardTitle>Large wires stop for a human</CardTitle>
            <CardDescription>
              A transfer of $10,000 or more is never sent on submit. It is held in
              the operators&rsquo; review queue (<em>Review queue</em> under
              Operations) until an operator approves it, which moves the money, or
              declines it, which leaves your account untouched. Flagged deposits
              credit on arrival and just need acknowledging.
            </CardDescription>
          </Card>
          <Card>
            <CardTitle>Interest, monthly and idempotent</CardTitle>
            <CardDescription>
              Savings earn 4% a year and loans charge 12%, posted once per month
              by a scheduled job (also triggerable from Operations). A loan drawn
              to its limit can&rsquo;t accrue past its cap, so one maxed account
              never breaks the run for everyone else.
            </CardDescription>
          </Card>
          <Card>
            <CardTitle>Signed in, and proven</CardTitle>
            <CardDescription>
              Passwords are hashed with BCrypt, sessions use short-lived access
              tokens with rotating refresh tokens, and six-digit codes (TOTP) can
              be added as a second factor from the Security page. Login attempts
              and code guesses are throttled.
            </CardDescription>
          </Card>
          <Card>
            <CardTitle>Every move leaves a record</CardTitle>
            <CardDescription>
              Each deposit, transfer, approval and account change writes an audit
              row with amounts and account numbers. Operators can read the trail
              in the console; you can download CSV or PDF statements per account
              and see where every cent came from and went.
            </CardDescription>
          </Card>
          <Card>
            <CardTitle>What is simulated</CardTitle>
            <CardDescription>
              Deposits arrive through a demo rail instead of a real payment
              network, notifications go to an in-app center (with an email stub
              in the logs), and virtual cards are issued for show. There is no
              card payment rail. Recipients must hold an account here, because
              the demo never dials out to real banks.
            </CardDescription>
          </Card>
        </div>

        <div className="mt-6 rounded-md border border-divider p-5">
          <p className="text-sm font-medium text-content-secondary">Residual risks</p>
          <ul className="muted mt-3 list-disc space-y-2 pl-5 text-sm">
            <li>
              The browser holds a short-lived access token that the app&rsquo;s routing
              layer can read (it expires in 15 minutes); the refresh token is
              HttpOnly. A full back-end-for-frontend setup would remove the
              browser-readable token entirely.
            </li>
            <li>
              Login throttling and TOTP budgets are in-memory, so they are
              single-instance by design. A load-balanced deployment would move
              them to a shared store.
            </li>
            <li>
              Money rules are enforced in the backend first; the forms mirror
              them client-side for fast feedback, and the two are kept in sync by
              tests rather than by one shared schema.
            </li>
          </ul>
          <p className="muted mt-4 text-sm">
            Try the demo as a customer with{" "}
            <span className="mono">alice@bank.local</span> /{" "}
            <span className="mono">secret123</span>, or open the operator console
            with <span className="mono">admin@bank.local</span>. Seeded and
            fresh registrations both work.
          </p>
          <div className="mt-4 flex flex-wrap gap-2">
            <Badge tone="neutral">Next.js + TypeScript</Badge>
            <Badge tone="neutral">Java 17 + Spring Boot</Badge>
            <Badge tone="neutral">PostgreSQL 16</Badge>
            <Badge tone="neutral">React Query</Badge>
          </div>
        </div>

        <p className="mt-6 text-sm">
          <Link href={Routes.design} className="text-action hover:underline">
            Browse the design system →
          </Link>
        </p>
      </div>
    </div>
  );
}
