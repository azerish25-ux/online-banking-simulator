"use client";

import * as React from "react";
import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useQueryClient } from "@tanstack/react-query";
import { clearToken } from "../../lib/api";
import { useMe, useUnreadCount } from "../../lib/queries";
import { Bell } from "lucide-react";
import { cn } from "../../lib/cn";
import { Routes } from "../../lib/routes";
import { BrandName } from "../../lib/brand";

const NAV = [
  { href: Routes.dashboard, label: "Overview" },
  { href: Routes.transfers, label: "Transfers" },
  { href: Routes.activity, label: "Activity" },
  { href: Routes.beneficiaries, label: "Beneficiaries" },
  { href: Routes.notifications, label: "Notifications" },
  { href: Routes.settings, label: "Security" }
];

// Browser-tab titles per route. The shell owns them so every page reports
// where the user is instead of just the brand name; order matters (longer
// prefixes first so /accounts/123 matches before a bare /accounts rule).
const ROUTE_TITLES: Array<{ prefix: string; label: string }> = [
  { prefix: Routes.admin, label: "Operations" },
  { prefix: Routes.transfers, label: "Send money" },
  { prefix: Routes.activity, label: "Activity" },
  { prefix: Routes.beneficiaries, label: "Beneficiaries" },
  { prefix: Routes.notifications, label: "Notifications" },
  { prefix: Routes.settings, label: "Security" },
  { prefix: "/accounts/", label: "Account" },
  { prefix: Routes.dashboard, label: "Overview" }
];

export function AppShell({ children }: { children: React.ReactNode }) {
  const pathname = usePathname();
  const router = useRouter();
  const qc = useQueryClient();

  React.useEffect(() => {
    const match = ROUTE_TITLES.find((r) => pathname.startsWith(r.prefix));
    document.title = match ? BrandName + " · " + match.label : BrandName;
  }, [pathname]);
  // Cached session + unread badge: no refetch churn on navigation.
  const me = useMe();
  const unread = useUnreadCount();
  const user = me.data ?? null;
  const unreadCount = unread.data ?? 0;
  const navItems = user?.role === "ADMIN"
    ? [...NAV.slice(0, 4), { href: Routes.admin, label: "Operations" }, ...NAV.slice(4)]
    : NAV;

  async function logout() {
    // Revoke server-side first - clearing the cookie alone used to leave the
    // refresh token valid for its full lifetime.
    try {
      await fetch("/backend/v1/auth/logout", { method: "POST" });
    } finally {
      clearToken();
      qc.clear();
      router.push(Routes.login);
    }
  }

  return (
    <div className="min-h-screen bg-ink-900">
      <a href="#main" className="sr-only focus:not-sr-only focus:absolute focus:p-2">
        Skip to content
      </a>
      <div className="mx-auto flex min-h-screen max-w-6xl">
        <aside className="hidden w-56 shrink-0 border-r border-line p-5 md:block" aria-label="Primary">
          <p className="display text-xl font-semibold tracking-tight">{BrandName}</p>
          <p className="caps muted mt-1 normal-case text-brass-400">Simulator demo · no real money</p>
          <nav className="mt-6 flex flex-col gap-1">
            {navItems.map((item) => (
              <Link
                key={item.href}
                href={item.href}
                aria-current={pathname === item.href ? "page" : undefined}
                className={cn(
                  "rounded-md px-3 py-2 text-sm transition-colors hover:bg-ink-800",
                  pathname === item.href
                    ? "bg-ink-800 text-white shadow-[inset_2px_0_0_0_var(--brand)]"
                    : "text-slate-400"
                )}
              >
                {item.label}
              </Link>
            ))}
          </nav>
        </aside>
        <div className="flex min-w-0 flex-1 flex-col">
          <header className="flex items-center justify-between border-b border-line px-5 py-3">
            <nav className="flex gap-4 md:hidden" aria-label="Primary">
              {navItems.map((item) => (
                <Link
                  key={item.href}
                  href={item.href}
                  className={cn("text-sm", pathname === item.href ? "text-white" : "text-slate-400")}
                >
                  {item.label}
                </Link>
              ))}
            </nav>
            <div className="muted hidden text-sm md:block">
              {user ? (
                <>
                  {user.fullName} · <span className="mono">{user.email}</span>
                </>
              ) : (
                "..."
              )}
            </div>
            <Link href={Routes.notifications} aria-label={"Notifications" + (unreadCount > 0 ? ", " + unreadCount + " unread" : "")} className="relative rounded-md border border-line px-3 py-1.5 text-sm text-slate-300 hover:bg-ink-700">
              <Bell size={16} aria-hidden="true" />{unreadCount > 0 && <span className="absolute -right-1.5 -top-1.5 rounded-full bg-brass-500 px-1.5 text-[11px] font-bold text-white">{unreadCount}</span>}
            </Link>
            <button
              onClick={() => void logout()}
              className="rounded-md border border-line px-3 py-1.5 text-sm text-slate-300 hover:bg-ink-700"
            >
              Log out
            </button>
          </header>
          <main id="main" className="flex-1 p-5">
            {children}
          </main>
        </div>
      </div>
    </div>
  );
}
