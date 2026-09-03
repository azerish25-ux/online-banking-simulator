import * as React from "react";
import { cn } from "../../lib/cn";

export function Table({ className, ...props }: React.TableHTMLAttributes<HTMLTableElement>) {
  return (
    <div className="overflow-x-auto rounded-lg border border-line">
      <table className={cn("w-full text-left text-sm", className)} {...props} />
    </div>
  );
}

export function THead(props: React.HTMLAttributes<HTMLTableSectionElement>) {
  return <thead className="bg-ink-700/60 text-xs uppercase tracking-wide text-slate-400" {...props} />;
}

export function TRow(props: React.HTMLAttributes<HTMLTableRowElement>) {
  return <tr className="border-t border-line first:border-t-0 hover:bg-ink-700/40" {...props} />;
}

export function TH(props: React.ThHTMLAttributes<HTMLTableCellElement>) {
  return <th className="px-4 py-2.5 font-medium" {...props} />;
}

export function TD(props: React.TdHTMLAttributes<HTMLTableCellElement>) {
  return <td className="px-4 py-2.5" {...props} />;
}
