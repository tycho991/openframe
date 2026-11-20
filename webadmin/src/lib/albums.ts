import type { Album, AlbumCounts } from "./api/client";

/** An album in the tree. `album` is missing for a folder on the way that the list did not name. */
export interface AlbumNode {
  path: string;
  /** The last level of the path. */
  title: string;
  /** 0 at the top level. */
  depth: number;
  album: Album | null;
  children: AlbumNode[];
}

/** An entry of an album choice: the path as value, indented with its whole path as label. */
export interface AlbumOption {
  value: string;
  label: string;
}

const SEPARATOR = " / ";

/** `holiday/2024` → `holiday / 2024`, readable in a choice and in the detail view. */
export function displayPath(path: string): string {
  return path.split("/").join(SEPARATOR);
}

/** Case-insensitive, as the frame sorts its list, and stable for names that differ only in case. */
function byTitle(a: AlbumNode, b: AlbumNode): number {
  const la = a.title.toLowerCase();
  const lb = b.title.toLowerCase();
  if (la !== lb) return la < lb ? -1 : 1;
  return a.title < b.title ? -1 : a.title > b.title ? 1 : 0;
}

/** Builds the tree from the album paths, in any order; every level of a path becomes a node. */
export function albumTree(albums: Album[]): AlbumNode[] {
  const roots: AlbumNode[] = [];
  const nodes = new Map<string, AlbumNode>();
  function node(path: string): AlbumNode {
    const existing = nodes.get(path);
    if (existing) return existing;
    const cut = path.lastIndexOf("/");
    const created: AlbumNode = {
      path,
      title: path.slice(cut + 1),
      depth: cut < 0 ? 0 : path.split("/").length - 1,
      album: null,
      children: [],
    };
    nodes.set(path, created);
    (cut < 0 ? roots : node(path.slice(0, cut)).children).push(created);
    return created;
  }
  for (const album of albums) node(album.name).album = album;
  const sort = (list: AlbumNode[]) => {
    list.sort(byTitle);
    list.forEach((n) => sort(n.children));
  };
  sort(roots);
  return roots;
}

/** Every node of the tree, parents before their children. */
export function flatten(tree: AlbumNode[]): AlbumNode[] {
  return tree.flatMap((n) => [n, ...flatten(n.children)]);
}

/** Non-breaking, since a select collapses ordinary spaces at the start of an option. */
const INDENT = "\u00a0\u00a0";

/** The albums for a select: in tree order, each indented by its depth. */
export function albumOptions(albums: Album[]): AlbumOption[] {
  return flatten(albumTree(albums)).map((n) => ({
    value: n.path,
    label: INDENT.repeat(n.depth) + displayPath(n.path),
  }));
}

/** The visibility filter of the photo overview. */
export type Visibility = "all" | "visible" | "hidden";

/** The key of the heading for the photos without an album: no album path is empty (upload and scan drop empty levels). */
export const NO_ALBUM = "";

/** The photos of [counts] that match [filter]. */
export function countFor(counts: AlbumCounts, filter: Visibility): number {
  if (filter === "visible") return counts.visibleCount;
  if (filter === "hidden") return counts.count - counts.visibleCount;
  return counts.count;
}

/** A heading of the photo overview, with what is below it while it is open. */
export interface AlbumHeading {
  /** The album's path, or [NO_ALBUM] for the heading for photos without an album. */
  path: string;
  /** The last level of the path; empty for the heading without an album, whose text comes from the catalog. */
  title: string;
  /** 0 at the top level. */
  depth: number;
  /** Photos of the album and its sub-albums that match the filter. */
  count: number;
  /** Photos that lie directly in the album and match the filter. */
  ownCount: number;
  open: boolean;
  /** The album as the frame lists it, for its switch; `null` for the heading without an album. */
  album: Album | null;
  /** Whether a grid of the album's own photos comes right below the heading: open with an own count above 0. */
  grid: boolean;
  /** The headings of the sub-albums, after the grid; empty while folded. */
  children: AlbumHeading[];
}

