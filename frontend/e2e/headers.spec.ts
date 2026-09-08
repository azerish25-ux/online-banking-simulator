import { expect, test } from "@playwright/test";

/**
 * Real-browser witness. An earlier prod smoke curled headers and grepped
 * for nonces; this spec walks the LIVE pages in a real browser against the
 * ephemeral production build (next start, NOT next dev - dev relaxes the CSP
 * with unsafe-eval/unsafe-inline):
 *
 *   1. Every HTML response carries the full security header set.
 *   2. The CSP nonce on the response header is the very nonce applied to the
 *      document's own scripts - extract it from the header and require it in
 *      the served HTML.
 *   3. Production CSP allows no unsafe-eval / unsafe-inline for scripts.
 *   4. Static _next assets carry the static header set too (the middleware
 *      matcher skips them; next.config headers() must cover them).
 */
test("live pages carry the full header set with a per-request nonce matching the document", async ({
  page
}) => {
  const res = await page.goto("/login");
  expect(res?.status()).toBe(200);

  const headers = res!.headers();
  const csp = headers["content-security-policy"];
  expect(csp, "Content-Security-Policy must be present").toBeTruthy();

  // The strict production posture, asserted on the script-src directive.
  const scriptSrc = csp!.split(";").find((d) => d.trim().startsWith("script-src"));
  expect(scriptSrc).toContain("'strict-dynamic'");
  expect(scriptSrc).toContain("'nonce-");
  expect(scriptSrc).not.toContain("'unsafe-eval'");
  expect(scriptSrc).not.toContain("'unsafe-inline'");
  expect(csp).toContain("object-src 'none'");
  expect(csp).toContain("frame-ancestors 'none'");
  expect(csp).toContain("base-uri 'self'");
  expect(csp).toContain("form-action 'self'");

  expect(headers["x-frame-options"]).toBe("DENY");
  expect(headers["x-content-type-options"]).toBe("nosniff");
  expect(headers["referrer-policy"]).toBe("strict-origin-when-cross-origin");
  expect(headers["permissions-policy"]).toContain("geolocation=()");
  expect(headers["cross-origin-opener-policy"]).toBe("same-origin");

  // The document's scripts must carry the very nonce named in its own CSP.
  const nonce = csp!.match(/nonce-([A-Za-z0-9+/=\-_]+)/)?.[1];
  expect(nonce, "CSP must carry a nonce").toBeTruthy();
  const html = await res!.text();
  expect(html).toContain(`nonce="${nonce}"`);

  // Static assets skip the nonce middleware but must still ship the header set.
  const assetUrl = html.match(/src="(\/_next\/static\/[^"]+\.js)"/)?.[1];
  expect(assetUrl, "the page must load at least one _next/static script").toBeTruthy();
  const assetRes = await page.request.get(assetUrl as string);
  expect(assetRes.status()).toBe(200);
  expect(assetRes.headers()["x-content-type-options"]).toBe("nosniff");
  expect(assetRes.headers()["x-frame-options"]).toBe("DENY");
  expect(assetRes.headers()["content-security-policy"]).toBeUndefined(); // no per-request nonce on assets
});
