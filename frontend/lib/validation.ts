import { z } from "zod";

/** Single source of truth for form rules - mirrors the API validation. */
// Mirrors the API rules - including the 72-character ceiling: BCrypt silently
// truncates longer passwords, so capping here keeps the client and the hash
// function in agreement.
export const loginSchema = z.object({
  email: z.string().email("Enter a valid email"),
  password: z.string().min(1, "Password is required").max(72, "Maximum 72 characters")
});
export type LoginForm = z.infer<typeof loginSchema>;

export const registerSchema = z.object({
  fullName: z.string().min(2, "Enter your full name"),
  email: z.string().email("Enter a valid email"),
  password: z.string().min(8, "Minimum 8 characters").max(72, "Maximum 72 characters")
});
export type RegisterForm = z.infer<typeof registerSchema>;

// Cents-only entry: a USD customer types dollars and cents, and the UI shows
// balances rounded to cents - an entered 4-decimal amount (the ledger's
// internal scale) would toast "Deposited $1.23" for $1.2345 and strand
// sub-cent dust no screen ever shows. Zero is rejected here, not by the
// server a round-trip later (the modal would sit open with no inline error).
const amountRule = z
  .string()
  .regex(/^\d+(\.\d{1,2})?$/, "Enter an amount like 10.00")
  .refine((v) => Number(v) > 0, "Enter an amount greater than zero");

export const transferSchema = z.object({
  fromAccountId: z.string().min(1, "Choose a source account"),
  toIban: z.string().trim().min(8, "Enter the full recipient IBAN").max(34),
  amount: amountRule,
  memo: z.string().max(140, "Max 140 characters").optional()
});
export type TransferForm = z.infer<typeof transferSchema>;

export const beneficiarySchema = z.object({
  nickname: z.string().trim().min(1, "Nickname is required").max(80),
  iban: z.string().trim().min(8, "Enter the full IBAN").max(34)
});
export type BeneficiaryForm = z.infer<typeof beneficiarySchema>;

export const depositSchema = z.object({
  amount: amountRule
});
