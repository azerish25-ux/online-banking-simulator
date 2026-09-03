import { NextResponse } from "next/server";
import type { NextRequest } from "next/server";

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

  // UX-only routing gate: the backend re-verifies the signature and role on every call.
  if (path.startsWith("/admin")) {
    if (!token) return NextResponse.redirect(new URL("/login", request.url));
    if (roleFromToken(token) !== "ADMIN") return NextResponse.redirect(new URL("/dashboard", request.url));
    return NextResponse.next();
  }

  const guarded = ["/dashboard", "/transfers", "/activity", "/beneficiaries", "/accounts", "/notifications"];
  if (!token && guarded.some((p) => path.startsWith(p))) {
    return NextResponse.redirect(new URL("/login", request.url));
  }
  return NextResponse.next();
}

export const config = { matcher: ["/dashboard/:path*", "/transfers/:path*", "/activity/:path*", "/beneficiaries/:path*", "/accounts/:path*", "/notifications/:path*", "/admin/:path*"] };
