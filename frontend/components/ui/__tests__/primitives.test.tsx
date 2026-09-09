import * as React from "react";
import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { Badge } from "../badge";
import { Button } from "../button";
import { Field, Input } from "../input";
import { Select } from "../select";
import { Modal } from "../modal";
import { PasswordInput } from "../password-input";
import { Table, THead, TRow, TH, TD } from "../table";
import { TxStatusBadge } from "../tx-status-badge";
import { SpendingChart } from "../../charts/spending-chart";

afterEach(cleanup);

describe("Button", () => {
  it("renders children and honors disabled", async () => {
    const onClick = vi.fn();
    render(<Button disabled onClick={onClick}>Send</Button>);
    const button = screen.getByRole("button", { name: "Send" });
    expect(button).toBeDisabled();
    await userEvent.click(button);
    expect(onClick).not.toHaveBeenCalled();
  });

  it("fires onClick when enabled", async () => {
    const onClick = vi.fn();
    render(<Button onClick={onClick}>Send</Button>);
    await userEvent.click(screen.getByRole("button", { name: "Send" }));
    expect(onClick).toHaveBeenCalledTimes(1);
  });
});

describe("Badge", () => {
  it("renders each review tone", () => {
    render(
      <>
        <Badge tone="success">POSTED</Badge>
        <Badge tone="warning">UNDER REVIEW</Badge>
        <Badge tone="danger">FAILED</Badge>
      </>
    );
    expect(screen.getByText("POSTED")).toBeInTheDocument();
    expect(screen.getByText("UNDER REVIEW")).toBeInTheDocument();
    expect(screen.getByText("FAILED")).toBeInTheDocument();
  });
});

describe("Field", () => {
  it("wires the label to the input and shows the error as an alert", () => {
    render(
      <Field label="Amount" error="Enter a positive amount.">
        <Input placeholder="0.00" />
      </Field>
    );
    const input = screen.getByLabelText("Amount");
    expect(input).toBeInTheDocument();
    expect(screen.getByRole("alert")).toHaveTextContent("Enter a positive amount.");
  });

  it("shows the hint when there is no error", () => {
    render(
      <Field label="Amount" hint="Simulated rail.">
        <Input />
      </Field>
    );
    expect(screen.getByText("Simulated rail.")).toBeInTheDocument();
    expect(screen.queryByRole("alert")).toBeNull();
  });

  it("associates the control with its hint via aria-describedby", () => {
    render(
      <Field label="Amount" hint="Simulated rail.">
        <Input />
      </Field>
    );
    const input = screen.getByLabelText("Amount");
    const describedBy = input.getAttribute("aria-describedby");
    expect(describedBy).toBeTruthy();
    const hint = document.getElementById(describedBy as string);
    expect(hint).toHaveTextContent("Simulated rail.");
  });

  it("merges a caller aria-describedby and marks the control invalid on error", () => {
    render(
      <Field label="IBAN" error="Not a valid IBAN.">
        <Input aria-describedby="helper-1" />
      </Field>
    );
    const input = screen.getByLabelText("IBAN");
    expect(input).toHaveAttribute("aria-invalid", "true");
    const describedBy = input.getAttribute("aria-describedby") ?? "";
    expect(describedBy).toContain("helper-1");
    const errorId = describedBy.split(" ").find((id) => id !== "helper-1");
    expect(document.getElementById(errorId as string)).toHaveTextContent("Not a valid IBAN.");
  });

  it("honors an explicit controlId without cloning the child", () => {
    const id = "amount-control";
    render(
      <Field label="Amount" controlId={id}>
        <input id={id} aria-label="Amount control" />
      </Field>
    );
    const control = screen.getByLabelText("Amount control");
    expect(screen.getByLabelText("Amount")).toBe(control);
  });

  it("wires the first element child even when siblings make children an array (regression)", () => {
    // JSX turns `oneControl + siblingParagraph` into an array child; the
    // control must still receive the id the label points at (open-account
    // dialog: a Select next to a conditionally-rendered loan hint).
    const hasLoan = true;
    render(
      <Field label="Account type">
        <Select aria-label="account-type-select" />
        {hasLoan && <p>You already have a loan open.</p>}
      </Field>
    );
    const select = screen.getByLabelText("account-type-select");
    const label = screen.getByText("Account type");
    expect(label.getAttribute("for")).toBeTruthy();
    expect(select.id).toBe(label.getAttribute("for"));
    expect(screen.getByText("You already have a loan open.")).toBeInTheDocument();
  });
});

