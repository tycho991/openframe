import { render } from "svelte/server";
import { describe, expect, it } from "vitest";
import type { Crop, FillMode, Photo } from "../lib/api/client";
import { i18n } from "../lib/i18n/i18n.svelte";
import SectionEditor from "./SectionEditor.svelte";

/** Spec web-admin, "Warning for an enlarged section", on a frame with a screen of 1920×1200. */
describe("Warning for an enlarged section", () => {
  const screen = { width: 1920, height: 1200 };

  function editor(
    width: number,
    height: number,
    crop: Crop | null,
    fillMode: FillMode = "fill",
    lang = "en",
  ): string {
    i18n.lang = lang as typeof i18n.lang;
    const photo = {
      id: "local-1",
      path: "a.jpg",
      width,
      height,
      crop,
      urls: { thumb: "/t", display: "/d", full: "/f" },
    } as unknown as Photo;
    return render(SectionEditor, {
      props: { photo, screen, fillMode, onsave: async () => {}, oncancel: () => {} },
    }).body;
  }

  /** The text of the editor's status line. */
  function status(body: string): string {
    const m = body.match(/<p class="enlarged[^"]*" role="status"[^>]*>([\s\S]*?)<\/p>/);
    if (!m) throw new Error("no status line");
    return m[1]!.replace(/<[^>]*>/g, "").trim();
  }

  it("Zoom into a phone photo", () => {
    expect(status(editor(1600, 1200, { x: 0.5, y: 0.5, zoom: 2 }))).toBe(
      "The frame enlarges this part: it has about 42 % of the screen's resolution and may look blurry.",
    );
  });

  it("Sharp again", () => {
    // 4000×3000 is sharp up to about 2.08.
    expect(status(editor(4000, 3000, { x: 0.5, y: 0.5, zoom: 2.6 }))).toContain("about 80 %");
    expect(status(editor(4000, 3000, { x: 0.5, y: 0.5, zoom: 1 / 0.48 }))).toBe("");
    expect(status(editor(4000, 3000, { x: 0.5, y: 0.5, zoom: 1.5 }))).toBe("");
  });

  it("Small photo without a section", () => {
    expect(status(editor(1280, 960, null))).toContain("about 67 %");
  });

  it("names the share in German too", () => {
    expect(status(editor(1600, 1200, { x: 0.5, y: 0.5, zoom: 2 }, "fill", "de"))).toContain(
      "etwa 42 % der Bildschirmauflösung",
    );
    i18n.lang = "en";
  });
});
