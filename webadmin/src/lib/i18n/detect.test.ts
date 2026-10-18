import { describe, expect, it } from "vitest";
import de from "./de.json";
import { detectLanguage, format } from "./detect";
import en from "./en.json";

describe("Sprache", () => {
  it("Deutscher Browser", () => {
    expect(detectLanguage(["de-DE", "en-US"])).toBe("de");
    expect(detectLanguage(["de-AT"])).toBe("de");
  });

  it("fällt auf Englisch zurück", () => {
    expect(detectLanguage(["fr-FR", "es"])).toBe("en");
    expect(detectLanguage([])).toBe("en");
    expect(detectLanguage(["en-GB", "de"])).toBe("en");
  });

  it("ersetzt Platzhalter", () => {
    expect(format("{n} Fotos in {album}", { n: 3, album: "urlaub" })).toBe("3 Fotos in urlaub");
    expect(format("{fehlt}")).toBe("{fehlt}");
  });

  it("beide Kataloge haben dieselben Schlüssel", () => {
    expect(Object.keys(en).sort()).toEqual(Object.keys(de).sort());
  });
});
