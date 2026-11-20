// @vitest-environment jsdom
import { beforeEach, describe, expect, it } from "vitest";
import { MAX_OPEN_ALBUMS, OPEN_ALBUMS_KEY, OpenAlbums } from "./openAlbums";

describe("Open album headings", () => {
  beforeEach(() => localStorage.clear());

  it("First visit: every heading is folded", () => {
    const open = new OpenAlbums();
    expect(open.paths).toEqual([]);
    expect(open.has("holiday")).toBe(false);
  });

  it("Remembered state: what was opened is open after a reload, the rest folded", () => {
    const before = new OpenAlbums();
    before.open("holiday");
    before.open("");
    before.open("family");
    before.close("family");
    const after = new OpenAlbums();
    expect(after.paths).toEqual(["holiday", ""]);
    expect(after.has("family")).toBe(false);
    expect(JSON.parse(localStorage.getItem(OPEN_ALBUMS_KEY)!)).toEqual(["holiday", ""]);
  });

  it("opens an album with every album above it", () => {
    const open = new OpenAlbums();
    open.open("holiday/2024");
    open.openWithAncestors("holiday/2024/may");
    expect(open.paths).toEqual(["holiday/2024", "holiday", "holiday/2024/may"]);
    open.toggle("holiday");
    expect(open.has("holiday")).toBe(false);
  });

  it("works in memory when the storage throws, and starts folded", () => {
    const broken = () => {
      throw new DOMException("denied", "SecurityError");
    };
    const open = new OpenAlbums(broken);
    expect(open.paths).toEqual([]);
    open.open("holiday");
    expect(open.has("holiday")).toBe(true);
    const full = {
      getItem: () => null,
      setItem: () => {
        throw new DOMException("full", "QuotaExceededError");
      },
    } as unknown as Storage;
    const quota = new OpenAlbums(() => full);
    quota.open("family");
    expect(quota.paths).toEqual(["family"]);
  });

  it("starts folded when the stored value is not a list of paths", () => {
    localStorage.setItem(OPEN_ALBUMS_KEY, "{not json");
    expect(new OpenAlbums().paths).toEqual([]);
    localStorage.setItem(OPEN_ALBUMS_KEY, JSON.stringify({ holiday: true }));
    expect(new OpenAlbums().paths).toEqual([]);
    localStorage.setItem(OPEN_ALBUMS_KEY, JSON.stringify(["holiday", 3, null, "holiday"]));
    expect(new OpenAlbums().paths).toEqual(["holiday"]);
  });

  it("Source briefly unreachable: an open path missing from the album list is kept", () => {
    const open = new OpenAlbums();
    open.open("share/holiday");
    open.open("family");
    // The overview renders only paths in the album list, but stores them all; folding another one keeps it.
    open.close("family");
    expect(new OpenAlbums().has("share/holiday")).toBe(true);
  });

  it("keeps at most 1,000 paths and drops the oldest opened first", () => {
    const open = new OpenAlbums();
    for (let i = 0; i < MAX_OPEN_ALBUMS + 5; i++) open.open(`album ${i}`);
    const stored = new OpenAlbums().paths;
    expect(stored).toHaveLength(MAX_OPEN_ALBUMS);
    expect(stored[0]).toBe("album 5");
    expect(stored.at(-1)).toBe(`album ${MAX_OPEN_ALBUMS + 4}`);
  });
});
