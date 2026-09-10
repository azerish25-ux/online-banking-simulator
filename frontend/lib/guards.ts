import { z } from "zod";

/**
 * Runtime response guards. Generated types describe the contract at
 * compile time, but a server that misbehaves (or a proxy that rewrites a
 * body) must not be trusted just because TypeScript believes the shape: the
 * few places where the CLIENT BRANCHES on a response (login: session vs MFA
 * challenge) or parses an error body validate it with zod before acting.
 */

/** Every error answers this RFC-7807 envelope; title/detail feed the toast. */
export const problemEnvelopeSchema = z
  .object({
    title: z.string(),
    detail: z.string()
  })
  .partial()
  .passthrough();

/** Extracts the toastable parts of an error body, tolerating anything. */
export function problemParts(body: unknown): { title?: string; detail?: string } {
  const parsed = problemEnvelopeSchema.safeParse(body);
  if (!parsed.success) {
    return {};
  }
  return { title: parsed.data.title, detail: parsed.data.detail };
}

/** Authenticated login success (HTTP 200). */
export const authSessionSchema = z.object({
  accessToken: z.string(),
  tokenType: z.string(),
  expiresInSeconds: z.number(),
  user: z.object({
    id: z.string(),
    email: z.string(),
    fullName: z.string(),
    role: z.string(),
    totpEnabled: z.boolean()
  })
});

/** MFA-required login challenge (HTTP 202). */
export const mfaChallengeSchema = z.object({
  mfaToken: z.string(),
  message: z.string()
});

/**
 * The two login outcomes are explicit (200 vs 202 in the contract); the body
 * discriminates them at runtime, so the client validates before branching on
 * which path the session takes.
 */
export const loginOutcomeSchema = z.union([authSessionSchema, mfaChallengeSchema]);

// ---------------------------------------------------------------------------
// Financial read-model guards: money, statuses, and
// timestamps are validated at runtime before the UI displays or branches on
// them. A malformed decimal must never be coerced into a zero balance; an
// unsupported state must never silently render as POSTED. Schemas stay
// additive-tolerant (`.passthrough()`, optional nullables) so a server that
// adds a field does not break older clients, while required money/identity
// fields and supported enum states remain enforced.
// ---------------------------------------------------------------------------

/** A ledger amount travels as a decimal string with at most 4 fraction
 *  digits (optional sign): never a JSON number, never more precision than
 *  the ledger keeps. */
const amountPattern = /^-?\d+(\.\d{1,4})?$/;

export const amountString = z
  .string()
  .regex(amountPattern, "not a ledger decimal string");

/** Optional-but-present nullable money fields must still be exact decimals. */
const nullableAmount = amountString.nullable().optional();

/** Timestamps travel as ISO-8601 strings; an unparseable date must not be
 *  rendered as if it were a real posting/request instant. */
const isoInstant = z
  .string()
  .refine((s) => s.includes("T") && !Number.isNaN(Date.parse(s)), "not an ISO instant");

const nullableIso = isoInstant.nullable().optional();

export const accountStatusSchema = z.enum(["ACTIVE", "FROZEN"]);
export const accountTypeSchema = z.enum(["CHECKING", "SAVINGS", "LOAN"]);

export const accountSchema = z
  .object({
    id: z.string().min(1),
    iban: z.string().min(1),
    type: accountTypeSchema,
    balance: amountString,
    status: accountStatusSchema,
    // Loan-only authoritative fields: null on non-loans; tolerated when
    // absent (an older response) but validated when present.
    principalOwed: nullableAmount,
    interestOwed: nullableAmount,
    totalOwed: nullableAmount,
    availableCredit: nullableAmount
  })
  .passthrough();

export const accountListSchema = z.array(accountSchema);

/** Supported transaction lifecycle states. Anything else is unrecognized and
 *  fails closed: the UI must never guess what an unknown state means. */
export const txStatusSchema = z.enum(["POSTED", "HELD", "CANCELLED"]);

