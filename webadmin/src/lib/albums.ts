import type { Album } from "./api/client";

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
