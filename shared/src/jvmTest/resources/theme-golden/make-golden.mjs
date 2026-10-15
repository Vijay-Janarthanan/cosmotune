// Golden vectors for the cover palette: runs the designer's reference implementation (v3/dynamic-colour-v3.js, plain
// browser JavaScript, not changed) on a fixed list of seeds and prints, for both themes, every derived colour as hex.
// CoverPaletteGoldenTest (Kotlin) loads the output, golden.json, and checks that the Kotlin engine gives the same colours.
//
// v3 only adds fields to v2's engine (ui/v3/02-colour-delta.md, line 4): every v2 field of the output is byte-identical to
// what ui/v2/dynamic-colour.js gave. The v3 fields kept here are glass, skelBase, skelShine, the four danger colours and
// dangerClash; `rim` (family A only) is not kept.
//
// Rerun after the designer changes dynamic-colour-v3.js, from this folder:
//   node make-golden.mjs "<path to ui/v3/dynamic-colour-v3.js>" > golden.json
// (the path defaults to the designer's folder on the owner's machine). The output has no clock and no randomness.

import { createHash } from "node:crypto";
import { readFileSync } from "node:fs";
import vm from "node:vm";

const DEFAULT = "D:/Vijay/Personal/Canvas/docs/superpowers/design/cosmotune/ui/v3/dynamic-colour-v3.js";
const path = process.argv[2] || DEFAULT;
const source = readFileSync(path);

// The file is an IIFE that hangs DC on window; the browser-only parts (Image, canvas, requestAnimationFrame) are never reached.
const window = {};
vm.runInNewContext(source.toString("utf8"), { window, Math, Promise, Array, Object, Number, String });
const DC = window.DC;

const hex = (rgb) => "#" + rgb.map((v) => v.toString(16).padStart(2, "0")).join("").toUpperCase();
const rgbOf = (h) => [1, 3, 5].map((i) => parseInt(h.slice(i, i + 2), 16));
const lchOfHex = (h) => DC.labToLch(...DC.rgbToOklab(...rgbOf(h)));
const hexFromLch = (L, C, h) => DC.mk(L, C, h).hex;

// ---- the seeds -------------------------------------------------------------------------------------------------
const cases = [];
const add = (name, seed, extra = {}) => cases.push({ name, seed, second: null, mono: false, bright: null, intensity: "balanced", ...extra });

add("apricot fallback (grey cover)", DC.APRICOT, { mono: true });
add("pure yellow (the designer's Sunblock)", "#FFE500");
add("yellow", "#FFD600");
add("pale yellow", "#FFF7A0");
add("white-ish", "#F2EEE8");
add("near-black", "#14181C");
add("neon pink", "#FF2D95");
add("teal", "#00E5D4");
add("red", "#E02020");
add("blue", "#1E4FD8");
add("green", "#2FA84F");
add("brown", "#7A4E2D");
add("pale blue (bright)", "#CFE3FF");
add("mid blue with a bright cover (mean L above 0.78)", "#8FB4E8", { bright: true });

// The designer's twelve test covers: the seeds of 01-dynamic-colour.md, section 6. A second colour is given where the
// table's bottom background stop shows one (a hue 25 degrees or more from the seed's): its hue is the hue of that dark
// bottom stop in the table, found by converting the table's hex to OKLCH.
add("cover: Glasshouse Weather", "#466044");
add("cover: Parallel Rooms", "#D4552E");
add("cover: Kite Season", "#ADD6C8", { second: hexFromLch(0.55, 0.10, 25.9) });
add("cover: after the rain (Tamil title)", "#2E7C58", { second: hexFromLch(0.55, 0.10, 88.3) });
add("cover: Ninefold", "#2D5A8A", { second: hexFromLch(0.55, 0.10, 42.9) });
add("cover: Night Shift Radio", "#84755F", { second: hexFromLch(0.55, 0.10, 29.1) });
add("cover: Overpass", "#F1AD75");
add("cover: Low Tide Hours", "#2C4E79");
add("cover: Sunblock (bright and yellow rules)", "#FFE500");
add("cover: Paper (grey rule)", DC.APRICOT, { mono: true });
add("cover: Portrait, 1971 (grey rule)", DC.APRICOT, { mono: true });
add("cover: Arcade Heat", "#FF2D95", { second: hexFromLch(0.55, 0.10, 182.7) });

