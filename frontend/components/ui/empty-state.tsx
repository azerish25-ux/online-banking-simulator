/**
 * Empty zone for lists and feeds. Deliberately NOT a Card: it is rendered
 * inside Cards on dashboard/activity/notifications, and a Card inside a Card
 * is a nesting mistake. The dashed hairline reads as "nothing here yet" in
 * either context.
 */
export function EmptyState({ title, description }: { title: string; description?: string }) {
  return (
    <div className="rounded-md border border-dashed border-divider bg-surface-subtle px-6 py-10 text-center">
      <p className="text-base font-semibold tracking-tight">{title}</p>
      {description ? <p className="muted mx-auto mt-1 max-w-sm text-sm">{description}</p> : null}
    </div>
  );
}
