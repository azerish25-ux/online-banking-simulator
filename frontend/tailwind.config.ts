import type { Config } from "tailwindcss";

/**
 * Simulator design tokens - "private-bank ink".
 *
 * The palette is bespoke: deep ink blues for surfaces, a brass accent that
 * reads as engraved hardware rather than a SaaS landing page, and a serif
 * display face for figures and headlines. Default Tailwind hues are banned
 * by scripts/check-design-tokens.mjs - every color a component can name is
 * defined here (or in the `content`/`status` semantic groups below).
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
        // Status text tones, tuned to the ink base (AA on ink-800/900).
        mint: "#9fdcb4",
        rose: "#f2a9a9",
        amber: "#e5c47c",
        sky: "#9ec5e8",
        // Foreground ramp - the bespoke answer to Tailwind's slate scale.
        // DEFAULT = primary text, soft = secondary, muted = captions/body,
        // faint = placeholders and non-interactive hints. Faint is tuned for
        // WCAG AA on the input field composite (ink-950/70 over a panel):
        // the old #66738c measured 4.0:1, below the 4.5 floor for the hint
        // text placeholders carry, so it was lifted to #7b89a3 (5.4:1) while
        // staying a clear step below muted (7.0:1) - the ramp's hierarchy
        // survives, and placeholders stay visibly dimmer than typed text.
        content: {
          DEFAULT: "#e9edf5",
          soft: "#c9d2e1",
          muted: "#97a3bd",
          faint: "#7b89a3"
        },
        // Status SURFACE pairs (text tones are mint/rose/amber/sky above),
        // mixed from each hue into the ink base so badges and banners sit on
        // the page instead of looking pasted on. strong = hover/fill.
        success: { surface: "#0b2418", border: "#1d5637", strong: "#14402a" },
        danger: { surface: "#2a1118", border: "#7a2131", strong: "#571823" },
        warning: { surface: "#211a08", border: "#66501c", strong: "#4b3a15" },
        info: { surface: "#0e1e31", border: "#2b4e77", strong: "#1e3a5c" },
        // Money-out bars in the flow chart (money-in is brass-400).
        outflow: "#5b7ea6",
        // Modal/backdrop scrim.
        scrim: "#000000"
      },
      fontFamily: {
        sans: ["var(--font-sans)", "ui-sans-serif", "system-ui", "sans-serif"],
        display: ["var(--font-display)", "Georgia", "serif"],
        mono: ["ui-monospace", "SFMono-Regular", "Menlo", "monospace"]
      },
      boxShadow: {
        card: "0 1px 0 rgba(255,255,255,0.03) inset, 0 10px 24px rgba(2, 6, 16, 0.5)"
      },
      letterSpacing: { caps: "0.08em" },
      // Motion language - bank-quiet: fast, small, no bounce. Applied with
      // `motion-safe:animate-*` so prefers-reduced-motion removes it entirely.
      keyframes: {
        "overlay-in": { from: { opacity: "0" } },
        "dialog-in": { from: { opacity: "0", transform: "translateY(6px) scale(0.98)" } },
        "toast-in": { from: { opacity: "0", transform: "translateY(8px)" } }
      },
      animation: {
        "overlay-in": "overlay-in 160ms ease-out",
        "dialog-in": "dialog-in 180ms ease-out",
        "toast-in": "toast-in 200ms ease-out"
      }
    }
  },
  plugins: []
} satisfies Config;
