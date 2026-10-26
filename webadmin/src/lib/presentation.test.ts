import { describe, expect, it } from "vitest";
import table from "../../../api/fixtures/presentation-cases.json";
import type { Crop, FillMode } from "./api/client";
import { AUTO_MAX_CROP, presentationOf, screenFor, shownArea, zoomRange } from "./presentation";

describe("Presentation, the client copy of the core's", () => {
  it("matches every case of the shared table within 1/10,000", () => {
    expect(table.cases.length).toBeGreaterThanOrEqual(20);
    expect(table.autoMaxCrop).toBe(AUTO_MAX_CROP);
    for (const c of table.cases) {
      const actual = presentationOf(c.photo, c.screen, c.crop as Crop | null, c.fillMode as FillMode);
      for (const key of ["x", "y", "width", "height", "zoom"] as const) {
        expect(Math.abs(actual[key] - c.expected[key]), `${key} in ${c.scenario}`).toBeLessThan(1e-4);
      }
    }
  });

  it("gives the zoom range of the editor", () => {
    // 4000×3000 on 1920×1200: fully visible at 0.833, one original pixel per screen pixel at 2.083.
    const range = zoomRange({ width: 4000, height: 3000 }, { width: 1920, height: 1200 });
    expect(range.min).toBeCloseTo(0.8333, 4);
    expect(range.max).toBeCloseTo(2.0833, 4);
  });

  it("puts the screen in the frame's orientation", () => {
    expect(screenFor({ width: 1024, height: 600 }, "landscape")).toEqual({ width: 1024, height: 600 });
    expect(screenFor({ width: 1024, height: 600 }, "portrait")).toEqual({ width: 600, height: 1024 });
    expect(screenFor({ width: 600, height: 1024 }, "landscape")).toEqual({ width: 1024, height: 600 });
  });

  it("knows where the shown part stands on the screen", () => {
    // A portrait photo in `auto` on a landscape frame: 1200 of 1920 pixels wide, borders left and right.
    const photo = { width: 3000, height: 4000 };
    const screen = { width: 1920, height: 1200 };
    const area = shownArea(photo, screen, presentationOf(photo, screen, null, "auto"));
    expect(area.width).toBeCloseTo(0.625, 6);
    expect(area.height).toBeCloseTo(1, 6);
    expect(shownArea(photo, screen, presentationOf(photo, screen, null, "fill"))).toEqual({
      width: 1,
      height: 1,
    });
  });
});
