import { describe, expect, it } from "vitest";
import { gridLayout } from "./grid";

describe("gridLayout", () => {
  it("renders only the visible rows", () => {
    const l = gridLayout({ width: 1280, count: 10_000, scrollY: 0, viewport: 800, gap: 6, minTile: 150 });
    expect(l.columns).toBe(8);
    expect(l.first).toBe(0);
    expect(l.last).toBeLessThan(100);
    expect(l.nearEnd).toBe(false);
  });

  it("further down", () => {
    const l = gridLayout({
      width: 1280,
      count: 10_000,
      scrollY: 50_000,
      viewport: 800,
      gap: 6,
      minTile: 150,
    });
    expect(l.first).toBeGreaterThan(2000);
    expect(l.last - l.first).toBeLessThan(100);
  });

  it("narrow phone with at least two columns", () => {
    const l = gridLayout({ width: 200, count: 5, scrollY: 0, viewport: 600 });
    expect(l.columns).toBe(2);
    expect(l.last).toBe(5);
    expect(l.nearEnd).toBe(true);
    expect(l.position(3)).toEqual({ x: l.tile + 6, y: l.tile + 6 });
  });
});
