import { describe, expect, it } from "vitest";
import { FIRST_PAGE_MARGIN_VIEWPORTS, gridLayout, OVERSCAN_ROWS, reservedLayout } from "./grid";
import { FIRST_PAGE_LIMIT } from "./photos";

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

  it("asks for more only when the visible area with the overscan rows overlaps the grid", () => {
    // 16 photos in 8 columns: 2 rows of 160 px (tile 154 + gap 6), so the visible rows always reach the last ones.
    const base = { width: 1274, count: 16, viewport: 800, gap: 6, minTile: 150 };
    const row = gridLayout({ ...base, scrollY: 0 }).tile + 6;
    expect(row).toBe(160);
    const height = gridLayout({ ...base, scrollY: 0 }).height;
    // Below the screen: the grid starts just beyond the overscan rows, or just inside them.
    const below = 800 + OVERSCAN_ROWS * row;
    expect(gridLayout({ ...base, scrollY: -below }).nearEnd).toBe(false);
    expect(gridLayout({ ...base, scrollY: -below + 1 }).nearEnd).toBe(true);
    // Above the screen: its end lies just beyond the overscan rows, or just inside them.
    const above = height + OVERSCAN_ROWS * row;
    expect(gridLayout({ ...base, scrollY: above }).nearEnd).toBe(false);
    expect(gridLayout({ ...base, scrollY: above - 1 }).nearEnd).toBe(true);
    const past = gridLayout({ ...base, scrollY: above + row });
    expect(past.last - past.first).toBe(0);
  });

  it("at 1280 x 800 px a first page of 60 at the top reaches its end at once", () => {
    // The grid starts below the toolbar and its heading; page 2 follows at once, which the e2e request counts allow.
    const l = gridLayout({ width: 1280, count: FIRST_PAGE_LIMIT, scrollY: -150, viewport: 800 });
    expect(l.nearEnd).toBe(true);
  });
});

describe("reservedLayout", () => {
  const base = { width: 1274, viewport: 800, gap: 6, minTile: 150 };

  it("reserves the height of the first page, at most 60 tiles", () => {
    const full = gridLayout({ ...base, count: FIRST_PAGE_LIMIT, scrollY: 0 }).height;
    expect(reservedLayout({ ...base, count: 10_000, scrollY: 0 }).height).toBe(full);
    expect(reservedLayout({ ...base, count: 9, scrollY: 0 }).height).toBe(2 * 160 - 6);
  });

  it("asks for the first page only within one viewport height of the screen", () => {
    const margin = FIRST_PAGE_MARGIN_VIEWPORTS * base.viewport;
    const count = 30;
    const height = reservedLayout({ ...base, count, scrollY: 0 }).height;
    // Below the screen.
    expect(reservedLayout({ ...base, count, scrollY: -(800 + margin) }).wanted).toBe(false);
    expect(reservedLayout({ ...base, count, scrollY: -(800 + margin) + 1 }).wanted).toBe(true);
    // Above the screen.
    expect(reservedLayout({ ...base, count, scrollY: height + margin }).wanted).toBe(false);
    expect(reservedLayout({ ...base, count, scrollY: height + margin - 1 }).wanted).toBe(true);
  });

  it("asks for nothing before the grid has a width", () => {
    expect(reservedLayout({ ...base, width: 0, count: 30, scrollY: 0 }).wanted).toBe(false);
  });
});
