import * as React from "react";
import Link from "next/link";
import { Card } from "../ui/card";

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
  return (
    <div className="flex min-h-screen items-center justify-center bg-ink-900 p-4">
      <div className="w-full max-w-md">
        <p className="mb-6 text-center text-xl font-bold tracking-tight">
          <Link href="/">Northbank <span className="text-brand-400">·</span></Link>
        </p>
        <Card>
          <h1 className="text-xl font-semibold tracking-tight">{title}</h1>
          {subtitle ? <p className="muted mt-1 text-sm">{subtitle}</p> : null}
          <div className="mt-5">{children}</div>
        </Card>
        {footer ? <p className="muted mt-4 text-center text-sm">{footer}</p> : null}
      </div>
    </div>
  );
}
