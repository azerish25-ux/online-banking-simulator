"use client";

import * as React from "react";
import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useQueryClient } from "@tanstack/react-query";
import { broadcastLogout, clearToken } from "../../lib/api";
import { useMe, useUnreadCount } from "../../lib/queries";
import { Bell, Menu, X } from "lucide-react";
import { cn } from "../../lib/cn";
import { Routes } from "../../lib/routes";
import { BrandName, DemoTagline } from "../../lib/brand";
import { usePageTitle } from "../../lib/page-title";
import { AboutDemoLink } from "./demo-seam";

/**
 * Navigation vocabulary ( section 13): Overview, Transfers, Activity,
 * Recipients (the existing /beneficiaries URL is kept), Notifications,
 * Security, and - for the operator role only - Operations.
 */
type NavItem = { href: string; section: string; label: string };

const NAV: NavItem[] = [
  { href: Routes.dashboard, section: "/dashboard", label: "Overview" },
  { href: Routes.transfers, section: "/transfers", label: "Transfers" },
  { href: Routes.activity, section: "/activity", label: "Activity" },
  { href: Routes.beneficiaries, section: "/beneficiaries", label: "Recipients" },
  { href: Routes.notifications, section: "/notifications", label: "Notifications" },
  { href: Routes.settings, section: "/settings", label: "Security" }
];

/**
 * Segment-aware active matching: a descendant route keeps its parent section
 * highlighted - /transfers/receipt/{id} is still "Transfers", /accounts/{id}
 * is still "Overview". Exact-prefix-with-boundary matching replaces the old
 * unsafe arbitrary prefix AND the exact-only rule that left receipt/detail
 * pages with no active item (app-shell section 13 anchor).
 */
function isActive(item: NavItem, pathname: string): boolean {
  if (pathname === item.section) return true;
  if (pathname.startsWith(item.section + "/")) return true;
  // Account-detail pages belong to Overview.
  return item.section === Routes.dashboard && pathname.startsWith(Routes.account("").slice(0, -1));
}

function NavLink({
  item,
  pathname,
  onNavigate,
  variant
}: {
  item: NavItem;
  pathname: string;
  onNavigate?: () => void;
  variant: "rail" | "drawer";
}) {
  const active = isActive(item, pathname);
  return (
    <Link
      href={item.href}
      onClick={onNavigate}
      aria-current={active ? "page" : undefined}
      className={cn(
        "rounded text-sm transition-colors",
        variant === "rail" ? "px-3 py-2" : "px-3 py-2.5",
        active
          ? "bg-surface-subtle font-medium text-content shadow-[inset_3px_0_0_0_var(--action)]"
          : "text-content-secondary hover:bg-surface-subtle hover:text-content"
      )}
    >
      {item.label}
    </Link>
  );
}

