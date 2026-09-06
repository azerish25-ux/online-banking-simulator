import { z } from "zod";

/**
 * Runtime response guards (F14). Generated types describe the contract at
 * compile time, but a server that misbehaves (or a proxy that rewrites a
 * body) must not be trusted just because TypeScript believes the shape - the
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
