/**
 * Format a ledger amount for display. Amounts travel as exact decimal strings
 * (the API never sends a number), so display must not round-trip through a
 * float: parseFloat turns "2.6750" into 2.6749999... and displays a cent that
 * the ledger never rounded. The BigInt cents path below is the one exact
 * money formatter in the app - usd() is that path applied to a single value,
 * so a row, a balance, and a summed total always round the same way. Anything
 * that is not a ledger decimal (bad data) renders as $0.00 rather than
 * throwing, like the old float path did for NaN.
 */
export function usd(amount: string): string {
  // The ledger's wire shape: optional sign, digits, up to 4 fraction digits.
  if (!/^-?\d+(\.\d{1,4})?$/.test(amount)) return "$0.00";
  return usdFromCents(decimalToCents(amount));
}

/**
 * Exact decimal-string → cents conversion (BigInt), rounding half away from
 * zero to the nearest cent. Use for anything that SUMS money, where float
 * arithmetic would accumulate rounding error. The server owns the ledger;
 * this only keeps client-side totals honest (the ledger keeps 4 decimals,
 * display rounds to cents - same as usd(), which is built on this).
 */
export function decimalToCents(value: string): bigint {
  const raw = value.trim();
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
  const absCents = (BigInt(whole === "" ? "0" : whole) * 10_000n + tenThousandths + 50n) / 100n;
  return negative ? -absCents : absCents;
}

/** Format integer cents without ever rounding through a float. */
export function usdFromCents(cents: bigint): string {
  const sign = cents < 0n ? "-" : "";
  const abs = cents < 0n ? -cents : cents;
  const dollars = abs / 100n;
  const rem = abs % 100n;
  return sign + "$" + dollars.toLocaleString("en-US") + "." + rem.toString().padStart(2, "0");
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
  // A row from a previous year must say so - December's activity otherwise
  // reads as if it happened this year forever (activity and notifications
  // both span years).
  if (d.getFullYear() !== new Date().getFullYear()) {
    options.year = "numeric";
  }
  return d.toLocaleString("en-US", options);
}

/**
 * "...last six" of an IBAN for listings - enough to recognize an account you
 * own, never enough to reproduce it. Absent values return null so the caller
 * picks the label ("DEPOSIT", "external", "-") that fits the column.
 */
export function maskIban(iban: string | null | undefined): string | null {
  return iban ? "..." + iban.slice(-6) : null;
}

/**
 * How an account reads in a chooser ("CHECKING ...017984", plus the balance
 * when the selector shows funds). Every account picker - the deposit
 * destination, the transfer source, the activity filter - composes the same
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
