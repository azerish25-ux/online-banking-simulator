import { createHmac } from "node:crypto";

/**
 * RFC 6238 TOTP in ~30 lines, so the 2FA e2e witness can enroll a real
 * authenticator secret without a library. The backend verifies with the same
 * standard (HMAC-SHA1, 30-second period, 6 digits, base32 secret) plus a
 * ±1-period window, so a code computed on the test runner's clock verifies.
 */
const B32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

function base32Decode(input: string): Buffer {
  const clean = input.toUpperCase().replace(/=+$/, "");
  const out: number[] = [];
  let bits = 0;
  let value = 0;
  for (const ch of clean) {
    const idx = B32.indexOf(ch);
    if (idx === -1) throw new Error("Not base32: " + ch);
    value = (value << 5) | idx;
    bits += 5;
    if (bits >= 8) {
      out.push((value >>> (bits - 8)) & 0xff);
      bits -= 8;
    }
  }
  return Buffer.from(out);
}

/** The six-digit code valid at `atMs` for `secret`. */
export function totpCode(secret: string, atMs: number = Date.now()): string {
  const counter = Math.floor(atMs / 1000 / 30);
  const counterBytes = Buffer.alloc(8);
  counterBytes.writeBigUInt64BE(BigInt(counter));
  const digest = createHmac("sha1", base32Decode(secret)).update(counterBytes).digest();
  const offset = digest[digest.length - 1] & 0x0f;
  const truncated =
    ((digest[offset] & 0x7f) << 24) |
    (digest[offset + 1] << 16) |
    (digest[offset + 2] << 8) |
    digest[offset + 3];
  return (truncated % 1_000_000).toString().padStart(6, "0");
}

/** Codes for the current, previous and next windows - retries land safely. */
export function totpCodesNearNow(secret: string): string[] {
  const now = Date.now();
  return [totpCode(secret, now), totpCode(secret, now - 30_000), totpCode(secret, now + 30_000)];
}
