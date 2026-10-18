import { describe, expect, it } from "vitest";
import { appTitle } from "./placeholder";

describe("appTitle", () => {
  it("heißt openframe", () => {
    expect(appTitle()).toBe("openframe");
  });
});