export interface AlbumHeadingInput {
  albums: Album[];
  noAlbum: AlbumCounts;
  /** The open paths, [NO_ALBUM] for the heading without an album. Unknown paths are ignored. */
  open: ReadonlySet<string>;
  filter: Visibility;
  /** Switched headings that stay with their ancestors while a filter is set, also with a count of 0. */
  pinned?: ReadonlySet<string>;
}

/** The counts of a node; a folder on the way that the list did not name counts by its children. */
function nodeCounts(node: AlbumNode): AlbumCounts {
  if (node.album) return node.album;
  return node.children
    .map(nodeCounts)
    .reduce((sum, c) => ({ count: sum.count + c.count, visibleCount: sum.visibleCount + c.visibleCount }), {
      count: 0,
      visibleCount: 0,
    });
}

/** Whether [path] is [pinned] or one of its ancestors. */
function onPinnedPath(path: string, pinned: ReadonlySet<string>): boolean {
  for (const p of pinned) if (p === path || p.startsWith(path + "/")) return true;
  return false;
}

/**
 * The headings of the photo overview in the frame's album order, nested as the album tree, with the heading for
 * photos without an album last. With a filter set, an album without a matching photo in its branch is left out with
 * its branch, unless it is pinned or above a pinned album (design decision 6 of album-groups-in-photos).
 */
export function albumHeadings(input: AlbumHeadingInput): AlbumHeading[] {
  const { open, filter } = input;
  const pinned = input.pinned ?? new Set<string>();
  function headings(nodes: AlbumNode[]): AlbumHeading[] {
    const out: AlbumHeading[] = [];
    for (const node of nodes) {
      const count = countFor(nodeCounts(node), filter);
      if (count === 0 && filter !== "all" && !onPinnedPath(node.path, pinned)) continue;
      const ownCount = Math.max(
        0,
        count - node.children.reduce((sum, c) => sum + countFor(nodeCounts(c), filter), 0),
      );
      const isOpen = open.has(node.path);
      out.push({
        path: node.path,
        title: node.title,
        depth: node.depth,
        count,
        ownCount,
        open: isOpen,
        album: node.album,
        grid: isOpen && ownCount > 0,
        children: isOpen ? headings(node.children) : [],
      });
    }
    return out;
  }
  const result = headings(albumTree(input.albums));
  const noAlbumCount = countFor(input.noAlbum, filter);
  if (noAlbumCount > 0) {
    const isOpen = open.has(NO_ALBUM);
    result.push({
      path: NO_ALBUM,
      title: "",
      depth: 0,
      count: noAlbumCount,
      ownCount: noAlbumCount,
      open: isOpen,
      album: null,
      grid: isOpen,
      children: [],
    });
  }
  return result;
}

/** The photos of the library that match [filter]: the top-level albums and the photos without an album. */
export function libraryCount(albums: Album[], noAlbum: AlbumCounts, filter: Visibility): number {
  return (
    albumTree(albums).reduce((sum, n) => sum + countFor(nodeCounts(n), filter), 0) + countFor(noAlbum, filter)
  );
}

/** What the overview renders, top to bottom: each heading, then its grid, then its sub-albums. */
export function renderOrder(headings: AlbumHeading[]): { kind: "heading" | "grid"; path: string }[] {
  return headings.flatMap((h) => [
    { kind: "heading" as const, path: h.path },
    ...(h.grid ? [{ kind: "grid" as const, path: h.path }] : []),
    ...renderOrder(h.children),
  ]);
}

/** The headings whose grid is on the page, in render order. */
export function gridHeadings(headings: AlbumHeading[]): AlbumHeading[] {
  return headings.flatMap((h) => [...(h.grid ? [h] : []), ...gridHeadings(h.children)]);
}
