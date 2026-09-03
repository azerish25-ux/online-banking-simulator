export type StatementKind = "csv" | "pdf";
export type DateRange = { from?: string; to?: string };

/**
 * Statement download URLs. The `/backend` prefix is the Next.js rewrite proxy
 * to the API (`/backend/*` -> `/api/*`), so the `/v1` segment is required -
 * a missing segment 404s in production while unit tests with mocked fetch
 * stay green. These builders make the shape exact and tested.
 */
export function statementUrl(accountId: string, kind: StatementKind, range: DateRange = {}): string {
  return build("/backend/v1/accounts/" + accountId + "/statement." + kind, range);
}

export function adminStatementUrl(accountId: string): string {
  return "/backend/v1/admin/accounts/" + accountId + "/statement.pdf";
}

function build(base: string, range: DateRange): string {
  const q: string[] = [];
  if (range.from) q.push("from=" + range.from);
  if (range.to) q.push("to=" + range.to);
  return q.length > 0 ? base + "?" + q.join("&") : base;
}
