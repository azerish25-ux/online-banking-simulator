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
import { BrandName, DemoTagline } from "../../lib/brand";
import { usePageTitle } from "../../lib/page-title";
import { AboutDemoLink } from "./demo-seam";

const NAV = [
  { href: Routes.dashboard, label: "Overview" },
  { href: Routes.transfers, label: "Transfers" },
  { href: Routes.activity, label: "Activity" },
  { href: Routes.beneficiaries, label: "Beneficiaries" },
  { href: Routes.notifications, label: "Notifications" },
  { href: Routes.settings, label: "Security" }
];

/**
 * The primary nav renders twice - a vertical rail on desktop, a scrollable
 * bar under the brand on small screens - because the chrome genuinely
 * differs, but the item list, the active-state rule, and aria-current are one
 * thing and must not be maintained as two maps.
 */
function PrimaryNav({ items, pathname, variant }: {
  items: { href: string; label: string }[];
  pathname: string;
  variant: "rail" | "bar";
}) {
  const rail = variant === "rail";
  return (
    <nav
      aria-label="Primary"
      className={
        rail
          ? "mt-6 flex flex-1 flex-col gap-1"
          : "mt-3 flex gap-1 overflow-x-auto pb-0.5 md:hidden"
      }
    >
      {items.map((item) => {
        const active = pathname === item.href;
        return (
          <Link
            key={item.href}
            href={item.href}
            aria-current={active ? "page" : undefined}
            className={cn(
              "rounded-md text-sm transition-colors hover:bg-ink-800",
              rail ? "px-3 py-2" : "whitespace-nowrap px-3 py-1.5",
              active ? "bg-ink-800 text-content" : "text-content-muted",
              rail && active && "shadow-[inset_2px_0_0_0_var(--brand)]"
            )}
          >
            {item.label}
          </Link>
        );
      })}
    </nav>
  );
}

export function AppShell({ children }: { children: React.ReactNode }) {
  const pathname = usePathname();
  const router = useRouter();
  const qc = useQueryClient();

  usePageTitle();
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
        <aside className="hidden w-56 shrink-0 flex-col border-r border-line p-5 md:flex" aria-label="Primary">
          <p className="display text-xl font-semibold tracking-tight">{BrandName}</p>
          <p className="label mt-1 text-brass-400">{DemoTagline}</p>
          <PrimaryNav items={navItems} pathname={pathname} variant="rail" />
          <AboutDemoLink className="mt-6 rounded-md px-3 py-1.5 text-xs text-content-muted transition-colors hover:bg-ink-800 hover:text-content" />
        </aside>
        <div className="flex min-w-0 flex-1 flex-col">
          <header className="border-b border-line px-5 py-3">
            <div className="flex items-center justify-between gap-3">
              {/* On small screens the sidebar is gone, so the brand lives here. */}
              <p className="display min-w-0 truncate text-lg font-semibold tracking-tight md:hidden">
                <Link href={Routes.dashboard} className="block truncate">{BrandName}</Link>
              </p>
              <div className="hidden flex-1 text-sm text-content-muted md:block">
                {user ? (
                  <>
                    {user.fullName} · <span className="mono">{user.email}</span>
                  </>
                ) : (
                  "..."
                )}
              </div>
              <div className="flex shrink-0 items-center gap-2">
                <Link href={Routes.notifications} aria-label={"Notifications" + (unreadCount > 0 ? ", " + unreadCount + " unread" : "")} className="relative rounded-md border border-line px-3 py-1.5 text-sm text-content-soft hover:bg-ink-700">
                  <Bell size={16} aria-hidden="true" />{unreadCount > 0 && <span className="absolute -right-1.5 -top-1.5 rounded-full bg-brass-500 px-1.5 text-[11px] font-bold text-ink-950">{unreadCount}</span>}
                </Link>
                <button
                  onClick={() => void logout()}
                  className="rounded-md border border-line px-3 py-1.5 text-sm text-content-soft hover:bg-ink-700"
                >
                  Log out
                </button>
              </div>
            </div>
            {/* Scrollable secondary nav under the brand bar - no overflow at
                360px even with the Operations item added for admins. */}
            <PrimaryNav items={navItems} pathname={pathname} variant="bar" />
          </header>
          <main id="main" className="flex-1 p-5">
            {children}
          </main>
        </div>
      </div>
    </div>
  );
}
