import { describe, expect, it } from "vitest";
import de from "./de.json";
import { detectLanguage, format } from "./detect";
import en from "./en.json";

describe("Language", () => {
  it("German browser", () => {
    expect(detectLanguage(["de-DE", "en-US"])).toBe("de");
    expect(detectLanguage(["de-AT"])).toBe("de");
  });

  it("falls back to English", () => {
    expect(detectLanguage(["fr-FR", "es"])).toBe("en");
    expect(detectLanguage([])).toBe("en");
    expect(detectLanguage(["en-GB", "de"])).toBe("en");
  });

  it("replaces placeholders", () => {
    expect(format("{n} photos in {album}", { n: 3, album: "holiday" })).toBe("3 photos in holiday");
    expect(format("{missing}")).toBe("{missing}");
  });

  it("both catalogs have the same keys", () => {
    expect(Object.keys(en).sort()).toEqual(Object.keys(de).sort());
  });
});
