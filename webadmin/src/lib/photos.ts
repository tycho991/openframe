import type { Api, Photo, PhotoQuery } from "./api/client";

interface Page {
  cursor: string | null;
  items: Photo[];
  next: string | null;
  loadedAt: number;
  /** Ob nach einem Bildfehler schon einmal nachgeladen wurde. */
  retried: boolean;
}

export interface PhotoPagesOptions {
  pageSize?: number;
  /** Signierte Bild-URLs gelten ein bis zwei Stunden. Seiten werden spätestens nach dieser Zeit neu geladen. */
  maxAgeMs?: number;
  now?: () => number;
}

export const MAX_PAGE_AGE_MS = 50 * 60 * 1000;

/**
 * Die geladenen Seiten der Fotoliste für das Raster. Lädt seitenweise per
 * Cursor nach und hält die signierten Bild-URLs frisch.
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

  /** Neuer Filter oder neue Sortierung: von vorn laden. */
  async setQuery(query: Omit<PhotoQuery, "cursor" | "limit">): Promise<void> {
    this.query = query;
    await this.reload();
  }

  /** Alle bisher geladenen Seiten neu laden, etwa wenn sich die Bibliotheksversion geändert hat. */
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

  /** Die nächste Seite anhängen, sofern es eine gibt. Gleichzeitige Aufrufe laden nur einmal. */
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

  /** Seiten neu laden, deren URLs bald ablaufen. Regelmäßig aufrufen, etwa jede Minute. */
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

  /** Ein Bild ließ sich nicht laden, vermutlich wegen abgelaufener Signatur: seine Seite einmal nachladen. */
  async onImageError(photoId: string): Promise<void> {
    const i = this.pages.findIndex((p) => p.items.some((it) => it.id === photoId));
    const page = this.pages[i];
    if (!page || page.retried) return;
    const fresh = await this.fetchPage(page.cursor);
    fresh.retried = true;
    this.pages[i] = fresh;
    this.emit();
  }

  /** Ein Foto lokal ersetzen, etwa nach dem Ausblenden, ohne neu zu laden. */
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
