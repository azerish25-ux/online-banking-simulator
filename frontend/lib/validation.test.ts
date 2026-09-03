import { describe, expect, it } from "vitest";
import { beneficiarySchema, loginSchema, registerSchema, transferSchema } from "./validation";

describe("loginSchema", () => {
  it("accepts a valid login", () => {
    expect(loginSchema.safeParse({ email: "a@b.co", password: "x" }).success).toBe(true);
  });
  it("rejects a bad email", () => {
    expect(loginSchema.safeParse({ email: "nope", password: "x" }).success).toBe(false);
  });
});

describe("registerSchema", () => {
  it("rejects short passwords like the API does", () => {
    const r = registerSchema.safeParse({ fullName: "Al", email: "a@b.co", password: "short" });
    expect(r.success).toBe(false);
  });
});

describe("transferSchema", () => {
  const base = { fromAccountId: "id-1", toIban: "DE44500105175407324931", amount: "10.00" };
  it("accepts a plain transfer", () => {
    expect(transferSchema.safeParse(base).success).toBe(true);
  });
  it("rejects negative, zero-decimal-overflow and float-junk amounts", () => {
    for (const amount of ["-5", "0.00001", "10.12345", "ten", ""]) {
      expect(transferSchema.safeParse({ ...base, amount }).success).toBe(false);
    }
  });
  it("rejects short IBANs and long memos", () => {
    expect(transferSchema.safeParse({ ...base, toIban: "DE1" }).success).toBe(false);
    expect(transferSchema.safeParse({ ...base, memo: "x".repeat(141) }).success).toBe(false);
  });
});

describe("beneficiarySchema", () => {
  it("trims before validating", () => {
    const r = beneficiarySchema.safeParse({ nickname: "  Mo  ", iban: "DE44500105175407324931" });
    expect(r.success).toBe(true);
    if (r.success) expect(r.data.nickname).toBe("Mo");
  });
});
