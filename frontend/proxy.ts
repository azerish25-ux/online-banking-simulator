import { NextResponse } from "next/server";
import type { NextRequest } from "next/server";
import { Routes } from "./lib/routes";

const IS_DEV = process.env.NODE_ENV === "development";

/**
 * Per-request security headers for HTML documents (F23). Runs as proxy.ts on the Node.js runtime (Next 16 renamed the middleware file convention).. The CSP is the
 * strict one: a fresh nonce per request gates every inline and framework
 * script (Next applies it automatically to the scripts it renders), so no
 * XSS that smuggles a <script> tag into the DOM can execute. React inline
 * style ATTRIBUTES (numeric chart bars etc.) are allowed explicitly via
 * style-src-attr; <style> blocks still need the nonce. 'strict-dynamic'
 * lets the nonced bootstrap load the app's own chunk scripts without an
 * allowlist of every hashed bundle name.
 *
 * Dev needs 'unsafe-eval' (React devtools/source maps) and inline styles
 * (fast refresh); production carries neither.
 */
function securityHeaders(nonce: string): Record<string, string> {
  const csp = [
    "default-src 'self'",
    `script-src 'self' 'nonce-${nonce}' 'strict-dynamic'${IS_DEV ? " 'unsafe-eval'" : ""}`,
    IS_DEV ? "style-src 'self' 'unsafe-inline'" : `style-src 'self' 'nonce-${nonce}'`,
    "style-src-attr 'unsafe-inline'",
    "img-src 'self' data: blob:",
    "font-src 'self'",
    "connect-src 'self'",
    "object-src 'none'",
    "base-uri 'self'",
    "form-action 'self'",
    "frame-ancestors 'none'",
    "upgrade-insecure-requests"
  ].join("; ");
  return {
    "Content-Security-Policy": csp,
    // Belt and braces for browsers without frame-ancestors support.
    "X-Frame-Options": "DENY",
    "X-Content-Type-Options": "nosniff",
    "Referrer-Policy": "strict-origin-when-cross-origin",
    "Permissions-Policy": "camera=(), microphone=(), geolocation=(), payment=(), usb=()",
    "Cross-Origin-Opener-Policy": "same-origin"
  };
}

function roleFromToken(token: string | undefined): string | null {
  if (!token) return null;
  try {
    const payload = token.split(".")[1];
    const json = JSON.parse(
      atob(payload.replace(/-/g, "+").replace(/_/g, "/"))
    );
    return typeof json.role === "string" ? json.role : null;
  } catch {
    return null;
  }
}

export function proxy(request: NextRequest) {
  const token = request.cookies.get("bank_token")?.value;
  const path = request.nextUrl.pathname;

  // Session posture (deliberate split):
  // - bank_token (access, 15m) is readable here FOR ROUTING ONLY. Its cookie
  //   Max-Age mirrors the JWT TTL (lib/api.ts), so an idle session past the
  //   token lifetime may bounce to login even though the HttpOnly refresh
  //   cookie could still repair it - accepted: routing is UX-only.
  // - refresh_token is HttpOnly: invisible to JS and to this middleware.
  // The API re-verifies signature + role on every call; a stolen access
  // token is therefore blast-radius-limited to 15 minutes. A full BFF
  // (no browser tokens at all) is the next step if threat posture demands it.
  // UX-only routing gate: the backend re-verifies the signature and role on every call.
  let redirect: URL | null = null;
  if (path.startsWith("/admin")) {
    if (!token) {
      redirect = new URL(Routes.login, request.url);
    } else if (roleFromToken(token) !== "ADMIN") {
      redirect = new URL(Routes.dashboard, request.url);
    }
  } else {
    const guarded = [Routes.dashboard, Routes.transfers, Routes.activity, Routes.beneficiaries, "/accounts", Routes.notifications, Routes.settings];
    if (!token && guarded.some((p) => path.startsWith(p))) {
      redirect = new URL(Routes.login, request.url);
    }
  }

  // One fresh nonce per request (edge crypto.randomUUID; a v4 UUID satisfies
  // the CSP base64-value charset). Next.js reads the CSP on the request
  // during SSR and applies the nonce to every inline script it renders; the
  // x-nonce header is also exposed for <Script> components.
  const nonce = crypto.randomUUID();
  const headers = securityHeaders(nonce);

  if (redirect) {
    const res = NextResponse.redirect(redirect);
    for (const [key, value] of Object.entries(headers)) {
      res.headers.set(key, value);
    }
    return res;
  }

  const requestHeaders = new Headers(request.headers);
  requestHeaders.set("x-nonce", nonce);
  requestHeaders.set("Content-Security-Policy", headers["Content-Security-Policy"]);
  const res = NextResponse.next({ request: { headers: requestHeaders } });
  for (const [key, value] of Object.entries(headers)) {
    res.headers.set(key, value);
  }
  return res;
}

export const config = {
  matcher: [
    // All document routes except the API proxy, Next internals, and static
    // files - and prefetches (next/link), whose cached payloads must not
    // carry (or consume) a nonce meant for a real navigation.
    {
      source: "/((?!backend|_next/static|_next/image|favicon\\.ico|.*\\.(?:png|jpg|jpeg|svg|webp|gif|ico|txt|xml|css|js|woff2?)$).*)",
      missing: [
        { type: "header", key: "next-router-prefetch" },
        { type: "header", key: "purpose", value: "prefetch" }
      ]
    }
  ]
};
