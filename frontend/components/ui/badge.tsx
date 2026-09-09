import * as React from "react";
import { cva, type VariantProps } from "class-variance-authority";
import { cn } from "../../lib/cn";

/**
 * Status notation, not a pill: square corners, a hairline bracket, monospaced
 * label: the way a booking system marks a row's state. Text travels in the
 * tone; the surface stays the section's white so statuses read as notation
 * rather than stickers.
 */
const badgeVariants = cva(
  "inline-flex items-center gap-1.5 border px-1.5 py-0.5 mono leading-4",
  {
    variants: {
      tone: {
        info: "border-info-border text-info",
        success: "border-success-border text-success",
        danger: "border-danger-border text-danger",
        warning: "border-warning-border text-warning",
        neutral: "border-divider text-content-secondary"
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
