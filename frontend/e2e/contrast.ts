import type { Page } from "@playwright/test";

/**
 * Contrast auditing from live computed styles: no axe-core dependency, no
 * screenshot diffing. For each visible text node inside a scope we read the
 * rendered foreground, the effective background (alpha-composited through the
 * element's ancestor chain down to the document), the font size and weight,
 * and apply the WCAG 2.2 AA thresholds: 3:1 for large text, 4.5:1 otherwise.
 * Placeholders are audited too, because "DE..." and "10.00" carry real
 * information (WCAG 1.4.3 applies to text, and the palette's faint tier was
 * tuned against this exact check).
 */

type RGB = { r: number; g: number; b: number };

type Sample = {
  text: string;
  where: string;
  fg: RGB;
  bg: RGB;
  px: number;
  bold: boolean;
};

type AuditResult = { failures: string[]; sampled: number };

function harvest(page: Page, scopeSelector?: string): Promise<Sample[]> {
  // Everything this callback references must live inside it: page.evaluate
  // serializes the function body only, never the enclosing module scope.
  return page.evaluate((scopeSel) => {
    const PARSE = /rgba?\(\s*([\d.]+)\s*,\s*([\d.]+)\s*,\s*([\d.]+)\s*(?:,\s*([\d.]+)\s*)?\)/;
    const parse = (s: string): { r: number; g: number; b: number; a: number } | null => {
      const m = PARSE.exec(s);
      return m
        ? { r: Number(m[1]), g: Number(m[2]), b: Number(m[3]), a: m[4] === undefined ? 1 : Number(m[4]) }
        : null;
    };

    /** The color actually painted under this element's text: composite the
     *  element's own background with every ancestor's, bottom-up from the
     *  document background, honoring alpha. */
    function effectiveBg(el: Element): { r: number; g: number; b: number } {
      const chain: Element[] = [];
      for (let n: Element | null = el; n; n = n.parentElement) chain.unshift(n);
      const doc = parse(getComputedStyle(document.documentElement).backgroundColor);
      let cur = doc && doc.a > 0 ? { r: doc.r, g: doc.g, b: doc.b } : { r: 0, g: 0, b: 0 };
      for (const n of chain) {
        const bg = parse(getComputedStyle(n).backgroundColor);
        if (!bg || bg.a === 0) continue;
        const a = bg.a;
        cur = {
          r: a * bg.r + (1 - a) * cur.r,
          g: a * bg.g + (1 - a) * cur.g,
          b: a * bg.b + (1 - a) * cur.b
        };
      }
      return cur;
    }

    const SKIP_TAGS = new Set(["SCRIPT", "STYLE", "NOSCRIPT", "SVG", "OPTION", "OPTGROUP", "TITLE"]);

    function exempt(el: Element): boolean {
      for (let n: Element | null = el; n && n !== document.body.parentElement; n = n.parentElement) {
        if (SKIP_TAGS.has(n.tagName)) return true;
        if (n.getAttribute("aria-hidden") === "true") return true;
        if (n.matches("[disabled]")) return true;
        if (n.classList.contains("sr-only")) return true;
      }
      return false;
    }

    function describe(el: Element): string {
      const tag = el.tagName.toLowerCase();
      const cls = typeof el.className === "string"
        ? el.className.split(/\s+/).filter(Boolean).slice(0, 2).join(".")
        : "";
      return cls ? tag + "." + cls : tag;
    }

    function pushSample(samples: Sample[], el: Element, color: string, text: string, pseudo = false) {
      const fg = parse(color);
      if (!fg) return;
      if (parseFloat(getComputedStyle(el).opacity) < 0.95) return;
      const bg = effectiveBg(el);
      const cs = getComputedStyle(el);
      const weight = parseInt(cs.fontWeight, 10);
      samples.push({
        text: text.trim().replace(/\s+/g, " ").slice(0, 60),
        where: describe(el) + (pseudo ? " ::placeholder" : ""),
        fg: { r: fg.r, g: fg.g, b: fg.b },
        bg,
        px: parseFloat(cs.fontSize) || 16,
        bold: Number.isNaN(weight) ? false : weight >= 700
      });
    }

    const scope = (scopeSel ? document.querySelector(scopeSel) : document.body) as Element | null;
    if (!scope) return [] as unknown as Sample[];

    const samples: Sample[] = [];
    // The scope itself is a candidate too: a toast's title is a direct text
    // child of its role="status" root, and descendants-only would miss it.
    for (const el of [scope, ...Array.from(scope.querySelectorAll("*"))]) {
      if (exempt(el)) continue;
      if (el.getClientRects().length === 0) continue;
      let ownText = "";
      for (const c of Array.from(el.childNodes)) {
        if (c.nodeType === Node.TEXT_NODE) ownText += c.nodeValue ?? "";
      }
      if (!ownText.trim()) continue;
      const cs = getComputedStyle(el);
      if (cs.color) pushSample(samples, el, cs.color, ownText);
    }

    // Placeholders: only when the field is empty (that is when they render).
    for (const el of Array.from(scope.querySelectorAll<HTMLInputElement>("input[placeholder]"))) {
      if (el.value !== "") continue;
      if (exempt(el)) continue;
      if (el.getClientRects().length === 0) continue;
      const ph = getComputedStyle(el, "::placeholder");
      if (ph.color && ph.color !== "rgba(0, 0, 0, 0)" && ph.color !== "transparent") {
        pushSample(samples, el, ph.color, "placeholder: " + (el.getAttribute("placeholder") ?? ""), true);
      }
    }

    return samples as unknown as Sample[];
  }, scopeSelector);
}

function channel(c: number): number {
  const s = c / 255;
  return s <= 0.04045 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
}

function luminance({ r, g, b }: RGB): number {
  return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b);
}

export function contrastRatio(a: RGB, b: RGB): number {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x);
  return (hi + 0.05) / (lo + 0.05);
}

/** WCAG 2.2 AA: 3:1 for large text (24px, or ≥18.66px bold), else 4.5:1. */
export function requiredRatio(px: number, bold: boolean): number {
  if (px >= 24) return 3;
  if (px >= 18.66 && bold) return 3;
  return 4.5;
}

function hex(c: number): string {
  return Math.round(c).toString(16).padStart(2, "0");
}

export function colorHex({ r, g, b }: RGB): string {
  return "#" + hex(r) + hex(g) + hex(b);
}

/**
 * Audit every visible text node inside `scopeSelector` (default: the whole
 * page) for WCAG 2.2 AA text contrast. Returns failures with the offending
 * text, its computed colors and the measured ratio; `sampled` is the count of
 * text nodes checked, so callers can assert a surface actually rendered.
 */
export async function auditTextContrast(
  page: Page,
  scopeSelector?: string
): Promise<AuditResult> {
  const samples = await harvest(page, scopeSelector);
  const failures: string[] = [];
  for (const s of samples) {
    const ratio = contrastRatio(s.fg, s.bg);
    const needed = requiredRatio(s.px, s.bold);
    if (ratio + 1e-9 < needed) {
      failures.push(
        `${s.where} "${s.text}" ${colorHex(s.fg)} on ${colorHex(s.bg)} ` +
          `\u2192 ${ratio.toFixed(2)}:1 (needs ${needed}:1)`
      );
    }
  }
  return { failures, sampled: samples.length };
}
