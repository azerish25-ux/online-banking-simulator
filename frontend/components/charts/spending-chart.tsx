import * as React from "react";
import { usd } from "../../lib/format";

export type MonthPoint = { month: string; inflow: string; outflow: string };

/**
 * Hand-rolled SVG bar pair per month. Restrained financial colors: navy for
 * money in, slate for money out, hairline grid - all from the design tokens.
 *
 * Values are never tooltip-only: the exact decimal strings render into a
 * real data table under the chart that a visible toggle expands, and the
 * collapsed table stays in the accessibility tree so assistive technology
 * reads the same figures the geometry approximates.
 */
export function SpendingChart({ data }: { data: MonthPoint[] }) {
  const tableId = React.useId();
  const [showValues, setShowValues] = React.useState(false);
  const max = Math.max(1, ...data.flatMap((d) => [parseFloat(d.inflow), parseFloat(d.outflow)]));
  const W = 560;
  const H = 200;
  const pad = 28;
  const group = (W - pad * 2) / Math.max(1, data.length);
  const barW = Math.min(26, group / 3);
  const scale = (v: number) => (v / max) * (H - pad * 2);
  const monthLabel = (iso: string) =>
    new Date(iso + "-02").toLocaleString("en-US", { month: "short" });

  return (
    <div>
      <svg
        viewBox={"0 0 " + W + " " + H}
        className="w-full"
        role="img"
        aria-label="Monthly money in and out, in USD"
        aria-describedby={tableId}
      >
        <title>Inflow versus outflow by month, in USD</title>
        {[0.25, 0.5, 0.75, 1].map((f) => (
          <line
            key={f}
            x1={pad} x2={W - pad}
            y1={H - pad - f * (H - pad * 2)} y2={H - pad - f * (H - pad * 2)}
            className="stroke-divider"
            strokeWidth="1"
            strokeDasharray="2 4"
          />
        ))}
        {/* The solid baseline is the zero axis: a $0 month shows its label with
            no bar at all rather than a fake nonzero sliver. */}
        <line
          x1={pad} x2={W - pad}
          y1={H - pad} y2={H - pad}
          className="stroke-content-secondary"
          strokeWidth="1"
          strokeOpacity="0.6"
        />
        {data.map((d, i) => {
          const x = pad + group * i + group / 2;
          const inH = scale(parseFloat(d.inflow));
          const outH = scale(parseFloat(d.outflow));
          return (
            <g key={d.month}>
              <rect x={x - barW - 2} y={H - pad - inH} width={barW} height={Math.max(0, inH)} className="fill-action">
                <title>{"In " + d.month + ": " + usd(d.inflow)}</title>
              </rect>
              <rect x={x + 2} y={H - pad - outH} width={barW} height={Math.max(0, outH)} className="fill-outflow">
                <title>{"Out " + d.month + ": " + usd(d.outflow)}</title>
              </rect>
              <text x={x} y={H - 8} textAnchor="middle" fontSize={11} className="fill-content-secondary">
                {monthLabel(d.month)}
              </text>
            </g>
          );
        })}
        <g fontSize={11} className="fill-content-secondary">
          <rect x={pad} y={4} width={10} height={10} className="fill-action" />
          <text x={pad + 14} y={13}>In</text>
          <rect x={pad + 52} y={4} width={10} height={10} className="fill-outflow" />
          <text x={pad + 66} y={13}>Out</text>
        </g>
      </svg>
      <div className="mt-2 flex items-center gap-3">
        <button
          type="button"
          aria-expanded={showValues}
          onClick={() => setShowValues((v) => !v)}
          className="border border-divider bg-surface px-3 py-1.5 text-sm text-content-secondary hover:bg-surface-subtle"
        >
          {showValues ? "Hide exact values" : "Show exact values"}
        </button>
        <span className="text-xs text-content-secondary">USD · by month</span>
      </div>
      {/* The exact-value data table: visible when toggled, and always in
          the accessibility tree so values are never tooltip-only. */}
      <table id={tableId} className={showValues ? "mt-3 w-full text-sm" : "sr-only"}>
        <caption className="sr-only">Monthly money in and out, by month, in USD</caption>
        <thead>
          <tr className="border-b border-divider text-left text-content-secondary">
            <th scope="col" className="py-1.5 pr-4 font-medium">Month</th>
            <th scope="col" className="py-1.5 pr-4 text-right font-medium">Money in</th>
            <th scope="col" className="py-1.5 text-right font-medium">Money out</th>
          </tr>
        </thead>
        <tbody>
          {data.map((d) => (
            <tr key={d.month} className="border-b border-divider/60">
              <th scope="row" className="py-1.5 pr-4 text-left font-medium">{monthLabel(d.month)}</th>
              <td className="nums py-1.5 pr-4 text-right">{usd(d.inflow)}</td>
              <td className="nums py-1.5 text-right">{usd(d.outflow)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
