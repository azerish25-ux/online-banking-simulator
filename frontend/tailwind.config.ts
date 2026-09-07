import type { Config } from "tailwindcss";

/**
 * Simulator design tokens - the light financial workspace ( section 12).
 *
 * One source of truth: cool gray workspace and white surfaces, near-navy
 * operational text, a deep-navy primary action, and semantic status pairs.
 * Every color a component can name is defined here (or in the `content` /
 * `status` groups below); default Tailwind hues and raw hex literals are
 * banned by scripts/check-design-tokens.mjs.
 */
export default {
  content: ["./app/**/*.{ts,tsx}", "./components/**/*.{ts,tsx}", "./lib/**/*.{ts,tsx}"],
  theme: {
    extend: {
      colors: {
        // Application background: restrained cool grey, not a warm paper.
        workspace: "#F4F6F8",
        // Surfaces: white content panels; subtle wells/hover/active states.
        surface: {
          DEFAULT: "#FFFFFF",
          subtle: "#EEF2F6",
          hover: "#E3E8EF"
        },
        // Decorative separators (never the sole boundary of a control).
        divider: "#D3DAE2",
        // Visible input/control boundaries.
        control: "#788796",
        // Primary actions and the selected navigation state.
        action: {
          DEFAULT: "#193C60",
          hover: "#12304E"
        },
        // Visible keyboard-focus treatment.
        focus: "#165DAA",
        // Foreground ramp. DEFAULT = primary text, secondary = supporting
        // text, faint = placeholders (tuned to stay ~4.5:1 on the field
        // composites while reading dimmer than typed text).
        content: {
          DEFAULT: "#172330",
          secondary: "#536273",
          faint: "#64748B"
        },
        // Semantic status text/surface pairs - pale surface + dark text tone.
        success: { DEFAULT: "#17613D", surface: "#ECF5EF", border: "#BFDBCB", strong: "#DCEEE4" },
        warning: { DEFAULT: "#805300", surface: "#FFF4D6", border: "#E7D29A", strong: "#F6E8B8" },
        danger: { DEFAULT: "#A32532", surface: "#FCEFF1", border: "#EABEC3", strong: "#F7DBDE" },
        info: { DEFAULT: "#205A83", surface: "#EEF4FA", border: "#C4DBEC", strong: "#DCE9F3" },
        // Money-out bars in the flow chart (money-in uses action-primary).
        outflow: "#7C93A9",
        // Modal/backdrop scrim - cool charcoal veil over the light page.
        scrim: "#1B2A38"
      },
      fontFamily: {
        sans: ["var(--font-sans)", "ui-sans-serif", "system-ui", "sans-serif"],
        mono: ["ui-monospace", "SFMono-Regular", "Menlo", "monospace"]
      },
      boxShadow: {
        // Panels carry restrained borders and little shadow; elevation is
        // reserved for genuine overlays (dialogs).
        panel: "0 1px 2px rgba(23, 35, 48, 0.05)",
        dialog: "0 8px 24px rgba(23, 35, 48, 0.14)"
      },
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
