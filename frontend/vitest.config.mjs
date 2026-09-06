import react from "@vitejs/plugin-react";
import { defineConfig } from "vitest/config";

export default defineConfig({
  // Next's tsconfig sets jsx: preserve; the React plugin makes TSX transform
  // for tests (Vite 8 refuses to guess when jsx is preserve).
  plugins: [react()],
  esbuild: {
    jsx: "automatic"
  },
  test: {
    setupFiles: ["./vitest.setup.mjs"],
    environment: "jsdom",
    include: ["lib/**/*.test.ts", "lib/**/*.test.tsx", "components/**/*.test.tsx"]
  }
});
