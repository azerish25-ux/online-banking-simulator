import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { Badge } from "../badge";
import { Button } from "../button";
import { Field, Input } from "../input";
import { Modal } from "../modal";
import { PasswordInput } from "../password-input";
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
    expect(screen.getByRole("img", { name: "Monthly money in and out" })).toBeInTheDocument();
    // Two bars per month + two legend swatches.
    expect(container.querySelectorAll("rect")).toHaveLength(data.length * 2 + 2);
    expect(screen.getByText("Apr")).toBeInTheDocument();
    expect(screen.getByText("May")).toBeInTheDocument();
  });
});
