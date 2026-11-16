import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import { contrast, parseHex, parseTokens, tokenColor } from "./tokens";

// openspec change app-icon, design.md, decision 2: favicon.svg is the source of every icon file, its colours are
// tokens, and the home-screen PNG has the size iOS asks for.
const tokens = parseTokens(readFileSync("src/styles/tokens.css", "utf8"));
const svg = readFileSync("public/favicon.svg", "utf8");

/** The fill or stroke of the first element of that kind in favicon.svg. */
function colorOf(element: "rect" | "path" | "circle", attribute: "fill" | "stroke"): string {
  const tag = new RegExp(`<${element}\\b[^>]*>`).exec(svg)?.[0];
  if (!tag) throw new Error(`favicon.svg has no <${element}>`);
  const value = new RegExp(`\\b${attribute}="([^"]+)"`).exec(tag)?.[1];
  if (!value) throw new Error(`<${element}> in favicon.svg has no ${attribute}`);
  return value.toLowerCase();
}

describe("App icon", () => {
  it("takes its colours from the design tokens", () => {
    expect(colorOf("rect", "fill"), "tile = --bg (dark)").toBe(tokenColor(tokens, "bg", "dark"));
    expect(colorOf("path", "stroke"), "frame = --accent (dark)").toBe(tokenColor(tokens, "accent", "dark"));
    expect(colorOf("circle", "fill"), "sun = --icon-sun").toBe(tokenColor(tokens, "icon-sun", "dark"));
  });

  it("keeps the frame and the sun apart from the tile at small sizes", () => {
    const tile = parseHex(colorOf("rect", "fill"));
    expect(contrast(parseHex(colorOf("path", "stroke")), tile)).toBeGreaterThanOrEqual(3);
    expect(contrast(parseHex(colorOf("circle", "fill")), tile)).toBeGreaterThanOrEqual(3);
  });

  it("has a 180×180 PNG for the home screen", () => {
    const png = readFileSync("public/apple-touch-icon.png");
    expect(png.subarray(1, 4).toString("latin1")).toBe("PNG");
    // IHDR: width and height as big-endian integers at bytes 16 and 20.
    expect([png.readUInt32BE(16), png.readUInt32BE(20)]).toEqual([180, 180]);
  });
});
