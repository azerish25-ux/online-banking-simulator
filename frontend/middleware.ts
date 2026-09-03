import { NextResponse } from "next/server";
import type { NextRequest } from "next/server";

export function middleware(request: NextRequest) {
  const token = request.cookies.get("bank_token")?.value;
  const guarded = ["/dashboard", "/transfers", "/activity", "/beneficiaries"];
  if (!token && guarded.some((p) => request.nextUrl.pathname.startsWith(p))) {
    return NextResponse.redirect(new URL("/login", request.url));
  }
  return NextResponse.next();
}

export const config = { matcher: ["/dashboard/:path*", "/transfers/:path*", "/activity/:path*", "/beneficiaries/:path*"] };
