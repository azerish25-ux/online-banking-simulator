export function usd(minorOrMajor: string | number): string {
  const n = typeof minorOrMajor === "string" ? parseFloat(minorOrMajor) : minorOrMajor;
  if (Number.isNaN(n)) return "$0.00";
  return n.toLocaleString("en-US", { style: "currency", currency: "USD" });
}

export function fmtDate(iso: string): string {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  return d.toLocaleString("en-US", { month: "short", day: "numeric", hour: "numeric", minute: "2-digit" });
}

export function shortId(id: string): string {
  return id.length > 8 ? id.slice(0, 8) + "..." : id;
}
