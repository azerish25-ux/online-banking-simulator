"use client";

import { Eye, EyeOff } from "lucide-react";
import * as React from "react";
import { Input } from "./input";

/**
 * Password field with a show/hide toggle. Default hides; the toggle swaps the
 * input type and its accessible label so screen readers announce the state.
 * Forwards its ref so react-hook-form register() attaches to the real input.
 */
export const PasswordInput = React.forwardRef<HTMLInputElement, React.ComponentProps<typeof Input>>(
  function PasswordInput(props, ref) {
    const [visible, setVisible] = React.useState(false);
    const toggle = () => setVisible((v) => !v);
    const Label = visible ? EyeOff : Eye;

    return (
      <div className="relative">
        <Input ref={ref} {...props} type={visible ? "text" : "password"} />
        <button
          type="button"
          onClick={toggle}
          aria-label={visible ? "Hide password" : "Show password"}
          aria-pressed={visible}
          className="absolute right-2 top-1/2 -translate-y-1/2 rounded-md p-1.5 text-content-muted hover:bg-ink-600 hover:text-content"
        >
          <Label size={16} aria-hidden="true" />
        </button>
      </div>
    );
  }
);
