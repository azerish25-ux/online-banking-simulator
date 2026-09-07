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
        "min-h-24 w-full rounded-md border border-line bg-ink-800/60 px-3 py-2 text-sm text-content",
        "placeholder:text-content-faint focus:border-brass-500 focus:outline-none focus:ring-1 focus:ring-brass-500",
        className
      )}
      {...props}
    />
  );
});
