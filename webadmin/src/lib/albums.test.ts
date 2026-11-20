import { describe, expect, it } from "vitest";
import type { Album } from "./api/client";
import {
  albumHeadings,
  albumOptions,
  albumTree,
  displayPath,
  flatten,
  gridHeadings,
  libraryCount,
  NO_ALBUM,
  renderOrder,
} from "./albums";

function album(name: string, count = 1, visibleCount = count): Album {
  return { name, count, visibleCount, hidden: false, overridesParent: false, cover: null };
}

const none = { count: 0, visibleCount: 0 };

describe("Album tree", () => {
  it("sorts every level case-insensitively, whatever the order of the list", () => {
    const tree = albumTree(
      ["holiday/2024", "Family", "holiday", "holiday/2023", "archive"].map((name) => album(name)),
    );
    expect(tree.map((n) => n.title)).toEqual(["archive", "Family", "holiday"]);
    expect(tree[2]!.children.map((n) => n.path)).toEqual(["holiday/2023", "holiday/2024"]);
    expect(flatten(tree).map((n) => n.depth)).toEqual([0, 0, 0, 1, 1]);
  });

  it("creates the albums on the way that the list leaves out", () => {
    const tree = albumTree([album("a/b/c")]);
    const nodes = flatten(tree);
    expect(nodes.map((n) => n.path)).toEqual(["a", "a/b", "a/b/c"]);
    expect(nodes.map((n) => n.album?.name ?? null)).toEqual([null, null, "a/b/c"]);
    expect(nodes.map((n) => n.title)).toEqual(["a", "b", "c"]);
  });

  it("keeps names with spaces and puts sub-albums right below their parent", () => {
    const tree = albumTree(["a b", "a", "a/x", "summer holiday/day 1"].map((name) => album(name)));
    expect(flatten(tree).map((n) => n.path)).toEqual([
      "a",
      "a/x",
      "a b",
      "summer holiday",
      "summer holiday/day 1",
    ]);
    expect(flatten(tree).at(-1)!.title).toBe("day 1");
  });

  it("indents the options by depth and labels them with the whole path", () => {
    expect(albumOptions(["holiday", "holiday/2024", "holiday/2024/may"].map((name) => album(name)))).toEqual([
      { value: "holiday", label: "holiday" },
      { value: "holiday/2024", label: "\u00a0\u00a0holiday / 2024" },
      { value: "holiday/2024/may", label: "\u00a0\u00a0\u00a0\u00a0holiday / 2024 / may" },
    ]);
    expect(displayPath("summer holiday/day 1")).toBe("summer holiday / day 1");
  });
});

