import { describe, expect, it } from "vitest";
import { ApiError } from "./api";
import { classifyMoneyFailure } from "./money-failure";

describe("classifyMoneyFailure", () => {
  it("keeps the server's words for a definitive rejection (nothing was recorded)", () => {
    for (const err of [
      new ApiError(400, "Transfer Rejected", "Amount must be positive"),
      new ApiError(422, "Insufficient Funds", "Insufficient funds"),
      new ApiError(404, "Not Found", "No account with IBAN DE01")
    ]) {
      const failure = classifyMoneyFailure("transfer", err);
      expect(failure.ambiguous).toBe(false);
      expect(failure.message).toBe(err.message);
    }
  });

  it("treats a lost response (network error, no HTTP status) as unknown and retry-safe", () => {
    const failure = classifyMoneyFailure("deposit", new TypeError("Failed to fetch"));
    expect(failure.ambiguous).toBe(true);
    expect(failure.message).toContain("can't confirm");
    expect(failure.message).toContain("never double-post");
  });

  it("treats a 5xx as unknown: the server may have committed before failing", () => {
    const failure = classifyMoneyFailure("transfer", new ApiError(503, "Server Error", "boom"));
    expect(failure.ambiguous).toBe(true);
    expect(failure.message).toContain("could not confirm");
    expect(failure.message).toContain("retry to check");
    expect(failure.message).toContain("post once");
  });

  it("treats a 429 as rate-limiting, never proof of rejection", () => {
    const failure = classifyMoneyFailure("deposit", new ApiError(429, "Too Many Requests", "slow down"));
    expect(failure.ambiguous).toBe(true);
    expect(failure.message).toContain("nothing was lost");
    expect(failure.message).toContain("cannot double-post");
  });

  it("treats a 409 key conflict as unknown - the operation may already exist", () => {
    const failure = classifyMoneyFailure("deposit", new ApiError(409, "Idempotency Conflict", "used"));
    expect(failure.ambiguous).toBe(true);
    expect(failure.message).toContain("may have already gone through");
    expect(failure.message).toContain("can never move money twice");
  });

  it("never tells the user to guess from the balance on an unknown outcome", () => {
    const failure = classifyMoneyFailure("transfer", new TypeError("load failed"));
    expect(failure.message.toLowerCase()).not.toContain("check your balance");
    // The whole point: the attempt is saved and a retry is the safe check.
    expect(failure.message).toContain("attempt is saved");
    expect(failure.message).toContain("retry");
  });
});
