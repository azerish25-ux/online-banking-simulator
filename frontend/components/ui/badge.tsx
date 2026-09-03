import * as React from "react";
import { cva, type VariantProps } from "class-variance-authority";
import { cn } from "../../lib/cn";

const badgeVariants = cva(
  "inline-flex items-center gap-1.5 rounded-sm border px-2 py-0.5 text-[11px] font-medium uppercase tracking-caps",
  {
    variants: {
      tone: {
        info: "border-sky-900/60 bg-sky-950/40 text-sky",
        success: "border-emerald-900/60 bg-emerald-950/40 text-mint",
        danger: "border-red-900/60 bg-red-950/40 text-rose",
        warning: "border-amber-800/60 bg-amber-950/40 text-amber",
        neutral: "border-line bg-ink-800 text-slate-400"
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
