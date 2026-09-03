import { describe, expect, it } from "vitest";
import { adminStatementUrl, statementUrl } from "./statements";

describe("statementUrl", () => {
  it("keeps the /v1 proxy segment for customer exports", () => {
    expect(statementUrl("acc-1", "csv")).toBe("/backend/v1/accounts/acc-1/statement.csv");
    expect(statementUrl("acc-1", "pdf")).toBe("/backend/v1/accounts/acc-1/statement.pdf");
  });

  it("appends only the provided range params", () => {
    expect(statementUrl("acc-1", "csv", { from: "2026-09-01", to: "2026-09-30" })).toBe(
      "/backend/v1/accounts/acc-1/statement.csv?from=2026-09-01&to=2026-09-30"
    );
    expect(statementUrl("acc-1", "csv", { from: "2026-09-01" })).toBe(
      "/backend/v1/accounts/acc-1/statement.csv?from=2026-09-01"
    );
  });
});

describe("adminStatementUrl", () => {
  it("targets the admin statement route through the proxy", () => {
    expect(adminStatementUrl("acc-9")).toBe("/backend/v1/admin/accounts/acc-9/statement.pdf");
  });
});