// A sweep: hue, lightness and chroma, so that every branch of the gamut mapping and the contrast loop is hit.
for (let h = 0; h < 360; h += 30) add(`sweep h${h} L0.60 C0.16`, hexFromLch(0.6, 0.16, h));
for (const h of [15, 75, 135, 195, 255, 315]) add(`sweep h${h} L0.40 C0.09`, hexFromLch(0.4, 0.09, h));
for (const h of [45, 105, 165, 225, 285, 345]) add(`sweep h${h} L0.82 C0.06`, hexFromLch(0.82, 0.06, h));
for (const h of [0, 90, 180, 270]) add(`sweep h${h} L0.70 C0.22 (vivid)`, hexFromLch(0.7, 0.22, h));

// A second colour 120 degrees away (used) and 10 degrees away (ignored).
add("second colour used", hexFromLch(0.6, 0.15, 30), { second: hexFromLch(0.6, 0.15, 150) });
add("second colour too close, ignored", hexFromLch(0.6, 0.15, 30), { second: hexFromLch(0.6, 0.15, 40) });
add("yellow with a second colour", "#FFE500", { second: hexFromLch(0.6, 0.15, 260) });

// The intensity setting (Settings > Look): Calm 0.7, Balanced 1.0, Rich 1.25.
add("intensity calm", hexFromLch(0.6, 0.16, 30), { intensity: "calm" });
add("intensity rich", hexFromLch(0.6, 0.16, 30), { intensity: "rich" });
add("intensity rich, vivid teal", "#00E5D4", { intensity: "rich" });
add("intensity calm, yellow", "#FFE500", { intensity: "calm" });

// ---- run the reference ------------------------------------------------------------------------------------------
const FIELDS = [
  ["seed", "seed"], ["ambient", "ambient"], ["backgroundTop", "bgTop"], ["backgroundMid", "bgMid"], ["backgroundBottom", "bgBottom"],
  ["surface", "surface"], ["surfaceHigh", "surfaceHigh"], ["surfaceVariant", "surfaceVariant"],
  ["textPrimary", "textPrimary"], ["textSecondary", "textSecondary"], ["textTertiary", "textTertiary"],
  ["accent", "accent"], ["accentStrong", "accentStrong"], ["onAccent", "onAccent"],
  ["buttonGradientStart", "btnA"], ["buttonGradientEnd", "btnB"],
  ["accentContainer", "accentContainer"], ["onAccentContainer", "onAccentContainer"],
  ["glow", "glow"], ["shadow", "shadow"], ["scrim", "scrim"], ["track", "track"], ["outline", "outline"],
  // v3: the glass tint (carries its alpha), the skeleton pair and the danger family.
  ["glass", "glass"], ["skelBase", "skelBase"], ["skelShine", "skelShine"],
  ["danger", "danger"], ["onDanger", "onDanger"], ["dangerContainer", "dangerContainer"], ["onDangerContainer", "onDangerContainer"],
];

const out = {
  about: "Generated by make-golden.mjs from the designer's dynamic-colour-v3.js. Do not edit by hand.",
  reference: { file: "ui/v3/dynamic-colour-v3.js", bytes: source.length, sha256: createHash("sha256").update(source).digest("hex") },
  cases: cases.map((c) => {
    const seedLch = lchOfHex(c.mono ? DC.APRICOT : c.seed);
    const bright = c.mono ? false : c.bright === null ? seedLch.L > 0.86 : c.bright;
    const ext = {
      seed: { L: seedLch.L, C: seedLch.C, h: seedLch.h },
      second: c.second ? { h: lchOfHex(c.second).h } : null,
      mono: c.mono,
      bright,
    };
    const result = { name: c.name, seed: c.seed, second: c.second, mono: c.mono, bright, intensity: c.intensity };
    for (const theme of ["dark", "light"]) {
      const p = DC.derive(ext, theme, c.intensity);
      const t = { pass: p.pass, failed: p.checks.filter((x) => !x.pass).map((x) => x.name), dangerClash: p.dangerClash };
      for (const [name, key] of FIELDS) {
        t[name] = hex(p[key].rgb);
        if (p[key].a < 1) (t.alpha = t.alpha || {})[name] = p[key].a;
      }
      result[theme] = t;
    }
    return result;
  }),
  // The fade's easing at 0, 0.1 ... 1.
  ease: Array.from({ length: 11 }, (_, i) => [i / 10, DC.ease(i / 10)]),
};

process.stdout.write(JSON.stringify(out, null, 1) + "\n");