export function AppShell({ children }: { children: React.ReactNode }) {
  const pathname = usePathname();
  const router = useRouter();
  const qc = useQueryClient();
  const [drawerOpen, setDrawerOpen] = React.useState(false);
  const closeRef = React.useRef<HTMLButtonElement>(null);
  const menuRef = React.useRef<HTMLButtonElement>(null);

  usePageTitle();
  // Cached session + unread badge: no refetch churn on navigation.
  const me = useMe();
  const unread = useUnreadCount();
  const user = me.data ?? null;
  const unreadCount = unread.data ?? 0;
  const navItems = user?.role === "ADMIN"
    ? [...NAV.slice(0, 4), { href: Routes.admin, section: "/admin", label: "Operations" }, ...NAV.slice(4)]
    : NAV;

  // Drawer lifecycle: Escape closes, navigation closes, focus returns to the
  // menu button. The drawer never unmounts page state - it is an overlay, so
  // opening it can never discard a form or an unresolved operation (section 13).
  React.useEffect(() => {
    if (!drawerOpen) return;
    closeRef.current?.focus();
    function onKey(e: KeyboardEvent) {
      if (e.key === "Escape") setDrawerOpen(false);
    }
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [drawerOpen]);

  function closeDrawer() {
    setDrawerOpen(false);
    menuRef.current?.focus();
  }

  async function logout() {
    // Revoke server-side first - clearing the cookie alone used to leave the
    // refresh token valid for its full lifetime.
    try {
      await fetch("/backend/v1/auth/logout", { method: "POST" });
    } finally {
      clearToken();
      qc.clear();
      // Sibling tabs share the cookie jar but not this React tree: tell them
      // to evict their cached user data and leave the app too (F22).
      broadcastLogout();
      router.push(Routes.login);
    }
  }

  const notifications = (
    <Link
      href={Routes.notifications}
      aria-label={"Notifications" + (unreadCount > 0 ? ", " + unreadCount + " unread" : "")}
      className="relative rounded border border-divider bg-surface px-3 py-2 text-content-secondary hover:bg-surface-subtle"
    >
      <Bell size={16} aria-hidden="true" />
      {unreadCount > 0 && (
        <span className="absolute -right-1.5 -top-1.5 flex h-5 min-w-5 items-center justify-center rounded-full bg-danger px-1 text-[11px] font-semibold text-white">
          {unreadCount > 99 ? "99+" : unreadCount}
        </span>
      )}
    </Link>
  );

  return (
    <div className="min-h-screen bg-workspace">
      <a href="#main" className="sr-only focus:not-sr-only focus:absolute focus:z-50 focus:p-2 focus:bg-surface">
        Skip to content
      </a>
      <div className="mx-auto flex min-h-screen max-w-[1440px]">
        {/* Desktop rail: 240px, brand + tagline + tasks + demo seam. */}
        <aside
          aria-label="Primary"
          className="hidden w-60 shrink-0 flex-col border-r border-divider bg-surface px-4 py-5 md:flex"
        >
          <p className="px-2 text-lg font-semibold tracking-tight">
            <Link href={Routes.dashboard}>{BrandName}</Link>
          </p>
          <p className="label px-2">{DemoTagline}</p>
          <nav aria-label="Primary tasks" className="mt-6 flex flex-1 flex-col gap-1">
            {navItems.map((item) => (
              <NavLink key={item.section} item={item} pathname={pathname} variant="rail" />
            ))}
          </nav>
          <AboutDemoLink className="mt-6 rounded px-3 py-2 text-sm text-content-secondary transition-colors hover:bg-surface-subtle hover:text-content" />
        </aside>

        <div className="flex min-w-0 flex-1 flex-col">
          {/* Header: ~64px on desktop, compact on narrow. */}
          <header className="border-b border-divider bg-surface">
            <div className="flex h-16 items-center justify-between gap-3 px-6 md:px-8">
              <div className="flex min-w-0 items-center gap-2">
                <button
                  ref={menuRef}
                  type="button"
                  aria-label="Open navigation menu"
                  aria-expanded={drawerOpen}
                  onClick={() => setDrawerOpen(true)}
                  className="rounded border border-divider bg-surface px-2.5 py-2 text-content-secondary hover:bg-surface-subtle md:hidden"
                >
                  <Menu size={18} aria-hidden="true" />
                </button>
                <p className="truncate text-base font-semibold tracking-tight md:hidden">{BrandName}</p>
                <p className="hidden text-sm text-content-secondary md:block">
                  {user ? (
                    <>
                      {user.fullName} · <span className="mono">{user.email}</span>
                    </>
                  ) : (
                    "..."
                  )}
                </p>
              </div>
              <div className="flex shrink-0 items-center gap-2">
                {notifications}
                <button
                  onClick={() => void logout()}
                  className="rounded border border-divider bg-surface px-3 py-2 text-sm text-content-secondary hover:bg-surface-subtle"
                >
                  Log out
                </button>
              </div>
            </div>
          </header>

          <main id="main" className="flex-1 p-6 md:p-8">
            {children}
          </main>
        </div>
      </div>

      {/* Mobile drawer: keyboard-operable menu replacing the overflow strip. */}
      {drawerOpen && (
        <div className="fixed inset-0 z-50 md:hidden" role="presentation">
          <div
            className="absolute inset-0 bg-scrim/60 motion-safe:animate-overlay-in"
            onClick={closeDrawer}
          />
          <div
            role="dialog"
            aria-modal="true"
            aria-label="Navigation menu"
            className="absolute inset-y-0 left-0 flex w-72 max-w-[85vw] flex-col bg-surface p-4 shadow-dialog motion-safe:animate-dialog-in"
          >
            <div className="flex items-center justify-between gap-2">
              <p className="truncate text-lg font-semibold tracking-tight">{BrandName}</p>
              <button
                ref={closeRef}
                type="button"
                aria-label="Close navigation menu"
                onClick={closeDrawer}
                className="rounded p-2 text-content-secondary hover:bg-surface-subtle hover:text-content"
              >
                <X size={18} aria-hidden="true" />
              </button>
            </div>
            <p className="label mt-1">{DemoTagline}</p>
            <nav aria-label="Primary tasks" className="mt-5 flex flex-1 flex-col gap-1">
              {navItems.map((item) => (
                <NavLink
                  key={item.section}
                  item={item}
                  pathname={pathname}
                  variant="drawer"
                  onNavigate={closeDrawer}
                />
              ))}
            </nav>
            <AboutDemoLink className="mt-4 rounded px-3 py-2 text-sm text-content-secondary hover:bg-surface-subtle hover:text-content" />
          </div>
        </div>
      )}
    </div>
  );
}
