import { usd } from "../../lib/format";

export type MonthPoint = { month: string; inflow: string; outflow: string };

export function SpendingChart({ data }: { data: MonthPoint[] }) {
  const max = Math.max(1, ...data.flatMap((d) => [parseFloat(d.inflow), parseFloat(d.outflow)]));
  const W = 560;
  const H = 200;
  const pad = 28;
  const group = (W - pad * 2) / Math.max(1, data.length);
  const barW = Math.min(26, group / 3);
  const scale = (v: number) => (v / max) * (H - pad * 2);

  return (
    <svg viewBox={"0 0 " + W + " " + H} className="w-full" role="img" aria-label="Monthly money in and out">
      <title>Inflow versus outflow by month</title>
      {[0.25, 0.5, 0.75, 1].map((f) => (
        <line
          key={f}
          x1={pad} x2={W - pad}
          y1={H - pad - f * (H - pad * 2)} y2={H - pad - f * (H - pad * 2)}
          stroke="#24365c" strokeDasharray="3 3"
        />
      ))}
      {data.map((d, i) => {
        const x = pad + group * i + group / 2;
        const inH = scale(parseFloat(d.inflow));
        const outH = scale(parseFloat(d.outflow));
        return (
          <g key={d.month}>
            <rect x={x - barW - 2} y={H - pad - inH} width={barW} height={Math.max(1, inH)} rx={3} fill="#34d399">
              <title>{"In " + d.month + ": " + usd(d.inflow)}</title>
            </rect>
            <rect x={x + 2} y={H - pad - outH} width={barW} height={Math.max(1, outH)} rx={3} fill="#38bdf8">
              <title>{"Out " + d.month + ": " + usd(d.outflow)}</title>
            </rect>
            <text x={x} y={H - 8} textAnchor="middle" fontSize={11} fill="#93a4c4">
              {d.month.slice(5)}
            </text>
          </g>
        );
      })}
      <g fontSize={11} fill="#93a4c4">
        <rect x={pad} y={4} width={10} height={10} rx={2} fill="#34d399" />
        <text x={pad + 14} y={13}>In</text>
        <rect x={pad + 52} y={4} width={10} height={10} rx={2} fill="#38bdf8" />
        <text x={pad + 66} y={13}>Out</text>
      </g>
    </svg>
  );
}
