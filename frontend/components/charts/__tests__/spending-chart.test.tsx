import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { SpendingChart, type MonthPoint } from "../spending-chart";

/**
 * the chart must expose its EXACT values: tooltip/title-only figures
 * are not enough. The data table is a real <table> that a visible toggle
 * expands; while collapsed it stays sr-only so assistive technology reads the
 * same figures the geometry approximates. Rows = months, cells = exact usd()
 * renderings of the ledger decimal strings.
 */
const SAMPLE: MonthPoint[] = [
  { month: "2026-01", inflow: "1250.00", outflow: "480.25" },
  { month: "2026-02", inflow: "0.00", outflow: "0.00" }
];

afterEach(cleanup);

describe("SpendingChart accessible data table", () => {
  it("names the chart's data table through aria-describedby", () => {
    render(<SpendingChart data={SAMPLE} />);
    const chart = screen.getByRole("img", { name: "Monthly money in and out, in USD" });
    const described = chart.getAttribute("aria-describedby");
    expect(described).toBeTruthy();
    expect(document.getElementById(described as string)).not.toBeNull();
  });

  it("expands the exact-value table through a visible toggle", () => {
    render(<SpendingChart data={SAMPLE} />);
    const table = document.querySelector("table") as HTMLTableElement;
    expect(table).not.toBeNull();
    // Collapsed: visually hidden but still a real, accessible table.
    expect(table.className).toContain("sr-only");
    // Expanding makes the same table visible on the page.
    fireEvent.click(screen.getByRole("button", { name: "Show exact values" }));
    expect(table.className).not.toContain("sr-only");
    expect(screen.getByRole("button", { name: "Hide exact values" })).toBeInTheDocument();
  });

  it("renders one exact-value row per month, including a zero month", () => {
    render(<SpendingChart data={SAMPLE} />);
    // The chart svg is role=img; the data table is a real <table>.
    const table = document.querySelector("table") as HTMLTableElement;
    expect(table).not.toBeNull();

    const cells = Array.from(table.querySelectorAll("td")).map((td) => td.textContent);
    // Month 1 inflow/outflow formatted exactly from the decimal strings.
    expect(cells).toContain("$1,250.00");
    expect(cells).toContain("$480.25");
    // Month 2 is genuinely zero: it must be PRESENT as $0.00 rows, not
    // represented by a fake nonzero bar and not dropped.
    expect(cells).toContain("$0.00");
    expect(table.querySelectorAll("tbody tr").length).toBe(SAMPLE.length);
  });

  it("keeps sub-cent precision visible via the same renderer the app uses", () => {
    render(
      <SpendingChart
        data={[{ month: "2026-03", inflow: "0.0049", outflow: "12.3456" }]}
      />
    );
    const table = document.querySelector("table") as HTMLTableElement;
    const cells = Array.from(table.querySelectorAll("td")).map((td) => td.textContent);
    // The chart table shows the same figures usd() would show elsewhere:
    // 0.0049 rounds HALF_UP to a cent (0.00), 12.3456 → 12.35: never raw
    // float debris like 12.3456000001.
    expect(cells).toContain("$0.00");
    expect(cells).toContain("$12.35");
  });
});
