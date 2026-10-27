import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { composite, contrast, parseHex, parseTokens, tokenColor, type Rgba, type Theme } from "./tokens";

const tokens = parseTokens(readFileSync("src/styles/tokens.css", "utf8"));

/** The `<color>` resources of the frame, `#aarrggbb` or `#rrggbb`. */
function frameColors(): Map<string, Rgba> {
  const dir = "../android/src/main/res/values";
  const colors = new Map<string, Rgba>();
  for (const file of readdirSync(dir).filter((f) => f.endsWith(".xml"))) {
    const xml = readFileSync(join(dir, file), "utf8");
    for (const m of xml.matchAll(/<color name="([a-z0-9_]+)">(#[0-9A-Fa-f]+)<\/color>/g))
      if (m[1] && m[2]) colors.set(m[1], parseHex(m[2], true));
  }
  return colors;
}

/** Every pair below its limit, as "fg on bg (theme): ratio < limit". */
function failures(pairs: [string, string, number][], theme: Theme): string[] {
  const color = (name: string) => parseHex(tokenColor(tokens, name, theme));
  return pairs.flatMap(([fg, bg, limit]) => {
    const ratio = contrast(color(fg), color(bg));
    return ratio < limit ? [`${fg} on ${bg} (${theme}): ${ratio.toFixed(2)} < ${limit}`] : [];
  });
}

const webPairs: [string, string, number][] = [
  ...["text", "muted", "danger", "warning"].flatMap((fg) =>
    ["bg", "surface", "surface-2"].map((bg): [string, string, number] => [fg, bg, 4.5]),
  ),
  ["on-accent", "accent", 4.5],
  ...["outline", "focus"].flatMap((fg) => ["bg", "surface"].map((bg): [string, string, number] => [fg, bg, 3])),
];

describe("Design tokens", () => {
  it("parses colours, their two themes and plain values", () => {
    expect(tokenColor(tokens, "surface", "light")).toBe("#ffffff");
    expect(tokenColor(tokens, "surface", "dark")).toBe("#1f1f1d");
    expect(tokens.get("motion-medium")).toBe("250ms");
    expect(tokens.get("target")).toBe("44px");
  });

  it("names the token that is missing or not a colour", () => {
    expect(() => tokenColor(tokens, "nope", "light")).toThrow("--nope");
    expect(() => tokenColor(tokens, "space-1", "light")).toThrow("--space-1");
  });

  it("computes the WCAG contrast", () => {
    expect(contrast(parseHex("#000000"), parseHex("#ffffff"))).toBeCloseTo(21, 5);
    expect(contrast(parseHex("#767676"), parseHex("#ffffff"))).toBeCloseTo(4.54, 2);
  });

  it.each<Theme>(["light", "dark"])("text, outlines and focus reach their contrast in the %s theme", (theme) => {
    expect(failures(webPairs, theme)).toEqual([]);
  });

  it("texts on the frame reach 4.5:1 over the panel on a white photo and on the black background", () => {
    const frame = frameColors();
    const get = (name: string) => {
      const c = frame.get(name);
      if (!c) throw new Error(`no colour ${name} on the frame`);
      return c;
    };
    const white = parseHex("#ffffff");
    const panel = composite(get("frame_panel"), white);
    const background = get("frame_background");
    const pairs: [string, Rgba, string, Rgba][] = [
      ...["frame_text", "frame_text_muted"].map((n): [string, Rgba, string, Rgba] => [
        n,
        composite(get(n), panel),
        "frame_panel over white",
        panel,
      ]),
      // The ds_* colours equal the dark tokens, which checkDesignTokens ensures.
      ...["text", "muted", "accent"].map((n): [string, Rgba, string, Rgba] => [
        `ds_${n}`,
        parseHex(tokenColor(tokens, n, "dark")),
        "frame_background",
        background,
      ]),
    ];
    const low = pairs.flatMap(([fgName, fg, bgName, bg]) => {
      const ratio = contrast(fg, bg);
      return ratio < 4.5 ? [`${fgName} on ${bgName}: ${ratio.toFixed(2)} < 4.5`] : [];
    });
    expect(low).toEqual([]);
  });
});
