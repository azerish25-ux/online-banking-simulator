"use client";

import * as React from "react";
import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { api, clearToken, type User } from "../../lib/api";
import { cn } from "../../lib/cn";

const NAV = [
  { href: "/dashboard", label: "Overview" },
  { href: "/transfers", label: "Transfers" },
  { href: "/activity", label: "Activity" },
  { href: "/beneficiaries", label: "Beneficiaries" },
  { href: "/design", label: "Design system" }
];

export function AppShell({ children }: { children: React.ReactNode }) {
  const pathname = usePathname();
  const router = useRouter();
  const [user, setUser] = React.useState<User | null>(null);
  const navItems = user?.role === "ADMIN"
    ? [...NAV.slice(0, 4), { href: "/admin", label: "Operations" }, ...NAV.slice(4)]
    : NAV;

  React.useEffect(() => {
    api("/v1/auth/me").then(setUser).catch(() => {});
  }, []);

  function logout() {
    clearToken();
    router.push("/login");
  }

  return (
    <div className="min-h-screen bg-ink-900">
      <a href="#main" className="sr-only focus:not-sr-only focus:absolute focus:p-2">
        Skip to content
      </a>
      <div className="mx-auto flex min-h-screen max-w-6xl">
        <aside className="hidden w-56 shrink-0 border-r border-line p-5 md:block" aria-label="Primary">
          <p className="text-lg font-bold tracking-tight">
            Northbank <span className="text-brand-400">·</span>
          </p>
          <p className="muted mt-0.5 text-xs">Enterprise banking</p>
          <nav className="mt-6 flex flex-col gap-1">
            {navItems.map((item) => (
              <Link
                key={item.href}
                href={item.href}
                aria-current={pathname === item.href ? "page" : undefined}
                className={cn(
                  "rounded-lg px-3 py-2 text-sm transition-colors hover:bg-ink-700",
                  pathname === item.href ? "bg-ink-700 text-white" : "text-slate-300"
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
            <button
              onClick={logout}
              className="rounded-lg border border-line px-3 py-1.5 text-sm text-slate-300 hover:bg-ink-700"
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
