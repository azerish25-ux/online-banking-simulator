import { usd } from "../../lib/format";

export type MonthPoint = { month: string; inflow: string; outflow: string };

/**
 * Hand-rolled SVG bar pair per month. Statement aesthetic: hairline grid,
 * brass for money in, slate-blue for money out, serif month labels. All
 * colors come from design tokens (brass-400 / outflow / line / content), so
 * the chart can never drift from the palette - no raw hex literals here.
 */
export function SpendingChart({ data }: { data: MonthPoint[] }) {
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
    <svg viewBox={"0 0 " + W + " " + H} className="w-full" role="img" aria-label="Monthly money in and out">
      <title>Inflow versus outflow by month</title>
      {[0.25, 0.5, 0.75, 1].map((f) => (
        <line
          key={f}
          x1={pad} x2={W - pad}
          y1={H - pad - f * (H - pad * 2)} y2={H - pad - f * (H - pad * 2)}
          className="stroke-line"
          strokeWidth="1"
          strokeDasharray="2 4"
        />
      ))}
      {/* The solid baseline is the zero axis: a $0 month shows its label with
          no bar at all rather than a fake nonzero sliver (F19). */}
      <line
        x1={pad} x2={W - pad}
        y1={H - pad} y2={H - pad}
        className="stroke-content-muted"
        strokeWidth="1"
        strokeOpacity="0.55"
      />
      {data.map((d, i) => {
        const x = pad + group * i + group / 2;
        const inH = scale(parseFloat(d.inflow));
        const outH = scale(parseFloat(d.outflow));
        return (
          <g key={d.month}>
            <rect x={x - barW - 2} y={H - pad - inH} width={barW} height={Math.max(0, inH)} rx={1.5} className="fill-brass-400">
              <title>{"In " + d.month + ": " + usd(d.inflow)}</title>
            </rect>
            <rect x={x + 2} y={H - pad - outH} width={barW} height={Math.max(0, outH)} rx={1.5} className="fill-outflow">
              <title>{"Out " + d.month + ": " + usd(d.outflow)}</title>
            </rect>
            <text x={x} y={H - 8} textAnchor="middle" fontSize={11} className="fill-content-muted"
              fontFamily="var(--font-display), Georgia, serif" fontStyle="italic">
              {monthLabel(d.month)}
            </text>
          </g>
        );
      })}
      <g fontSize={11} className="fill-content-muted">
        <rect x={pad} y={4} width={10} height={10} rx={1.5} className="fill-brass-400" />
        <text x={pad + 14} y={13}>In</text>
        <rect x={pad + 52} y={4} width={10} height={10} rx={1.5} className="fill-outflow" />
        <text x={pad + 66} y={13}>Out</text>
      </g>
    </svg>
  );
}
