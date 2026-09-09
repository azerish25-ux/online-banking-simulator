import * as React from "react";
import { cn } from "../../lib/cn";

/**
 * A ledger block, not a floating data-grid: no outer border of its own - the
 * row and header rules inside a bordered section carry the structure. Amount
 * columns add `.nums` for tabular figures.
 */
export function Table({ className, ...props }: React.TableHTMLAttributes<HTMLTableElement>) {
  return (
    <div className="overflow-x-auto">
      <table className={cn("w-full text-left text-sm", className)} {...props} />
    </div>
  );
}

export function THead({ className, ...props }: React.HTMLAttributes<HTMLTableSectionElement>) {
  return <thead className={cn("border-b border-divider text-content-secondary", className)} {...props} />;
}

export function TRow({ className, ...props }: React.HTMLAttributes<HTMLTableRowElement>) {
  return (
    <tr
      className={cn(
        "border-t border-divider/70 first:border-t-0 hover:bg-surface-subtle",
        className
      )}
      {...props}
    />
  );
}

export function TH({ className, ...props }: React.ThHTMLAttributes<HTMLTableCellElement>) {
  // Alignment classes (text-right etc.) merge with the base cell padding via
  // cn; callers never lose px-4 py-2 just because they aligned a header.
  return <th scope="col" className={cn("label px-4 py-2", className)} {...props} />;
}

export function TD({ className, ...props }: React.TdHTMLAttributes<HTMLTableCellElement>) {
  return <td className={cn("px-4 py-2", className)} {...props} />;
}
