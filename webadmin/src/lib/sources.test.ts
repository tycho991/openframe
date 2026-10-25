import { describe, expect, it } from "vitest";
import { formatTime, isPlainHttp, isShareLink, settingsPatch, statusLine } from "./sources";

const base = {
  name: "cloud.example.org",
  enabled: true,
  folder: "/",
  includeSubfolders: true,
  removeVanished: true,
  intervalMinutes: 20,
  originalsBudgetMb: 2000,
};

const status = {
  syncing: false,
  lastSyncAt: "2026-10-22T10:00:00Z",
  ok: true as boolean | null,
  error: null as string | null,
  added: 2,
  updated: 1,
  removed: 0,
};

describe("Source settings", () => {
  it("sends only what changed", () => {
    expect(settingsPatch(base, { ...base })).toEqual({});
    expect(settingsPatch(base, { ...base, folder: "/Photos", intervalMinutes: 45 })).toEqual({
      folder: "/Photos",
      intervalMinutes: 45,
    });
    expect(settingsPatch(base, { ...base, name: "Family" })).toEqual({ name: "Family" });
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

describe("Share links", () => {
  it("accepts the forms Nextcloud hands out", () => {
    for (const link of [
      "https://cloud.example.org/s/AbC123",
      "https://cloud.example.org/index.php/s/AbC123",
      "https://nas.local:8443/nextcloud/s/AbC123/",
      "http://192.168.100.20/s/AbC123",
      "  https://cloud.example.org/s/AbC123?dir=/  ",
    ]) {
      expect(isShareLink(link), link).toBe(true);
    }
  });

  it("refuses what is no share link", () => {
    for (const link of [
      "",
      "cloud.example.org/s/AbC123",
      "ftp://cloud.example.org/s/AbC123",
      "https://cloud.example.org/apps/files/",
      "https://cloud.example.org/s/",
      "https://cloud.example.org/s/AbC123/extra",
      "https://cloud.example.org/s/Ab-C",
      "https://user:pw@cloud.example.org/s/AbC123",
    ]) {
      expect(isShareLink(link), link).toBe(false);
    }
  });
});

describe("Status line", () => {
  it("says whether a sync runs, never ran, worked or failed", () => {
    expect(statusLine({ ...status, syncing: true }, "en").key).toBe("sources.syncing");
    expect(statusLine({ ...status, ok: null, lastSyncAt: null }, "en").key).toBe("sources.never");
    const ok = statusLine(status, "en");
    expect(ok.key).toBe("sources.lastOk");
    expect(ok.failed).toBe(false);
    expect(ok.params).toMatchObject({ added: 2, updated: 1, removed: 0 });
    expect(String(ok.params["time"])).toMatch(/2026/);
    const failed = statusLine(
      { ...status, ok: false, error: "The share refused the password (HTTP 401)" },
      "en",
    );
    expect(failed.key).toBe("sources.lastFailed");
    expect(failed.failed).toBe(true);
    expect(failed.params["error"]).toBe("The share refused the password (HTTP 401)");
  });
});
