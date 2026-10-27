import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { filledIcons, icons } from "./icons";

/** The Svelte files of the interface: the shell and every component. */
function svelteFiles(): string[] {
  return ["src/App.svelte", ...readdirSync("src/components").map((f) => join("src/components", f))].filter(
    (f) => f.endsWith(".svelte"),
  );
}

describe("Icons", () => {
  it("every icon name used in the components exists", () => {
    const used = new Set<string>();
    for (const file of svelteFiles()) {
      const source = readFileSync(file, "utf8");
      for (const m of source.matchAll(/<Icon\b[^>]*?\bname="([A-Za-z]+)"/g)) if (m[1]) used.add(m[1]);
      for (const m of source.matchAll(/\bicon: "([A-Za-z]+)"/g)) if (m[1]) used.add(m[1]);
    }
    expect(used.size).toBeGreaterThan(0);
    expect([...used].filter((name) => !(name in icons))).toEqual([]);
  });

  it("every filled form has its outlined form", () => {
    expect(Object.keys(filledIcons).filter((name) => !(name in icons))).toEqual([]);
  });

  it("holds path data in the 960 grid", () => {
    for (const [name, d] of Object.entries(icons)) expect(d, name).toMatch(/^[Mm][-0-9.,a-zA-Z ]+$/);
  });
});
