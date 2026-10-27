/** Reads `styles/tokens.css` in the shape it is written in: one declaration per line, colours as `#rrggbb` or
 *  `#rrggbbaa`, the two themes of a colour as `light-dark(light, dark)`. Used by the contrast tests; the frame's
 *  `checkDesignTokens` reads the same file with the same assumptions. */

export type Theme = "light" | "dark";

export interface Rgba {
  r: number;
  g: number;
  b: number;
  a: number;
}

/** Every custom property of the file with its raw value, the first declaration winning (later blocks only narrow
 *  the theme or turn off motion). */
export function parseTokens(css: string): Map<string, string> {
  const tokens = new Map<string, string>();
  for (const m of css.matchAll(/^\s*--([a-z0-9-]+):\s*(.+?);\s*$/gm)) {
    const [, name, value] = m;
    if (name && value && !tokens.has(name)) tokens.set(name, value);
  }
  return tokens;
}

/** The colour of a token in one theme, or an error naming the token if it is not a colour of the expected shape. */
export function tokenColor(tokens: Map<string, string>, name: string, theme: Theme): string {
  const value = tokens.get(name);
  if (value === undefined) throw new Error(`no token --${name}`);
  const pair = /^light-dark\(\s*(#[0-9a-f]+)\s*,\s*(#[0-9a-f]+)\s*\)$/i.exec(value);
  const color = pair ? (theme === "light" ? pair[1] : pair[2]) : value;
  if (!color || !/^#([0-9a-f]{6}|[0-9a-f]{8})$/i.test(color))
    throw new Error(`--${name} is not a colour: ${value}`);
  return color.toLowerCase();
}

/** `#rrggbb`, `#rrggbbaa` (CSS) or, with `android`, `#aarrggbb`. */
export function parseHex(hex: string, android = false): Rgba {
  const digits = hex.replace(/^#/, "");
  const byte = (i: number) => parseInt(digits.slice(i, i + 2), 16);
  if (digits.length === 6) return { r: byte(0), g: byte(2), b: byte(4), a: 1 };
  if (digits.length !== 8) throw new Error(`not a colour: ${hex}`);
  return android
    ? { a: byte(0) / 255, r: byte(2), g: byte(4), b: byte(6) }
    : { r: byte(0), g: byte(2), b: byte(4), a: byte(6) / 255 };
}

/** A translucent colour painted over an opaque one. */
export function composite(top: Rgba, below: Rgba): Rgba {
  const mix = (t: number, b: number) => Math.round(t * top.a + b * (1 - top.a));
  return { r: mix(top.r, below.r), g: mix(top.g, below.g), b: mix(top.b, below.b), a: 1 };
}

function luminance({ r, g, b }: Rgba): number {
  const channel = (c: number) => {
    const s = c / 255;
    return s <= 0.04045 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4;
  };
  return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b);
}

/** The WCAG 2.2 contrast ratio of two opaque colours. */
export function contrast(a: Rgba, b: Rgba): number {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x) as [number, number];
  return (hi + 0.05) / (lo + 0.05);
}
