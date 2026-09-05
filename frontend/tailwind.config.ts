import type { Config } from "tailwindcss";

/**
 * Simulator design tokens - "private-bank ink".
 *
 * The palette is bespoke: deep ink blues for surfaces, a brass accent that
 * reads as engraved hardware rather than a SaaS landing page, and a serif
 * display face for figures and headlines. No default Tailwind hues.
 */
export default {
  content: ["./app/**/*.{ts,tsx}", "./components/**/*.{ts,tsx}", "./lib/**/*.{ts,tsx}"],
  theme: {
    extend: {
      colors: {
        // Surfaces: near-black navy, stepped for panels and wells.
        ink: {
          950: "#080b14",
          900: "#0c101d",
          850: "#101728",
          800: "#151e33",
          700: "#1d2942",
          600: "#27365a"
        },
        // Hairlines and rules - the statement-paper grid.
        line: "#26324e",
        // Brass accent scale (primary 400). Warm, desaturated, engraved.
        brass: {
          200: "#e8cf9a",
          300: "#d9b876",
          400: "#c9a35c",
          500: "#b18a42",
          600: "#936f34",
          700: "#75572a",
          900: "#3a2c17"
        },
        // Status colors, tuned to the ink base (AA on ink-800/900).
        mint: "#9fdcb4",
        rose: "#f2a9a9",
        amber: "#e5c47c",
        sky: "#9ec5e8"
      },
      fontFamily: {
        sans: ["var(--font-sans)", "ui-sans-serif", "system-ui", "sans-serif"],
        display: ["var(--font-display)", "Georgia", "serif"],
        mono: ["ui-monospace", "SFMono-Regular", "Menlo", "monospace"]
      },
      boxShadow: {
        card: "0 1px 0 rgba(255,255,255,0.03) inset, 0 10px 24px rgba(2, 6, 16, 0.5)"
      },
      letterSpacing: { caps: "0.08em" }
    }
  },
  plugins: []
} satisfies Config;
