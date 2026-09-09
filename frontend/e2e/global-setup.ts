import { request } from "@playwright/test";

/**
 * Identity preflight for the browser suite: the suite must
 * never seed or write against a stale, leftover or unrelated application and
 * pass green. A health body and a CSP header do not identify the actual
 * database or source build, so before ANY seeding step this setup demands a
 * RUN-SPECIFIC MARKER from the backend's marker-gated identity endpoint
 * (`/api/e2e/identity`), which also names the live database connection.
 *
 * The backend must be booted with the SAME `E2E_MARKER` value this run uses
 * (CI sets one per job). A leftover server from an earlier run: the
 * documented two-deposit incident: carries an older or absent marker and is
 * REFUSED here, before the first write.
 *
 * Two stack shapes:
 *
 * 1. E2E_BASE_URL is set (an EPHEMERAL stack the caller built and started):
 *    the probe goes THROUGH that frontend's `/backend` rewrite, proving the
 *    same backend the browser will actually hit, and `/login` must answer the
 *    per-request nonce CSP this checkout's proxy enforces (frontend build
 *    identity).
 *
 * 2. E2E_BASE_URL is unset: playwright.config.ts starts a FRESH frontend on
 *    :3000 from this checkout's build (`reuseExistingServer: false`: an
 *    occupied port is a loud startup error, never silent reuse), so the
 *    frontend is this build by construction. The backend probe goes DIRECTLY
 *    to the backend origin (BACKEND_URL, default :8080: the same default the
 *    frontend proxy rewrites to).
 */
export default async function globalSetup(): Promise<void> {
  // Backend-less runs (smoke.spec.ts checks the built frontend only) have no
  // backend to identify; the frontend is still this checkout's build because
  // the webServer refuses to reuse a stale :3000. Everything else demands the
  // run marker before any seed or destructive step.
  if (process.env.E2E_BACKENDLESS === "1") {
    process.stdout.write(
      "E2E preflight (backend-less): no database to verify; the frontend under "
        + "test is the webServer this config boots from this checkout.\n"
    );
    return;
  }
  const marker = process.env.E2E_MARKER;
  if (!marker) {
    throw new Error(
      "E2E identity preflight requires a run-specific E2E_MARKER: boot the backend "
        + "with E2E_MARKER=<this-run's marker> and export the SAME value for Playwright. "
        + "Without it the suite cannot prove it is talking to THIS run's database, not a "
        + "stale or working one."
    );
  }

  const base = process.env.E2E_BASE_URL;
  // The identity endpoint is always reached through the SAME path the
  // browser's API calls use: the frontend rewrite when one is under test
  // (`/backend/*` → backend `/api/*`), otherwise the backend origin the
  // local proxy targets. The controller lives at `/api/e2e/identity`, so the
  // direct (no-frontend) probe carries the `/api` prefix.
  const identityUrl = base
    ? base + "/backend/e2e/identity"
    : (process.env.BACKEND_URL ?? "http://localhost:8080") + "/api/e2e/identity";

  const context = await request.newContext();
  try {
    const probe = await context.get(identityUrl, {
      headers: { "X-E2E-Marker": marker }
    });
    if (probe.status() === 403 || probe.status() === 404) {
      throw new Error(
        "E2E identity preflight REFUSED at " + identityUrl + " (HTTP " + probe.status()
          + "): the backend there was not booted with E2E_MARKER=" + marker
          + ". A stale or foreign server (e.g. a leftover from an earlier run) must never "
          + "receive this suite's seeds: boot the intended backend with the current marker."
      );
    }
    if (!probe.ok()) {
      throw new Error(
        "E2E identity preflight failed: " + identityUrl + " answered HTTP "
          + probe.status() + ". Is a CURRENT backend running?"
      );
    }
    const identity = (await probe.json()) as {
      marker?: string;
      testMode?: boolean;
      version?: string;
      db?: { product?: string; url?: string; user?: string };
    };
    if (identity.marker !== marker || identity.testMode !== true || !identity.db) {
      throw new Error(
        "E2E identity preflight failed: " + identityUrl + " did not confirm THIS run's "
          + "marker/test-mode/database identity (" + JSON.stringify(identity) + ")."
      );
    }
    // Optional hard gate on the database the run expects (CI sets the
    // disposable name; local disposable runs should too).
    const expectedDb = process.env.E2E_EXPECT_DB;
    if (expectedDb && !String(identity.db.url ?? "").includes(expectedDb)) {
      throw new Error(
        "E2E identity preflight refused the DATABASE: expected a disposable database "
          + "whose URL contains '" + expectedDb + "' but " + identityUrl + " reports "
          + identity.db.url + ". Refusing before the first write."
      );
    }
    process.stdout.write(
      "E2E identity preflight passed: marker " + marker + " confirmed at " + identityUrl
        + " (backend " + identity.version + " on " + identity.db.product + " "
        + identity.db.url + " as " + identity.db.user + ").\n"
    );

    // Frontend build identity is only probeable when a frontend is already
    // running (ephemeral stack). Local runs boot :3000 from this checkout.
    if (base) {
      const login = await context.get(base + "/login");
      if (!login.ok()) {
        throw new Error(
          "E2E identity preflight failed: " + base + "/login answered HTTP "
            + login.status() + ": expected a live document route."
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
    }
  } finally {
    await context.dispose();
  }
}
