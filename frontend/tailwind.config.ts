import type { Config } from "tailwindcss";

/**
 * Simulator design tokens - the light "private-bank workspace".
 *
 * The workspace is warm paper, not a dark terminal: ivory page and white
 * cards (the `ink` steps are now LIGHT surfaces stepped by role - page,
 * panel, well, hover), near-black navy text, and a bronze accent that still
 * reads as engraved hardware. Ink-950 is the one deliberate exception: it
 * stays a deep navy and is reserved as the CONTRAST TEXT on brass fills
 * (buttons, badges) - the light theme's answer to "dark text on brass" -
 * never as a background. Default Tailwind hues are banned by
 * scripts/check-design-tokens.mjs - every color a component can name is
 * defined here (or in the `content`/`status` semantic groups below).
 */
export default {
  content: ["./app/**/*.{ts,tsx}", "./components/**/*.{ts,tsx}", "./lib/**/*.{ts,tsx}"],
  theme: {
    extend: {
      colors: {
        // LIGHT surfaces, stepped by ROLE (darker = deeper, recessed):
        // 900 page, 850 panel/card (paper white), 800 wells / active nav /
        // row hover / skeletons, 700 secondary-button base + hover tints,
        // 600 deepest hover. 950 is the dark contrast text on brass.
        ink: {
          950: "#0b0f1a",
          900: "#f1efe8",
          850: "#fdfcf7",
          800: "#e9e5d6",
          700: "#e1dcc9",
          600: "#d3ccb6"
        },
        // Hairlines and rules - the statement-paper grid.
        line: "#cfc8b4",
        // Bronze accent scale. Text steps (200-400) are DARK bronzes tuned
        // for AA on the light surfaces (links, eyebrows, display figures);
        // fill steps (500-600) stay the mid-gold of buttons, focus rings,
        // unread dots and chart bars. 900 is the deepest engraving tone.
        brass: {
          200: "#8a6626",
          300: "#6e521c",
          400: "#5a4318",
          500: "#b18a42",
          600: "#936f34",
          700: "#75572a",
          900: "#3a2c17"
        },
        // Status text tones - DARK versions, AA on their light surfaces and
        // on the white/ivory pages (loan debts read as a deep rose, not a
        // neon tint).
        mint: "#2e6b3f",
        rose: "#9e2f22",
        amber: "#7c5a10",
        sky: "#1f5f92",
        // Foreground ramp - near-black navy text on the light surfaces.
        // DEFAULT = primary text, soft = secondary, muted = captions/body,
        // faint = placeholders and non-interactive hints. Faint is tuned for
        // WCAG AA on the input field composite (a light well over a panel):
        // #5f6c86 measures ~4.9:1 there, keeping placeholders visibly dimmer
        // than typed text while clearing the 4.5 floor.
        content: {
          DEFAULT: "#1a2130",
          soft: "#3b4559",
          muted: "#4c5a70",
          faint: "#5f6c86"
        },
        // Status SURFACE pairs - pale tints of each hue (text tones are the
        // dark mint/rose/amber/sky above) so badges and banners sit on the
        // page like paper notes. strong = hover/fill.
        success: { surface: "#eaf4e6", border: "#a9cf96", strong: "#d8ecce" },
        danger: { surface: "#fceeea", border: "#e0a79b", strong: "#f3d4cd" },
        warning: { surface: "#f9f2dd", border: "#ddc184", strong: "#efe0b4" },
        info: { surface: "#e7f0f8", border: "#a5c9e6", strong: "#d2e4f3" },
        // Money-out bars in the flow chart (money-in is brass-500).
        outflow: "#8aa8c6",
        // Modal/backdrop scrim - warm charcoal veil over the light page.
        scrim: "#201c13"
      },
      fontFamily: {
        sans: ["var(--font-sans)", "ui-sans-serif", "system-ui", "sans-serif"],
        display: ["var(--font-display)", "Georgia", "serif"],
        mono: ["ui-monospace", "SFMono-Regular", "Menlo", "monospace"]
      },
      boxShadow: {
        // Paper cards on the ivory page: a soft warm drop, no dark glow.
        card: "0 1px 0 rgba(255,255,255,0.7) inset, 0 1px 2px rgba(58,44,23,0.06), 0 12px 28px rgba(58,44,23,0.08)"
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
