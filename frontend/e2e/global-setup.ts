import { request } from "@playwright/test";

/**
 * Identity preflight for the browser suite (N02 / section 11.3): the
 * suite must never run against a stale or unrelated application and pass.
 *
 * Two cases:
 *
 * 1. E2E_BASE_URL is set (an EPHEMERAL stack - a second frontend the caller
 *    built and started, or CI's own job): that server is not something this
 *    run booted, so probe it before any assertion runs -
 *    `/backend/health` must reach a live backend through the rewrite (the
 *    canonical proxy boundary the whole suite depends on), and `/login` must
 *    answer HTML carrying the per-request nonce Content-Security-Policy the
 *    current code enforces. A mismatch aborts the run with the probed URL and
 *    what failed, instead of reporting green against the wrong app.
 *
 * 2. E2E_BASE_URL is unset: playwright.config.ts starts a FRESH server on
 *    :3000 from this checkout's build (reuseExistingServer is false - an
 *    occupied port is a loud startup error, never a silent reuse), so the
 *    app under test is this build by construction and nothing further to
 *    probe here.
 */
export default async function globalSetup(): Promise<void> {
  const base = process.env.E2E_BASE_URL;
  if (!base) {
    return;
  }
  const context = await request.newContext();
  try {
    const health = await context.get(base + "/backend/health");
    if (!health.ok()) {
      throw new Error(
        "E2E identity preflight failed: " + base + "/backend/health answered HTTP "
          + health.status() + ". Is a CURRENT backend running behind this frontend's "
          + "rewrite? The suite would otherwise fail (or pass) against the wrong app."
      );
    }
    const login = await context.get(base + "/login");
    if (!login.ok()) {
      throw new Error(
        "E2E identity preflight failed: " + base + "/login answered HTTP "
          + login.status() + " - expected a live document route."
      );
    }
    const csp = login.headers()["content-security-policy"] ?? "";
    if (!csp.includes("script-src") || !/nonce-[A-Za-z0-9+/=_-]+/.test(csp)) {
      throw new Error(
        "E2E identity preflight failed: " + base + "/login does not carry the "
          + "per-request nonce Content-Security-Policy this checkout enforces "
          + "(proxy.ts). Refusing to run assertions against an unverified application."
      );
    }
    process.stdout.write(
      "E2E identity preflight passed: " + base
        + " proxies a live backend and serves the nonce-CSP document headers.\n"
    );
  } finally {
    await context.dispose();
  }
}
