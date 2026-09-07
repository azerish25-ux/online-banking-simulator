import * as React from "react";
import { cn } from "../../lib/cn";

export const Input = React.forwardRef<HTMLInputElement, React.InputHTMLAttributes<HTMLInputElement>>(
  function Input({ className, ...props }, ref) {
    return (
      <input
        ref={ref}
        className={cn(
          "h-11 w-full rounded border border-control bg-surface px-3 text-sm text-content shadow-panel",
          "placeholder:text-content-faint focus:border-focus focus:outline-none focus:ring-1 focus:ring-focus",
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
  controlId,
  children
}: {
  label: string;
  error?: string;
  hint?: string;
  /**
   * Explicit control-ID contract (F18): when the caller already owns the
   * control's id (e.g. it renders a non-element composition or needs the id
   * elsewhere), pass it here and Field will NOT clone the child - the label,
   * hint and error are all wired to that id.
   */
  controlId?: string;
  children: React.ReactNode;
}) {
  const generatedId = React.useId();
  const id = controlId ?? generatedId;
  const errorId = React.useId();
  const hintId = React.useId();
  const describedBy = [hint ? hintId : null, error ? errorId : null]
      .filter(Boolean)
      .join(" ") || undefined;

  // Wiring the label needs exactly one element child that accepts an id.
  // JSX turns `oneControl + siblingText` into an ARRAY child, so the first
  // element child is located anywhere among siblings (e.g. a Select followed
  // by a conditionally-rendered hint paragraph) - never assume a single
  // child. With controlId the caller owns the id and children render
  // untouched.
  let control = children;
  if (!controlId) {
    const kids = React.Children.toArray(children);
    let wired = false;
    control = kids.map((child) => {
      if (wired || !React.isValidElement(child)) return child;
      wired = true;
      const el = child as React.ReactElement<{
        id?: string;
        "aria-invalid"?: boolean | "true" | "false";
        "aria-describedby"?: string;
      }>;
      return React.cloneElement(el, {
        id,
        "aria-invalid": error ? true : undefined,
        "aria-describedby": describedBy
          ? [el.props["aria-describedby"], describedBy].filter(Boolean).join(" ")
          : el.props["aria-describedby"]
      });
    });
    if (!wired && process.env.NODE_ENV !== "production") {
      // A label with htmlFor but no element child silently loses its
      // association; make the contract failure loud in development instead.
      console.error(
        "Field: pass an element child (Input/Select/PasswordInput) or "
            + "provide `controlId` so the label can reach the control.");
    }
  }

  return (
    <div>
      <label htmlFor={id} className="label mb-1.5 block text-content-secondary">
        {label}
      </label>
      {control}
      {error ? (
        <p id={errorId} role="alert" className="mt-1.5 text-sm text-danger">
          {error}
        </p>
      ) : hint ? (
        <p id={hintId} className="muted mt-1.5 text-xs">{hint}</p>
      ) : null}
    </div>
  );
}
