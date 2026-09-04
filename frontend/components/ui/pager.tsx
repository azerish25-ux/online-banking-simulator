import { Button } from "./button";

/** Prev/Next pager for paginated tables; renders nothing for a single page. */
export function Pager({
  page,
  totalPages,
  onChange
}: {
  page: number;
  totalPages: number;
  onChange: (next: number) => void;
}) {
  if (totalPages <= 1) return null;
  return (
    <div className="mt-3 flex items-center justify-between text-sm">
      <span className="muted">Page {page + 1} of {totalPages}</span>
      <div className="flex gap-2">
        <Button variant="secondary" size="sm" disabled={page === 0} onClick={() => onChange(page - 1)}>
          Prev
        </Button>
        <Button variant="secondary" size="sm" disabled={page + 1 >= totalPages} onClick={() => onChange(page + 1)}>
          Next
        </Button>
      </div>
    </div>
  );
}
