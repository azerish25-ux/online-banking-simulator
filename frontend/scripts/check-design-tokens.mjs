/**
 * Design-token guard - fails the build if a component reaches for a color
 * outside the bespoke palette.
 *
 * Two leaks are banned in app/ and components/:
 *   1. Default Tailwind hues (slate, red, emerald, amber, sky, ...) - every
 *      color a view can name must come from tailwind.config.ts, which the
 *      project extends with `ink`, `brass`, `line`, `content`, `mint`,
 *      `rose`, `amber`, `sky`, and the status/outflow tokens.
 *   2. Raw hex color literals - chart fills, borders, etc. reference the
 *      tokens (e.g. `fill-outflow`), never a copy of a value.
 *
 * The tailwind.config.ts and globals.css files ARE the definition sites and
 * are intentionally out of scope.
 */
import { readdirSync, readFileSync, statSync } from "node:fs";
import { join } from "node:path";
import { fileURLToPath } from "node:url";

const root = join(fileURLToPath(new URL(".", import.meta.url)), "..");
const scanDirs = ["app", "components"];
const extensions = /\.(ts|tsx|css)$/;

// A default Tailwind hue used as a class utility (numeric step required, so
// the bespoke `text-rose` / `text-amber` tokens - which have no step - stay
// legal).
const hueClass = /(?:bg|text|border|ring|fill|stroke|divide|outline|placeholder:text|from|via|to)-(?:slate|gray|neutral|stone|zinc|red|orange|amber|yellow|lime|green|emerald|teal|cyan|sky|blue|indigo|violet|purple|fuchsia|pink|rose)-\d/;
const hexColor = /#[0-9a-fA-F]{6}\b|#[0-9a-fA-F]{3}\b/;

function walk(dir) {
  return readdirSync(dir).flatMap((entry) => {
    const full = join(dir, entry);
    return statSync(full).isDirectory() ? walk(full) : [full];
  });
}

const files = scanDirs
  .flatMap((dir) => walk(join(root, dir)))
  .filter((f) => extensions.test(f))
  // app/globals.css is the token DEFINITION file (the :root variables) and
  // intentionally holds the raw values.
  .filter((f) => !/globals\.css$/.test(f));
const offenders = [];

for (const file of files) {
  const text = readFileSync(file, "utf8");
  for (const [index, line] of text.split("\n").entries()) {
    for (const [name, re] of [["default Tailwind hue class", hueClass], ["raw hex color", hexColor]]) {
      const match = line.match(re);
      if (match) {
        offenders.push(`${file}:${index + 1}  ${name} "${match[0]}"`);
      }
    }
  }
}

if (offenders.length > 0) {
  console.error("Design-token violations (use tailwind.config.ts tokens instead):\n");
  for (const line of offenders) console.error("  " + line);
  console.error("\nFix them, or extend the palette in tailwind.config.ts if the color is genuinely new.");
  process.exit(1);
}
console.log(`Design tokens clean (${files.length} files scanned).`);
