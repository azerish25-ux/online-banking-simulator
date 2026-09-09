"use client";

import { usePublicStats } from "../lib/queries";
import { usd } from "../lib/format";

/**
 * Client island inside the otherwise static landing page: pulls the one
 * public endpoint (cached server-side, evicted on register/transfer).
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
          <dt className="text-sm text-content-secondary">{item.label}</dt>
          <dd className="nums mt-1 text-[26px] leading-8 font-semibold">
            {item.value}
          </dd>
        </div>
      ))}
    </dl>
  );
}
