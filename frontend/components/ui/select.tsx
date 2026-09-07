import * as React from "react";
import { cn } from "../../lib/cn";

// Inline SVG chevron (dimmed) drawn from the content-faint token value; the
// native arrow is removed with appearance-none so every select in the app
// shares one arrow, size and focus treatment instead of three ad-hoc styles.
// The stroke hex must mirror tailwind's `content.faint` (a data-URI SVG
// cannot read CSS variables) - keep the two in sync.
const chevron =
  "url(\"data:image/svg+xml;charset=utf-8,%3Csvg xmlns='http://www.w3.org/2000/svg' width='16' height='16' viewBox='0 0 24 24' fill='none' stroke='%2364748b' stroke-width='2' stroke-linecap='round' stroke-linejoin='round'%3E%3Cpath d='m6 9 6 6 6-6'/%3E%3C/svg%3E\")";

/**
 * Native select styled like Input - same height, control border, focus ring.
 * Forwarding the ref and spreading props keeps react-hook-form
 * register() and Field's label wiring (id injection) on the real element.
 */
export const Select = React.forwardRef<
  HTMLSelectElement,
  React.SelectHTMLAttributes<HTMLSelectElement>
>(function Select({ className, ...props }, ref) {
  return (
    <select
      ref={ref}
      style={{ backgroundImage: chevron, backgroundRepeat: "no-repeat", backgroundPosition: "right 0.6rem center" }}
      className={cn(
        "h-11 w-full appearance-none rounded border border-control bg-surface py-1.5 pl-3 pr-9 text-sm text-content shadow-panel",
        "focus:border-focus focus:outline-none focus:ring-1 focus:ring-focus",
        "disabled:cursor-not-allowed disabled:opacity-60",
        className
      )}
      {...props}
    />
  );
});
