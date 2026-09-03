import { NextResponse } from "next/server";
import type { NextRequest } from "next/server";
import { Routes } from "./lib/routes";

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

export function middleware(request: NextRequest) {
  const token = request.cookies.get("bank_token")?.value;
  const path = request.nextUrl.pathname;

  // Session posture (deliberate split):
  // - bank_token (access, 15m) is readable here FOR ROUTING ONLY.
  // - refresh_token is HttpOnly: invisible to JS and to this middleware.
  // The API re-verifies signature + role on every call; a stolen access
  // token is therefore blast-radius-limited to 15 minutes. A full BFF
  // (no browser tokens at all) is the next step if threat posture demands it.
  // UX-only routing gate: the backend re-verifies the signature and role on every call.
  if (path.startsWith("/admin")) {
    if (!token) return NextResponse.redirect(new URL(Routes.login, request.url));
    if (roleFromToken(token) !== "ADMIN") return NextResponse.redirect(new URL(Routes.dashboard, request.url));
    return NextResponse.next();
  }

  const guarded = [Routes.dashboard, Routes.transfers, Routes.activity, Routes.beneficiaries, "/accounts", Routes.notifications];
  if (!token && guarded.some((p) => path.startsWith(p))) {
    return NextResponse.redirect(new URL(Routes.login, request.url));
  }
  return NextResponse.next();
}

export const config = { matcher: ["/dashboard/:path*", "/transfers/:path*", "/activity/:path*", "/beneficiaries/:path*", "/accounts/:path*", "/notifications/:path*", "/admin/:path*"] };
