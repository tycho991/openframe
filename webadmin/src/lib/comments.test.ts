import { describe, expect, it } from "vitest";
import { commentLength, commentPatch, coordinates, MAX_COMMENT_LENGTH } from "./comments";

describe("comment field", () => {
  it("counts the trimmed text in code points", () => {
    expect(commentLength("  Grandma's 80th birthday ")).toBe(23);
    expect(commentLength("🎂".repeat(MAX_COMMENT_LENGTH))).toBe(500);
    expect(commentLength(" \n ")).toBe(0);
  });

  it("counts a run of line breaks as one space, as the frame stores it", () => {
    expect(commentLength("a\n\n\nb")).toBe(3);
    expect(commentLength("a\r\n\tb")).toBe(3);
    expect(commentLength("a\u2028b")).toBe(3);
    expect(commentLength("x".repeat(498) + "\n\n\n")).toBe(498);
    expect(commentLength("x".repeat(249) + "\n\n\n" + "x".repeat(250))).toBe(500);
  });

  it("removes the comment for an empty field", () => {
    expect(commentPatch("")).toBeNull();
    expect(commentPatch("   ")).toBeNull();
    expect(commentPatch(" Siena ")).toBe(" Siena ");
  });
});

describe("coordinates", () => {
  it("have four decimals in the interface's language", () => {
    const siena = { lat: 43.31880001, lon: 11.3308, place: null };
    expect(coordinates(siena, "en")).toEqual({ lat: "43.3188", lon: "11.3308" });
    expect(coordinates(siena, "de")).toEqual({ lat: "43,3188", lon: "11,3308" });
    expect(coordinates({ lat: -22.9, lon: -43.17291, place: null }, "en")).toEqual({
      lat: "-22.9000",
      lon: "-43.1729",
    });
  });
});
