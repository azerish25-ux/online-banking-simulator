import * as React from "react";
import { cn } from "../../lib/cn";

/** Multi-line twin of Input: same tokens, vertically resizable by the user. */
export const Textarea = React.forwardRef<
  HTMLTextAreaElement,
  React.TextareaHTMLAttributes<HTMLTextAreaElement>
>(function Textarea({ className, ...props }, ref) {
  return (
    <textarea
      ref={ref}
      className={cn(
        "min-h-24 w-full rounded border border-control bg-surface px-3 py-2 text-sm text-content shadow-panel",
        "placeholder:text-content-faint focus:border-focus focus:outline-none focus:ring-1 focus:ring-focus",
        className
      )}
      {...props}
    />
  );
});
