import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";

describe("Licences", () => {
  // scripts/build-places.py writes both; the licence menu links the copy in public/licenses.
  it("the GeoNames source note of the web UI equals the one next to the place data", () => {
    expect(readFileSync("public/licenses/GeoNames.txt", "utf8")).toBe(
      readFileSync("../core/src/main/resources/places/SOURCE.txt", "utf8"),
    );
  });
});
