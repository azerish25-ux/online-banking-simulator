import Link from "next/link";
import { AboutThisDemo } from "../../lib/brand";
import { Routes } from "../../lib/routes";

/**
 * The single route to the demo seam. Every surface that points at /about: * the logged-in sidebar, the auth screens, the landing page: renders this
 * one link, so the seam's destination and default label live in one place.
 * Callers keep their own layout classes; the landing uses its own label
 * ("How it works") without forking the markup.
 */
export function AboutDemoLink({
  className,
  label = AboutThisDemo
}: {
  className?: string;
  label?: string;
}) {
  return (
    <Link href={Routes.about} className={className}>
      {label}
    </Link>
  );
}
