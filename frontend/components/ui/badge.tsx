import * as React from "react";
import { cva, type VariantProps } from "class-variance-authority";
import { cn } from "../../lib/cn";

const badgeVariants = cva(
  "inline-flex items-center gap-1.5 rounded-sm border px-2 py-0.5 text-[11px] font-medium uppercase tracking-caps",
  {
    variants: {
      tone: {
        info: "border-info-border bg-info-surface text-sky",
        success: "border-success-border bg-success-surface text-mint",
        danger: "border-danger-border bg-danger-surface text-rose",
        warning: "border-warning-border bg-warning-surface text-amber",
        neutral: "border-line bg-ink-800 text-content-muted"
      }
    },
    defaultVariants: { tone: "neutral" }
  }
);

export function Badge({
  className,
  tone,
  ...props
}: React.HTMLAttributes<HTMLSpanElement> & VariantProps<typeof badgeVariants>) {
  return <span className={cn(badgeVariants({ tone }), className)} {...props} />;
}