describe("Album headings", () => {
  // holiday: 10 own photos, 2 hidden; holiday/2024: 30, 3 hidden; family: 5, all visible.
  const library = [album("holiday", 40, 35), album("holiday/2024", 30, 27), album("family", 5)];

  it("Nested albums: own photos come before the headings of the sub-albums", () => {
    const headings = albumHeadings({
      albums: library,
      noAlbum: none,
      open: new Set(["holiday"]),
      filter: "all",
    });
    expect(headings.map((h) => h.path)).toEqual(["family", "holiday"]);
    expect(renderOrder(headings)).toEqual([
      { kind: "heading", path: "family" },
      { kind: "heading", path: "holiday" },
      { kind: "grid", path: "holiday" },
      { kind: "heading", path: "holiday/2024" },
    ]);
    const sub = headings[1]!.children[0]!;
    expect(sub).toMatchObject({ title: "2024", depth: 1, count: 30, ownCount: 30, open: false, grid: false });
    expect(sub.album?.name).toBe("holiday/2024");
  });

  it("a folded heading shows nothing below it, also not its open sub-albums", () => {
    const headings = albumHeadings({
      albums: library,
      noAlbum: none,
      open: new Set(["holiday/2024"]),
      filter: "all",
    });
    expect(renderOrder(headings).map((r) => r.path)).toEqual(["family", "holiday"]);
    expect(gridHeadings(headings)).toEqual([]);
  });

  it("own counts are the count minus the direct children's counts", () => {
    const headings = albumHeadings({
      albums: [album("a", 12), album("a/b", 7), album("a/b/c", 4), album("a/d", 5)],
      noAlbum: none,
      open: new Set(["a", "a/b"]),
      filter: "all",
    });
    const a = headings[0]!;
    expect(a.ownCount).toBe(0);
    expect(a.grid).toBe(false);
    expect(a.children.map((h) => [h.path, h.ownCount, h.grid])).toEqual([
      ["a/b", 3, true],
      ["a/d", 5, false],
    ]);
    expect(gridHeadings(headings).map((h) => h.path)).toEqual(["a/b"]);
  });

  it("Album without photos of its own: no grid, and nothing to request", () => {
    const headings = albumHeadings({
      albums: [album("holiday", 30), album("holiday/2024", 30)],
      noAlbum: none,
      open: new Set(["holiday", "holiday/2024"]),
      filter: "all",
    });
    expect(gridHeadings(headings).map((h) => h.path)).toEqual(["holiday/2024"]);
  });

  it("Photos without an album: a heading of their own after all albums, without a switch", () => {
    const headings = albumHeadings({
      albums: library,
      noAlbum: { count: 3, visibleCount: 2 },
      open: new Set([NO_ALBUM]),
      filter: "all",
    });
    const last = headings.at(-1)!;
    expect(last).toMatchObject({ path: NO_ALBUM, depth: 0, count: 3, ownCount: 3, album: null, grid: true });
    expect(renderOrder(headings).slice(-2)).toEqual([
      { kind: "heading", path: NO_ALBUM },
      { kind: "grid", path: NO_ALBUM },
    ]);
    expect(
      albumHeadings({ albums: library, noAlbum: none, open: new Set(), filter: "all" }).at(-1)!.path,
    ).toBe("holiday");
  });

  it("Count follows the filter, and albums without matching photos are left out", () => {
    const hidden = albumHeadings({
      albums: library,
      noAlbum: { count: 3, visibleCount: 3 },
      open: new Set(["holiday"]),
      filter: "hidden",
    });
    expect(hidden.map((h) => [h.path, h.count, h.ownCount])).toEqual([["holiday", 5, 2]]);
    expect(hidden[0]!.children.map((h) => [h.path, h.count])).toEqual([["holiday/2024", 3]]);
    const visible = albumHeadings({
      albums: library,
      noAlbum: { count: 3, visibleCount: 3 },
      open: new Set(),
      filter: "visible",
    });
    expect(visible.map((h) => [h.path, h.count])).toEqual([
      ["family", 5],
      ["holiday", 35],
      [NO_ALBUM, 3],
    ]);
    expect(libraryCount(library, { count: 3, visibleCount: 3 }, "hidden")).toBe(5);
    expect(libraryCount(library, { count: 3, visibleCount: 3 }, "visible")).toBe(43);
    expect(libraryCount(library, { count: 3, visibleCount: 3 }, "all")).toBe(48);
  });

  it("keeps a pinned album with a count of 0 under a filter", () => {
    const albums = [album("holiday", 10, 10), album("family", 5)];
    const open = new Set(["holiday"]);
    expect(albumHeadings({ albums, noAlbum: none, open, filter: "hidden" })).toEqual([]);
    const pinned = albumHeadings({
      albums,
      noAlbum: none,
      open,
      filter: "hidden",
      pinned: new Set(["holiday"]),
    });
    expect(pinned.map((h) => [h.path, h.count, h.grid])).toEqual([["holiday", 0, false]]);
  });

  it("keeps a pinned holiday/2024 under 'only hidden' and its parent holiday without own photos at 0", () => {
    // Every photo of holiday/2024 was hidden by the album; its switch was turned on, so none is hidden any more.
    const albums = [album("holiday", 20, 20), album("holiday/2024", 20, 20), album("holiday/2023", 0, 0)];
    const headings = albumHeadings({
      albums,
      noAlbum: none,
      open: new Set(["holiday", "holiday/2024"]),
      filter: "hidden",
      pinned: new Set(["holiday/2024"]),
    });
    expect(headings.map((h) => [h.path, h.count])).toEqual([["holiday", 0]]);
    expect(headings[0]!.children.map((h) => [h.path, h.count, h.grid])).toEqual([["holiday/2024", 0, false]]);
  });

  it("Empty library: no heading", () => {
    expect(albumHeadings({ albums: [], noAlbum: none, open: new Set([NO_ALBUM]), filter: "all" })).toEqual(
      [],
    );
    expect(libraryCount([], none, "all")).toBe(0);
  });

  it("ignores open paths that are not in the list", () => {
    const headings = albumHeadings({
      albums: [album("family")],
      noAlbum: none,
      open: new Set(["gone", "gone/deeper"]),
      filter: "all",
    });
    expect(headings.map((h) => [h.path, h.open])).toEqual([["family", false]]);
  });
});
