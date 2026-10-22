import { describe, expect, it } from "vitest";
import { formatTime, isPlainHttp, settingsPatch } from "./nextcloud";

const base = {
  enabled: true,
  folder: "/",
  includeSubfolders: true,
  removeVanished: true,
  intervalMinutes: 20,
  originalsBudgetMb: 2000,
};

describe("Nextcloud settings", () => {
  it("sends only what changed", () => {
    expect(settingsPatch(base, { ...base })).toEqual({});
    expect(settingsPatch(base, { ...base, folder: "/Photos", intervalMinutes: 45 })).toEqual({
      folder: "/Photos",
      intervalMinutes: 45,
    });
    expect(settingsPatch(base, { ...base, removeVanished: false })).toEqual({ removeVanished: false });
  });

  it("recognizes an address without encryption", () => {
    expect(isPlainHttp("http://nas.local/nextcloud")).toBe(true);
    expect(isPlainHttp("  HTTP://x.org")).toBe(true);
    expect(isPlainHttp("https://cloud.example.org")).toBe(false);
    expect(isPlainHttp("")).toBe(false);
  });

  it("formats a time and survives a wrong one", () => {
    expect(formatTime(null, "en")).toBe("");
    expect(formatTime("not a date", "en")).toBe("not a date");
    expect(formatTime("2026-10-22T10:00:00Z", "en")).toMatch(/2026/);
  });
});
