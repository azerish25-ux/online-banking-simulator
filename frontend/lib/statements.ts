export type StatementKind = "csv" | "pdf";
export type DateRange = { from?: string; to?: string };

/**
 * Statement download API paths (no proxy prefix). They are fed to
 * downloadAuthed → authedFetch, which prepends the single `/backend` rewrite
 * segment itself - a path that already carries `/backend` here would double
 * it and 404 on the real proxy, while unit tests with mocked fetch stay
 * green either way. These builders make the shape exact and tested.
 */
export function statementUrl(accountId: string, kind: StatementKind, range: DateRange = {}): string {
  return build("/v1/accounts/" + accountId + "/statement." + kind, range);
}

export function adminStatementUrl(accountId: string): string {
  return "/v1/admin/accounts/" + accountId + "/statement.pdf";
}

function build(base: string, range: DateRange): string {
  const q: string[] = [];
  if (range.from) q.push("from=" + range.from);
  if (range.to) q.push("to=" + range.to);
  return q.length > 0 ? base + "?" + q.join("&") : base;
}