export const transactionSchema = z
  .object({
    id: z.string().min(1),
    status: txStatusSchema,
    amount: amountString,
    currency: z.string().regex(/^[A-Z]{3}$/, "not an ISO currency code"),
    createdAt: isoInstant,
    // Nullable relationships are either absent or genuinely null/typed;
    // non-null values are still validated.
    postedAt: nullableIso,
    memo: z.string().nullable().optional(),
    fromIban: z.string().nullable().optional(),
    toIban: z.string().nullable().optional(),
    kind: z.string().optional(),
    flagged: z.boolean().optional(),
    reviewed: z.boolean().optional(),
    reversalId: z.string().nullable().optional(),
    reversalReason: z.string().nullable().optional(),
    reversesTransactionId: z.string().nullable().optional()
  })
  .passthrough();

export const transactionListSchema = z.array(transactionSchema);

/** The cursor-paged history envelope. */
export const historyPageSchema = z.object({
  items: transactionListSchema,
  total: z.number().int().nonnegative(),
  nextCursor: z.string().nullable()
});

/** One monthly point of the spending summary. */
export const monthPointSchema = z
  .object({
    month: z.string().min(1),
    inflow: amountString,
    outflow: amountString
  })
  .passthrough();

export const monthPointListSchema = z.array(monthPointSchema);

/** One recoverable operation on the authorized recent list. */
export const operationItemSchema = z
  .object({
    id: z.string().min(1),
    idempotencyKey: z.string().min(1),
    // The account whose key namespace the operation lives in (the sender for
    // a transfer, the funded account for a deposit): absent only on responses
    // from a server that predates the complete operation identity.
    originatingAccountId: z.string().min(1).optional(),
    kind: z.string().min(1),
    amount: amountString,
    currency: z.string().regex(/^[A-Z]{3}$/, "not an ISO currency code"),
    createdAt: isoInstant,
    postedAt: nullableIso,
    memo: z.string().nullable().optional(),
    fromIban: z.string().nullable().optional(),
    toIban: z.string().nullable().optional(),
    status: txStatusSchema
  })
  .passthrough();

export const operationListSchema = z
  .object({
    items: z.array(operationItemSchema)
  })
  .passthrough();

/**
 * Validates a runtime response against its schema and returns the typed
 * value, or throws so the caller's query lands in its honest error state
 * ("unrecognized response", never coerced money or a guessed status).
 */
export function requireShape<S extends z.ZodTypeAny>(
  schema: S,
  data: unknown,
  what: string
): z.output<S> {
  const parsed = schema.safeParse(data);
  if (!parsed.success) {
    throw new Error("Unrecognized " + what + " response from the server.");
  }
  return parsed.data;
}

// ---------------------------------------------------------------------------
// Money-movement receipts: the 2xx bodies of deposit and transfer mutations.
// A receipt is only actionable when it identifies ITSELF: the operation id,
// the exact key the request carried, and the decimal amount actually moved.
// Anything less is an UNKNOWN outcome (a proxy or codec answered, not the
// ledger): the mutation must treat it exactly like a lost response instead
// of discarding the pending identity on unverifiable success.
// ---------------------------------------------------------------------------

/** The deposit envelope: the funded account plus the operation identity. */
export const depositReceiptSchema = z
  .object({
    account: accountSchema,
    operationId: z.string().min(1),
    idempotencyKey: z.string().min(1),
    amount: amountString,
    status: txStatusSchema
  })
  .passthrough();

/**
 * The transfer receipt: the full transaction row plus the key it was
 * recorded under. The kind is pinned to TRANSFER: a deposit row that
 * arrived on the transfer path must never clear a transfer's identity.
 */
export const transferReceiptSchema = transactionSchema
  .extend({
    idempotencyKey: z.string().min(1),
    kind: z.literal("TRANSFER")
  });

/**
 * True when two ledger decimal strings name the same amount ("10.00" vs
 * "10.0" vs "10.0000"): the ledger's scale is presentation, not value.
 * An unparseable operand falls back to exact string equality.
 */
export function sameAmount(a: string | undefined, b: string | undefined): boolean {
  if (a === undefined || b === undefined) return a === b;
  if (typeof a !== "string" || typeof b !== "string") return false;
  if (!amountPattern.test(a) || !amountPattern.test(b)) return a === b;
  return Number(a) === Number(b);
}
