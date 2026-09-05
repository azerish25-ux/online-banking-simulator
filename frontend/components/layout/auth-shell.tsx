"use client";

import * as React from "react";
import Link from "next/link";
import { BrandName, DemoTagline } from "../../lib/brand";
import { usePageTitle } from "../../lib/page-title";
import { Card } from "../ui/card";
import { AboutDemoLink } from "./demo-seam";

export function AuthShell({
  title,
  subtitle,
  children,
  footer
}: {
  title: string;
  subtitle?: string;
  children: React.ReactNode;
  footer?: React.ReactNode;
}) {
  // The shared page-title hook maps this route to the same tab title as the
  // h1 below ("Log in", "Create your account", "Two-factor check").
  usePageTitle();

  return (
    <div className="flex min-h-screen items-center justify-center bg-ink-900 p-4">
      <div className="w-full max-w-md">
        <p className="display mb-6 text-center text-2xl font-semibold tracking-tight">
          <Link href="/">{BrandName}</Link>
        </p>
        <p className="label -mt-4 mb-6 text-center text-brass-400">{DemoTagline}</p>
        <Card>
          <h1 className="display text-xl font-semibold tracking-tight">{title}</h1>
          {subtitle ? <p className="muted mt-1 text-sm">{subtitle}</p> : null}
          <div className="mt-5">{children}</div>
        </Card>
        {footer ? <p className="muted mt-4 text-center text-sm">{footer}</p> : null}
        <p className="mt-3 text-center text-xs">
          <AboutDemoLink className="text-content-muted hover:text-content" />
        </p>
      </div>
    </div>
  );
}
