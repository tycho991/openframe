/** Where the browser remembers the open headings of the photo overview, as a JSON array of paths. */
export const OPEN_ALBUMS_KEY = "openframe.photos.openAlbums";

/** At most this many paths are remembered; the oldest opened is dropped first. */
export const MAX_OPEN_ALBUMS = 1000;

/**
 * The open album headings of the photo overview (design decision 7 of album-groups-in-photos). Paths missing from
 * the album list are kept, so a source that is unreachable for a while keeps its open albums. Without storage the
 * state lives in memory only.
 */
export class OpenAlbums {
  /** Oldest opened first. */
  private list: string[];

  constructor(private readonly storage: () => Storage = () => localStorage) {
    this.list = this.load();
  }

  /** The open paths, oldest opened first. */
  get paths(): string[] {
    return [...this.list];
  }

  has(path: string): boolean {
    return this.list.includes(path);
  }

  open(path: string): void {
    if (this.has(path)) return;
    this.list.push(path);
    if (this.list.length > MAX_OPEN_ALBUMS) this.list.splice(0, this.list.length - MAX_OPEN_ALBUMS);
    this.save();
  }

  close(path: string): void {
    if (!this.has(path)) return;
    this.list = this.list.filter((p) => p !== path);
    this.save();
  }

  toggle(path: string): void {
    if (this.has(path)) this.close(path);
    else this.open(path);
  }

  /** Opens [path] and every album above it, the top level first. */
  openWithAncestors(path: string): void {
    const levels = path.split("/");
    for (let i = 1; i <= levels.length; i++) this.open(levels.slice(0, i).join("/"));
  }

  private load(): string[] {
    try {
      const raw = this.storage().getItem(OPEN_ALBUMS_KEY);
      const parsed: unknown = raw === null ? [] : JSON.parse(raw);
      if (!Array.isArray(parsed)) return [];
      const paths = [...new Set(parsed.filter((p): p is string => typeof p === "string"))];
      return paths.slice(-MAX_OPEN_ALBUMS);
    } catch {
      // No storage, or something else under the key: start folded.
      return [];
    }
  }

  private save(): void {
    try {
      this.storage().setItem(OPEN_ALBUMS_KEY, JSON.stringify(this.list));
    } catch {
      // Private mode or full storage: the state lives in memory until the page is left.
    }
  }
}
