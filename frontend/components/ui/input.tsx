import * as React from "react";
import { cn } from "../../lib/cn";

export const Input = React.forwardRef<HTMLInputElement, React.InputHTMLAttributes<HTMLInputElement>>(
  function Input({ className, ...props }, ref) {
    return (
      <input
        ref={ref}
        className={cn(
          "h-10 w-full rounded-md border border-line bg-ink-950/70 px-3 text-sm text-slate-100",
          "placeholder:text-slate-500 focus:border-brass-500 focus:outline-none focus:ring-1 focus:ring-brass-500",
          className
        )}
        {...props}
      />
    );
  }
);

export function Field({
  label,
  error,
  hint,
  children
}: {
  label: string;
  error?: string;
  hint?: string;
  children: React.ReactNode;
}) {
  const id = React.useId();
  return (
    <div>
      <label htmlFor={id} className="caps mb-1.5 block font-medium text-slate-300 normal-case">
        {label}
      </label>
      {React.isValidElement(children)
        ? React.cloneElement(children as React.ReactElement<{ id?: string }>, { id })
        : children}
      {error ? (
        <p role="alert" className="mt-1.5 text-sm text-rose">
          {error}
        </p>
      ) : hint ? (
        <p className="muted mt-1.5 text-xs">{hint}</p>
      ) : null}
    </div>
  );
}
