import * as React from "react";
import { usePathname } from "next/navigation";
import { BrandName } from "./brand";

/**
 * Single owner of browser-tab titles. Every shell used to re-implement the
 * same effect with its own copy of a route table; now one table feeds both,
 * so every page reports where the user is instead of just the brand name.
 * Order matters: longer prefixes first, so /login/mfa matches before /login
 * and /accounts/123 matches before any bare rule.
 */
const ROUTE_TITLES: Array<{ prefix: string; label: string }> = [
  { prefix: "/login/mfa", label: "Two-factor check" },
  { prefix: "/login", label: "Log in" },
  { prefix: "/register", label: "Create your account" },
  { prefix: "/admin", label: "Operations" },
  { prefix: "/transfers", label: "Send money" },
  { prefix: "/activity", label: "Activity" },
  { prefix: "/beneficiaries", label: "Beneficiaries" },
  { prefix: "/notifications", label: "Notifications" },
  { prefix: "/settings", label: "Security" },
  { prefix: "/accounts/", label: "Account" },
  { prefix: "/dashboard", label: "Overview" }
];

/** The full tab title for a pathname: "Brand · Section", or the brand alone. */
export function tabTitleFor(pathname: string): string {
  const match = ROUTE_TITLES.find((r) => pathname.startsWith(r.prefix));
  return match ? BrandName + " · " + match.label : BrandName;
}

/** Keep the browser tab in step with the route, from any shell. */
export function usePageTitle(): void {
  const pathname = usePathname();
  React.useEffect(() => {
    document.title = tabTitleFor(pathname);
  }, [pathname]);
}
