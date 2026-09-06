// Next 16 dropped `next lint`; linting runs through the ESLint CLI with the
// flat config below. eslint-config-next 16 ships flat-format rule sets, and
// `next build` no longer lints, so CI must call this script explicitly.
import { defineConfig, globalIgnores } from "eslint/config";
import nextVitals from "eslint-config-next/core-web-vitals";
import nextTs from "eslint-config-next/typescript";

const eslintConfig = defineConfig([
  ...nextVitals,
  ...nextTs,
  {
    // eslint-config-next 16 pulls eslint-plugin-react-hooks v7, whose new
    // compiler-era diagnostics (set-state-in-effect, refs-in-render) and the
    // React Compiler linter flag patterns this codebase used deliberately
    // under Next 14 (dialog-state sync, mutation-result augmentation). They
    // are advisory rather than classic rule violations, and the UI/state
    // remediation phases (F09-F11) own those files; disabling here keeps the
    // lint gate meaningful for every OTHER rule while that work is pending.
    rules: {
      "react-hooks/set-state-in-effect": "off",
      "react-hooks/refs": "off",
      "react-hooks/incompatible-library": "off",
      "react-compiler/react-compiler": "off",
      "no-restricted-globals": [
        "error",
        {
          name: "status",
          message:
            "`status` is the legacy window.status DOM global - a typo for a local variable silently compiles and reads as \"\" at runtime (the HELD-transfer toast regression). Declare a local, e.g. `const held = d.status === \"HELD\"`."
        }
      ]
    }
  },
  globalIgnores([
    ".next/**",
    "out/**",
    "build/**",
    "next-env.d.ts",
    "**/*.d.ts",
    "e2e-screenshots/**",
    "coverage/**"
  ])
]);

export default eslintConfig;
