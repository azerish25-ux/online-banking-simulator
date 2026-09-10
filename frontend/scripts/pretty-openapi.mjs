/**
 * Serializes the OpenAPI contract deterministically: 2-space indent,
 * " : " before values, scalar arrays inlined with ", " separators, object
 * arrays opened as "[ {", and object keys SORTED at every level.
 *
 * springdoc serializes reflected bean properties in JVM-dependent order, so
 * a raw byte diff of /v3/api-docs flaps between machines for an identical
 * contract (the CI check is order-insensitive for the same reason). Sorting
 * keys here makes the committed file byte-stable across regens.
 *
 * Usage: node scripts/pretty-openapi.mjs <input.json> <output.json>
 */
import { readFileSync, writeFileSync } from "node:fs";

const [input, output] = process.argv.slice(2);
const doc = JSON.parse(readFileSync(input, "utf8"));

function escape(str) {
  let out = "";
  for (const char of str) {
    if (char === '"') out += '\\"';
    else if (char === "\\") out += "\\\\";
    else if (char === "\n") out += "\\n";
    else if (char === "\r") out += "\\r";
    else if (char === "\t") out += "\\t";
    else if (char < " ") out += "\\u" + char.charCodeAt(0).toString(16).padStart(4, "0");
    else out += char;
  }
  return out;
}

/** A scalar, an empty container, or an array whose elements are all scalars: Jackson inlines these. */
function isScalarLike(value) {
  if (value === null || typeof value !== "object") return true;
  if (Array.isArray(value)) {
    if (value.length === 0) return true;
    return value.every((item) => item === null || typeof item !== "object");
  }
  return Object.keys(value).length === 0;
}

function sortedEntries(value) {
  return Object.entries(value).sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0));
}

function inlineValue(value) {
  if (value === null) return "null";
  if (typeof value === "string") return '"' + escape(value) + '"';
  if (typeof value === "number" || typeof value === "boolean") return String(value);
  if (Array.isArray(value)) {
    if (value.length === 0) return "[ ]".replace("  ", " ");
    return "[ " + value.map(inlineValue).join(", ") + " ]";
  }
  const entries = sortedEntries(value);
  if (entries.length === 0) return "{ }";
  return "{ " + entries.map(([k, v]) => '"' + escape(k) + '" : ' + inlineValue(v)).join(", ") + " }";
}

/** Writes a nested (non-inline) value as a list of lines at the given depth. */
function emit(value, depth) {
  const pad = "  ".repeat(depth + 1);
  const close = "  ".repeat(depth);
  const lines = [];
  if (Array.isArray(value)) {
    let open = "[";
    for (const item of value) {
      if (!isScalarLike(item)) {
        const sub = emit(item, depth);
        if (open === "[") {
          // Jackson opens an object array as "[ {" on the key's line.
          open = "[ " + sub[0];
          lines.push(open, ...sub.slice(1));
        } else {
          // ...and separates elements as "}, {" on one line.
          lines[lines.length - 1] += ", {";
          lines.push(...sub.slice(1));
        }
      } else {
        lines.push(pad + inlineValue(item) + ",");
      }
    }
    if (lines.length > 0) {
      lines[lines.length - 1] = lines[lines.length - 1].replace(/,$/, "");
      // Jackson closes an object array as "]}"... i.e. the ] rides the last element's line: "} ]".
      lines[lines.length - 1] += " ]";
      return [lines.join("\n")];
    }
    return ["[ ]"];
  }
  const entries = sortedEntries(value);
  entries.forEach(([key, item], index) => {
    const comma = index < entries.length - 1 ? "," : "";
    const prefix = pad + '"' + escape(key) + '" : ';
    if (!isScalarLike(item)) {
      const sub = emit(item, depth + 1);
      lines.push(prefix + sub[0], ...sub.slice(1));
      lines[lines.length - 1] += comma;
    } else {
      lines.push(prefix + inlineValue(item) + comma);
    }
  });
  return ["{", ...lines, close + "}"];
}

const lines = emit(doc, 0);
// Jackson writes no trailing newline; keep the output byte-identical to it.
writeFileSync(output, lines.join("\n"));
console.log("Wrote " + output + " (" + lines.length + " lines)");
