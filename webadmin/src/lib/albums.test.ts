import { describe, expect, it } from "vitest";
import type { Album } from "./api/client";
import { albumOptions, albumTree, displayPath, flatten } from "./albums";

function album(name: string): Album {
  return { name, count: 1, hidden: false, overridesParent: false, cover: null };
}

describe("Album tree", () => {
  it("sorts every level case-insensitively, whatever the order of the list", () => {
    const tree = albumTree(["holiday/2024", "Family", "holiday", "holiday/2023", "archive"].map(album));
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
    const tree = albumTree(["a b", "a", "a/x", "summer holiday/day 1"].map(album));
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
    expect(albumOptions(["holiday", "holiday/2024", "holiday/2024/may"].map(album))).toEqual([
      { value: "holiday", label: "holiday" },
      { value: "holiday/2024", label: "\u00a0\u00a0holiday / 2024" },
      { value: "holiday/2024/may", label: "\u00a0\u00a0\u00a0\u00a0holiday / 2024 / may" },
    ]);
    expect(displayPath("summer holiday/day 1")).toBe("summer holiday / day 1");
  });
});
