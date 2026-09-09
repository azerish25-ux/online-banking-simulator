import * as React from "react";
import { cn } from "../../lib/cn";

/**
 * A section of the record: white surface, divider boundary, square corners,
 * no shadow. Content sits flush (px-4 py-3): density comes from the grid,
 * not from floating chrome.
 */
export function Card({ className, ...props }: React.HTMLAttributes<HTMLDivElement>) {
  return <section className={cn("panel", className)} {...props} />;
}

/** A section heading row: title and actions on one divider-drawn band. */
export function CardHead({ className, ...props }: React.HTMLAttributes<HTMLDivElement>) {
  return <div className={cn("flex flex-wrap items-center justify-between gap-x-4 gap-y-2 border-b border-divider px-4 py-2.5", className)} {...props} />;
}

export function CardTitle({ className, ...props }: React.HTMLAttributes<HTMLHeadingElement>) {
  // Section title: 16/22, same size as body: hierarchy via weight only.
  return <h2 className={cn("text-base leading-[22px] font-semibold", className)} {...props} />;
}

export function CardBody({ className, ...props }: React.HTMLAttributes<HTMLDivElement>) {
  return <div className={cn("px-4 py-3", className)} {...props} />;
}

export function CardDescription({ className, ...props }: React.HTMLAttributes<HTMLParagraphElement>) {
  return <p className={cn("muted mt-1 text-sm", className)} {...props} />;
}
