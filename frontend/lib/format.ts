/**
 * Money display for ledger amounts. Amounts travel as exact decimal strings
 * (the API never sends a number), so display never round-trips through a
 * float: parseFloat turns "2.6750" into 2.6749999... and would display a cent
 * the ledger never rounded.
 *
 * EXACT SCALE: the ledger keeps 4 decimals, so every amount is parsed into an
 * exact integer count of ten-thousandths (BigInt): no floating point, no
 * premature rounding. A SINGLE value is rounded once, only when formatted for
 * the user; any SUM is accumulated at the ten-thousandths scale and
 * rounded once at the end, so per-item rounding can never skew a total.
 *
 * ROUNDING: display rounds HALF_UP (half away from zero for both signs) to
 * two decimals, matching the backend's Money formatter (common/Money.java).
 * Negative zero is normalized to "$0.00".
 *
 * INVALID INPUT: is never silently "$0.00". A malformed amount renders the
 * unavailability marker "-" so bad data cannot read as a real zero balance.
 */
export function usd(amount: string): string {
  const units = toTenThousandths(amount);
  if (units === null) return "-";
  return usdFromCents(unitsToCents(units));
}

/**
 * Sums ledger amounts EXACTLY (ten-thousandths scale) and formats the total
 * once: per-item cent rounding is a display decision, not an arithmetic one.
 * Use this for every money total built from more than one amount.
 */
export function totalUsd(amounts: string[]): string {
  let sum = 0n;
  for (const amount of amounts) {
    const units = toTenThousandths(amount);
    if (units === null) return "-"; // a malformed part must not read as zero
    sum += units;
  }
  return usdFromCents(unitsToCents(sum));
}

/**
 * Review-grade rendering: exposes the ledger's full 4-decimal precision so a
 * supported sub-cent amount is never hidden as "$0.00" on a confirmation or
 * receipt. Amounts with no sub-cent part render normally (two decimals).
 */
export function usdReview(amount: string): string {
  const units = toTenThousandths(amount);
  if (units === null) return "-";
  const hasSubCent = ((units < 0n ? -units : units) % 100n) !== 0n;
  if (!hasSubCent) return usdFromCents(unitsToCents(units));
  return usdFromTenThousandths(units);
}

/**
 * Exact decimal-string → integer ten-thousandths (BigInt). Returns null when
 * the string is not a ledger decimal (optional sign, digits, up to 4 fraction
 * digits): the caller decides how to surface malformed data.
 */
export function toTenThousandths(value: string): bigint | null {
  const raw = (value ?? "").trim();
  if (!/^-?\d+(\.\d{1,4})?$/.test(raw)) return null;
  let negative = false;
  let body = raw;
  if (body.startsWith("-")) {
    negative = true;
    body = body.slice(1);
  }
  const dot = body.indexOf(".");
  const whole = dot === -1 ? body : body.slice(0, dot);
  const frac = dot === -1 ? "" : body.slice(dot + 1);
  const tenThousandths = BigInt((frac + "0000").slice(0, 4).padEnd(4, "0"));
  const units = BigInt(whole === "" ? "0" : whole) * 10_000n + tenThousandths;
  return negative ? -units : units;
}

/**
 * Exact integer cents from a decimal string (single-value convenience: * exact, then rounded once HALF_UP). For SUMS use totalUsd so components are
 * not rounded before they meet; sign checks should use toTenThousandths.
 */
export function decimalToCents(value: string): bigint {
  const units = toTenThousandths(value);
  if (units === null) return 0n;
  return unitsToCents(units);
}

/** Ten-thousandths → integer cents, rounding HALF_UP (away from zero). */
function unitsToCents(units: bigint): bigint {
  const abs = units < 0n ? -units : units;
  const cents = (abs + 50n) / 100n; // half away from zero at the cent
  return units < 0n && cents !== 0n ? -cents : cents;
}

/** Format integer cents without ever rounding through a float. */
export function usdFromCents(cents: bigint): string {
  const sign = cents < 0n ? "-" : "";
  const abs = cents < 0n ? -cents : cents;
  const dollars = abs / 100n;
  const rem = abs % 100n;
  return sign + "$" + dollars.toLocaleString("en-US") + "." + rem.toString().padStart(2, "0");
}

/** Format integer ten-thousandths with the ledger's full 4-decimal precision. */
function usdFromTenThousandths(units: bigint): string {
  const sign = units < 0n ? "-" : "";
  const abs = units < 0n ? -units : units;
  const dollars = abs / 10_000n;
  const rem = abs % 10_000n;
  return sign + "$" + dollars.toLocaleString("en-US") + "."
      + rem.toString().padStart(4, "0");
}

/**
 * Direction-aware amount for account-ledger listings. A transaction's wire
 * amount is a positive magnitude and its direction lives in the from/to
 * IBANs; a row is a credit when the account being viewed is its destination.
 * Returns signed display text so a transfer's direction never depends on the
 * reader matching IBAN columns. Rows that do not touch the viewed account
 * (which should not occur on an account feed) stay unsigned rather than lie.
 */
export function signedUsd(
  amount: string,
  fromIban: string | null | undefined,
  toIban: string | null | undefined,
  viewedIban: string
): string {
  if (toIban === viewedIban) return "+" + usd(amount);
  if (fromIban === viewedIban) return "-" + usd(amount);
  return usd(amount);
}

export function fmtDate(iso: string): string {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  const options: Intl.DateTimeFormatOptions = {
    month: "short",
    day: "numeric",
    hour: "numeric",
    minute: "2-digit"
  };
  // A row from a previous year must say so: December's activity otherwise
  // reads as if it happened this year forever (activity and notifications
  // both span years).
  if (d.getFullYear() !== new Date().getFullYear()) {
    options.year = "numeric";
  }
  return d.toLocaleString("en-US", options);
}

/**
 * "...last six" of an IBAN for listings. Enough to recognize an account you
 * own, never enough to reproduce it. Absent values return null so the caller
 * picks the label ("DEPOSIT", "external", "-") that fits the column.
 */
export function maskIban(iban: string | null | undefined): string | null {
  return iban ? "..." + iban.slice(-6) : null;
}

/**
 * How an account reads in a chooser ("CHECKING ...017984", plus the balance
 * when the selector shows funds). Every account picker: the deposit
 * destination, the transfer source, the activity filter: composes the same
 * name; renaming accounts is a one-file edit. Pass balance only where the
 * current option shows it, so the caller keeps its exact rendering.
 */
export function accountLabel(
  account: { type?: string | null; iban?: string | null },
  balance?: string
): string {
  const name = [account.type, maskIban(account.iban)].filter(Boolean).join(" ");
  return balance === undefined ? name : name + " · " + usd(balance);
}
