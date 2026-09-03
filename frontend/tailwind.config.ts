import type { Config } from "tailwindcss";

export default {
  content: ["./app/**/*.{ts,tsx}", "./components/**/*.{ts,tsx}", "./lib/**/*.{ts,tsx}"],
  theme: {
    extend: {
      colors: {
        ink: { 950: "#070d1a", 900: "#0b1220", 800: "#111c33", 700: "#1a2a4a" },
        line: "#24365c",
        brand: { 300: "#7dd3fc", 400: "#38bdf8", 500: "#0ea5e9", 600: "#0284c7", 900: "#12325b" },
        mint: "#86efac",
        rose: "#fca5a5"
      },
      fontFamily: {
        sans: ["var(--font-sans)", "ui-sans-serif", "system-ui", "sans-serif"],
        mono: ["ui-monospace", "SFMono-Regular", "Menlo", "monospace"]
      },
      boxShadow: { card: "0 8px 30px rgba(2, 8, 23, 0.45)" }
    }
  },
  plugins: []
} satisfies Config;