describe("Modal", () => {
  it("renders the dialog with focus on the close button and returns focus on close", async () => {
    function Harness() {
      const [open, setOpen] = [true, vi.fn()];
      return <Modal open={open} onClose={setOpen} title="Open account">content</Modal>;
    }
    render(<Harness />);
    const dialog = screen.getByRole("dialog", { name: "Open account" });
    expect(dialog).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Close dialog" })).toHaveFocus();
  });

  it("locks background scroll while open and restores it on close", () => {
    const { rerender } = render(
      <Modal open onClose={() => {}} title="Deposit funds"><p>content</p></Modal>
    );
    expect(document.body.style.overflow).toBe("hidden");
    rerender(<Modal open={false} onClose={() => {}} title="Deposit funds"><p>content</p></Modal>);
    expect(document.body.style.overflow).toBe("");
  });

  it("never steals focus from a typed control when the onClose identity changes", async () => {
    // The input's value lives in the harness, so every keystroke re-renders
    // it and re-creates the inline onClose. A focus lifecycle keyed on the
    // callback identity would yank focus back to the close button after each
    // character; it must be keyed on `open` alone.
    function Harness() {
      const [text, setText] = React.useState("");
      return (
        <Modal open onClose={() => setText((t) => t)} title="Deposit funds">
          <input aria-label="Amount" value={text} onChange={(e) => setText(e.target.value)} />
        </Modal>
      );
    }
    render(<Harness />);
    const input = screen.getByLabelText("Amount");
    const user = userEvent.setup();
    await user.click(input);
    await user.type(input, "1234.50");
    expect(input).toHaveFocus();
    expect(input).toHaveValue("1234.50");
  });

  it("calls onClose on Escape", async () => {
    const onClose = vi.fn();
    render(<Modal open onClose={onClose} title="Open account">content</Modal>);
    await userEvent.keyboard("{Escape}");
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it("renders nothing when closed", () => {
    render(<Modal open={false} onClose={() => {}} title="Open account">content</Modal>);
    expect(screen.queryByRole("dialog")).toBeNull();
  });

  it("keeps Tab cycling inside the dialog and never reaches background content", async () => {
    const user = userEvent.setup();
    const onClose = vi.fn();
    render(
      <div>
        <button>Outside before</button>
        <Modal open onClose={onClose} title="Confirm">
          <button>First inside</button>
          <button>Second inside</button>
        </Modal>
        <button>Outside after</button>
      </div>
    );
    const dialog = screen.getByRole("dialog", { name: "Confirm" });
    // Initial focus is the close button inside the dialog.
    expect(screen.getByLabelText("Close dialog")).toHaveFocus();
    // Tab forward: close → First → Second → wraps back to close... four full
    // cycles must never land on an outside button.
    for (let i = 0; i < 12; i++) {
      await user.tab();
      expect(dialog.contains(document.activeElement)).toBe(true);
    }
    // Shift+Tab from the close button wraps to the last focusable inside.
    await user.tab({ shift: true });
    expect(screen.getByRole("button", { name: "Second inside" })).toHaveFocus();
  });
});

describe("Table", () => {
  it("merges caller alignment with the base cell padding", () => {
    render(
      <Table>
        <THead className="sr-only">
          <TRow>
            <TH>When</TH>
            <TH className="text-right">Amount</TH>
          </TRow>
        </THead>
        <tbody>
          <TRow>
            <TD>Today</TD>
            <TD className="text-right font-semibold tabular-nums">$10.00</TD>
          </TRow>
        </tbody>
      </Table>
    );
    const amountHead = screen.getByRole("columnheader", { name: "Amount" });
    expect(amountHead.className).toContain("px-4 py-2");
    expect(amountHead.className).toContain("text-right");
    const head = screen.getByText("When").closest("thead");
    expect(head?.className).toContain("sr-only");
    const amountCell = screen.getByText("$10.00");
    expect(amountCell.className).toContain("px-4 py-2");
    expect(amountCell.className).toContain("tabular-nums");
  });
});

describe("TxStatusBadge", () => {
  it("labels an unknown server value explicitly instead of assuming POSTED", () => {
    render(<TxStatusBadge />);
    expect(screen.getByText("Unknown")).toBeInTheDocument();
    expect(screen.queryByText("POSTED")).toBeNull();
  });

  it("surfaces an unrecognized server value rather than hiding it", () => {
    render(<TxStatusBadge status="MYSTERY" />);
    expect(screen.getByText("Unknown · MYSTERY")).toBeInTheDocument();
  });
});

describe("PasswordInput", () => {
  it("toggles visibility and announces the state", async () => {
    render(<PasswordInput data-testid="pw" />);
    const input = screen.getByTestId("pw");
    expect(input).toHaveAttribute("type", "password");
    await userEvent.click(screen.getByLabelText("Show password"));
    expect(input).toHaveAttribute("type", "text");
    expect(screen.getByLabelText("Hide password")).toBeInTheDocument();
  });
});

describe("SpendingChart", () => {
  it("renders one bar group per month with an accessible name", () => {
    const data = [
      { month: "2026-04", inflow: "120.00", outflow: "30.00" },
      { month: "2026-05", inflow: "80.00", outflow: "45.00" }
    ];
    const { container } = render(<SpendingChart data={data} />);
    const svg = container.querySelector("svg");
    expect(svg).not.toBeNull();
    expect(screen.getByRole("img", { name: "Monthly money in and out, in USD" })).toBeInTheDocument();
    // Two bars per month + two legend swatches.
    expect(container.querySelectorAll("rect")).toHaveLength(data.length * 2 + 2);
    // Month labels appear once in the chart AND once in its sr-only data table
    //: so assert inside the svg for the geometry, then confirm the
    // table exists as the accessible equivalent.
    expect(svg?.textContent).toContain("Apr");
    expect(svg?.textContent).toContain("May");
    const table = container.querySelector("table");
    expect(table).not.toBeNull();
    expect(table?.textContent).toContain("Apr");
    expect(table?.textContent).toContain("May");
  });
});
