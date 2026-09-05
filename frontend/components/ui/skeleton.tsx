import { cn } from "../../lib/cn";

export function Skeleton({ className }: { className?: string }) {
  // The pulse is a motion cue, so it respects prefers-reduced-motion - under
  // reduced motion the block still shapes the layout, just without pulsing.
  return <div aria-hidden className={cn("rounded-sm bg-ink-800 motion-safe:animate-pulse", className)} />;
}
