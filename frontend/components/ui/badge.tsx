import * as React from "react";
import { cva, type VariantProps } from "class-variance-authority";
import { cn } from "../../lib/cn";

const badgeVariants = cva(
  "inline-flex items-center gap-1.5 rounded border px-2 py-0.5 text-xs font-medium",
  {
    variants: {
      tone: {
        info: "border-info-border bg-info-surface text-info",
        success: "border-success-border bg-success-surface text-success",
        danger: "border-danger-border bg-danger-surface text-danger",
        warning: "border-warning-border bg-warning-surface text-warning",
        neutral: "border-divider bg-surface-subtle text-content-secondary"
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
