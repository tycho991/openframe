import type { Api, Photo, PhotoQuery } from "./api/client";

interface Page {
  cursor: string | null;
  items: Photo[];
  next: string | null;
  loadedAt: number;
  /** Whether a reload has already happened after an image error. */
  retried: boolean;
}

export interface PhotoPagesOptions {
  pageSize?: number;
  /** Signed image URLs are valid for one to two hours. Pages are reloaded after this time at the latest. */
  maxAgeMs?: number;
  now?: () => number;
}

export const MAX_PAGE_AGE_MS = 50 * 60 * 1000;

/**
 * The loaded pages of the photo list for the grid. Loads page by page through
 * the cursor and keeps the signed image URLs fresh.
 */
export class PhotoPages {
  private pages: Page[] = [];
  private listeners = new Set<() => void>();
  private loading: Promise<void> | null = null;
  private readonly pageSize: number;
  private readonly maxAgeMs: number;
  private readonly now: () => number;
  total = 0;

  constructor(
    private readonly api: Pick<Api, "photos">,
    private query: Omit<PhotoQuery, "cursor" | "limit">,
    options: PhotoPagesOptions = {},
  ) {
    this.pageSize = options.pageSize ?? 200;
    this.maxAgeMs = options.maxAgeMs ?? MAX_PAGE_AGE_MS;
    this.now = options.now ?? Date.now;
  }

  get items(): Photo[] {
    return this.pages.flatMap((p) => p.items);
  }

  get hasMore(): boolean {
    const last = this.pages.at(-1);
    return last === undefined || last.next !== null;
  }

  subscribe(listener: () => void): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  /** New filter or new sort order: load from the start. */
  async setQuery(query: Omit<PhotoQuery, "cursor" | "limit">): Promise<void> {
    this.query = query;
    await this.reload();
  }

  /** Reload all pages loaded so far, for example when the library version has changed. */
  async reload(): Promise<void> {
    const count = Math.max(1, this.pages.length);
    const fresh: Page[] = [];
    let cursor: string | null = null;
    for (let i = 0; i < count; i++) {
      const page = await this.fetchPage(cursor);
      fresh.push(page);
      if (page.next === null) break;
      cursor = page.next;
    }
    this.pages = fresh;
    this.emit();
  }

  /** Append the next page, if there is one. Concurrent calls load only once. */
  loadMore(): Promise<void> {
    if (this.loading) return this.loading;
    if (!this.hasMore) return Promise.resolve();
    this.loading = (async () => {
      try {
        const cursor = this.pages.at(-1)?.next ?? null;
        this.pages.push(await this.fetchPage(cursor));
        this.emit();
      } finally {
        this.loading = null;
      }
    })();
    return this.loading;
  }

  /** Reload pages whose URLs are about to expire. Call regularly, for example every minute. */
  async refreshStale(): Promise<void> {
    const now = this.now();
    let changed = false;
    for (let i = 0; i < this.pages.length; i++) {
      const page = this.pages[i]!;
      if (now - page.loadedAt < this.maxAgeMs) continue;
      this.pages[i] = await this.fetchPage(page.cursor);
      changed = true;
    }
    if (changed) this.emit();
  }

  /** An image could not be loaded, presumably because of an expired signature: reload its page once. */
  async onImageError(photoId: string): Promise<void> {
    const i = this.pages.findIndex((p) => p.items.some((it) => it.id === photoId));
    const page = this.pages[i];
    if (!page || page.retried) return;
    const fresh = await this.fetchPage(page.cursor);
    fresh.retried = true;
    this.pages[i] = fresh;
    this.emit();
  }

  /** Replace a photo locally, for example after hiding, without reloading. */
  replace(photo: Photo): void {
    for (const page of this.pages) {
      const j = page.items.findIndex((p) => p.id === photo.id);
      if (j >= 0) page.items[j] = photo;
    }
    this.emit();
  }

  private async fetchPage(cursor: string | null): Promise<Page> {
    const res = await this.api.photos({ ...this.query, cursor, limit: this.pageSize });
    this.total = res.total;
    return { cursor, items: res.items, next: res.nextCursor, loadedAt: this.now(), retried: false };
  }

  private emit(): void {
    for (const l of this.listeners) l();
  }
}
