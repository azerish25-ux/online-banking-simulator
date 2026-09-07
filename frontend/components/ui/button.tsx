import * as React from "react";
import { cva, type VariantProps } from "class-variance-authority";
import { cn } from "../../lib/cn";

const buttonVariants = cva(
  "inline-flex items-center justify-center gap-2 rounded border text-sm font-medium transition-colors duration-150 disabled:pointer-events-none disabled:opacity-50",
  {
    variants: {
      variant: {
        primary: "border-transparent bg-action text-white hover:bg-action-hover font-semibold",
        secondary: "border-control bg-surface text-content hover:bg-surface-subtle",
        ghost: "border-transparent text-action hover:bg-surface-subtle",
        danger: "border-danger-border bg-danger-surface text-danger hover:bg-danger-strong"
      },
      size: {
        sm: "h-8 px-3",
        // 44px primary-control height is this project's design target (section 17).
        md: "h-11 px-5",
        lg: "h-12 px-6 text-base"
      }
    },
    defaultVariants: { variant: "primary", size: "md" }
  }
);

export interface ButtonProps
  extends React.ButtonHTMLAttributes<HTMLButtonElement>,
    VariantProps<typeof buttonVariants> {}

export function Button({ className, variant, size, ...props }: ButtonProps) {
  return <button className={cn(buttonVariants({ variant, size }), className)} {...props} />;
}
