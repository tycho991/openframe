import { readdirSync, readFileSync, statSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import en from "./en.json";

/** Every `*.ts` and `*.svelte` file under `src/`, without the tests. */
function sourceFiles(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) return sourceFiles(path);
    if (path.endsWith(".test.ts")) return [];
    return path.endsWith(".ts") || path.endsWith(".svelte") ? [path] : [];
  });
}

/**
 * Keys in use: a key counts as used if it appears as a quoted literal, or starts with a prefix that the source itself
 * builds a key from: a template literal inside `t(` or `tOr(` (`` t(`prefix.${ ``) and the first argument of
 * `option("prefix", …)`, whose helper builds `` tOr(`${prefix}.${value}`) ``. No list by hand: a new dynamic key that
 * these patterns miss is fixed in the patterns, not by deleting the key (design decision 5 of `core-cleanup`).
 */
function usage(sources: string[]): { literals: Set<string>; prefixes: Set<string> } {
  const literals = new Set<string>();
  const prefixes = new Set<string>();
  for (const source of sources) {
    for (const m of source.matchAll(/(["'`])([A-Za-z0-9_.-]+)\1/g)) if (m[2]) literals.add(m[2]);
    for (const m of source.matchAll(/\btOr?\(\s*`([A-Za-z0-9_.-]+\.)\$\{/g)) if (m[1]) prefixes.add(m[1]);
    for (const m of source.matchAll(/\boption\(\s*(["'`])([A-Za-z0-9_.-]+)\1\s*,/g))
      if (m[2]) prefixes.add(`${m[2]}.`);
  }
  return { literals, prefixes };
}

describe("Texts", () => {
  it("every key of en.json is used somewhere in the source", () => {
    const { literals, prefixes } = usage(sourceFiles("src").map((file) => readFileSync(file, "utf8")));
    expect(prefixes.size).toBeGreaterThan(0);
    const unused = Object.keys(en).filter(
      (key) => !literals.has(key) && ![...prefixes].some((prefix) => key.startsWith(prefix)),
    );
    expect(unused).toEqual([]);
  });
});
