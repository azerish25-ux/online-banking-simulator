import * as React from "react";
import { cva, type VariantProps } from "class-variance-authority";
import { cn } from "../../lib/cn";

const badgeVariants = cva(
  "inline-flex items-center rounded-full border px-2.5 py-0.5 text-xs font-medium",
  {
    variants: {
      tone: {
        info: "border-brand-900 bg-brand-900/40 text-brand-300",
        success: "border-emerald-900 bg-emerald-950 text-mint",
        danger: "border-red-900 bg-red-950 text-rose",
        neutral: "border-line bg-ink-700 text-slate-300"
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
