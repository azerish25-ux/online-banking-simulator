import { defineConfig } from "vitest/config";

export default defineConfig({
  esbuild: {
    // Next's tsconfig sets jsx: preserve; vitest needs the automatic runtime.
    jsx: "automatic"
  },
  test: {
    setupFiles: ["./vitest.setup.mjs"],
    environment: "jsdom",
    include: ["lib/**/*.test.ts", "lib/**/*.test.tsx", "components/**/*.test.tsx"]
  }
});
