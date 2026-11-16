import { describe, expect, it } from "vitest";
import table from "../../../api/fixtures/presentation-cases.json";
import type { Crop, FillMode } from "./api/client";
import {
  AUTO_MAX_CROP,
  enlargedPercent,
  enlargement,
  presentationOf,
  screenFor,
  sharpZoom,
  shownArea,
  zoomRange,
  zoomStep,
} from "./presentation";

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
    // 4000×3000 on 1920×1200: fully visible at 0.833, one original pixel per screen pixel at 2.083, up to 4 beyond it.
    const photo = { width: 4000, height: 3000 };
    const screen = { width: 1920, height: 1200 };
    const range = zoomRange(photo, screen);
    expect(range.min).toBeCloseTo(0.8333, 4);
    expect(range.max).toBe(4);
    expect(sharpZoom(photo, screen)).toBeCloseTo(2.0833, 4);
    // A photo smaller than the screen can be zoomed in as well.
    expect(zoomRange({ width: 800, height: 600 }, screen).max).toBe(4);
  });

  it("says how much the frame enlarges what it shows", () => {
    const screen = { width: 1920, height: 1200 };
    const phone = { width: 1600, height: 1200 };
    // 1600×1200 at zoom 2: 2.4 screen pixels per original pixel, about 42 % of the screen's resolution.
    expect(
      enlargement(phone, screen, presentationOf(phone, screen, { x: 0.5, y: 0.5, zoom: 2 }, "fill")),
    ).toBeCloseTo(2.4, 6);
    // 8000×6000 at zoom 4: still more original pixels than the screen.
    const large = { width: 8000, height: 6000 };
    expect(
      enlargement(large, screen, presentationOf(large, screen, { x: 0.5, y: 0.5, zoom: 4 }, "fill")),
    ).toBeCloseTo(0.96, 6);
  });

  it("names the share of the screen's resolution only while the part is enlarged", () => {
    expect(enlargedPercent(2.4)).toBe(42);
    expect(enlargedPercent(1.5)).toBe(67);
    // At the sharp zoom, also with a rounded stored section, and below it: no warning.
    expect(enlargedPercent(1)).toBeNull();
    expect(enlargedPercent(1.004)).toBeNull();
    expect(enlargedPercent(0.96)).toBeNull();
  });

  // Spec web-admin › Zoom buttons stop at the sharp limit › Stop before enlarging: 4000×3000 on 1920×1200.
  it("stops a button step at the sharp zoom, in both directions, but not a wheel or pinch step", () => {
    const range = { min: 0.8333, max: 4 };
    const sharp = 1 / 0.48;
    expect(zoomStep(1.8, 1.25, range, sharp, true)).toBeCloseTo(sharp, 9);
    expect(zoomStep(sharp, 1.25, range, sharp, true)).toBeCloseTo(sharp * 1.25, 9);
    expect(zoomStep(2.6, 1 / 1.25, range, sharp, true)).toBeCloseTo(sharp, 9);
    expect(zoomStep(sharp, 1 / 1.25, range, sharp, true)).toBeCloseTo(sharp / 1.25, 9);
    expect(zoomStep(1.8, 1.35, range, sharp, false)).toBeCloseTo(2.43, 9);
    // The range still limits every step.
    expect(zoomStep(3.8, 1.25, range, sharp, true)).toBe(4);
  });

  it("has no stop when the sharp zoom lies outside the range", () => {
    // 800×600 on 1920×1200 is sharp at about 0.42, below the whole photo at about 0.83.
    expect(zoomStep(1, 1.25, { min: 0.8333, max: 4 }, 0.4167, true)).toBeCloseTo(1.25, 9);
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
