"use client";

import { usePublicStats } from "../lib/queries";
import { usd } from "../lib/format";

/**
 * Client island inside the otherwise static landing page: pulls the one
 * public endpoint (cached server-side, evicted on register/transfer).
 * Figures are set in the display serif, like a printed statement.
 */
export function HeroStats() {
  const query = usePublicStats();
  const stats = query.data ?? null;

  // "Accounts opened" is a count of real customer accounts; the API also
  // reports users separately, and mixing the two would mislabel the figure.
  const items = [
    { label: "Accounts opened", value: stats ? stats.accounts.toLocaleString("en-US") : "-" },
    { label: "Transfers settled", value: stats ? stats.transfers.toLocaleString("en-US") : "-" },
    { label: "Transfer volume", value: stats ? usd(stats.volume) : "-" }
  ];

  return (
    <dl className="grid gap-8 sm:grid-cols-3">
      {items.map((item) => (
        <div key={item.label}>
          <dt className="caps muted">{item.label}</dt>
          <dd className="display mt-2 text-4xl font-semibold tabular-nums text-brass-200">
            {item.value}
          </dd>
        </div>
      ))}
    </dl>
  );
}
