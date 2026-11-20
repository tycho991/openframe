import type { Api, Photo, PhotoPage, PhotoQuery } from "./api/client";

interface Page {
  cursor: string | null;
  items: Photo[];
  next: string | null;
  loadedAt: number;
  /** Whether a reload has already happened after an image error. */
  retried: boolean;
}

/** The first page of a grid, the request without a cursor: small, so that many open albums cost little. */
export const FIRST_PAGE_LIMIT = 60;
/** Every later page. */
export const PAGE_LIMIT = 200;
/** Photo list requests that run at a time for the whole overview. */
export const MAX_PHOTO_REQUESTS = 2;

/** Signed image URLs are valid for one to two hours. Pages are reloaded after this time at the latest. */
export const MAX_PAGE_AGE_MS = 50 * 60 * 1000;

/** Where the grid of a [PhotoPages] is, for the order of the queue. */
export interface RequestOwner {
  /** Whether the grid intersects the viewport now. */
  onScreen(): boolean;
  /** The grid's place on the page; lower comes first among grids that are equally on screen. */
  position(): number;
}

interface Job {
  owner: RequestOwner;
  wanted: () => boolean;
  run: () => Promise<PhotoPage>;
  seq: number;
  resolve: (page: PhotoPage | null) => void;
  reject: (error: unknown) => void;
}

/**
 * The photo list requests of the whole overview, at most [MAX_PHOTO_REQUESTS] at a time (design decision 4 of
 * album-groups-in-photos). It holds single page requests. Which one runs next is decided when a slot frees: grids
 * on screen first, then the rest in page order. A request that is no longer wanted is dropped and answers `null`.
 */
export class PhotoRequestQueue {
  private waiting: Job[] = [];
  private running = 0;
  private seq = 0;
  private scheduled = false;

  constructor(readonly maxInFlight = MAX_PHOTO_REQUESTS) {}

  /** Requests running now. */
  get inFlight(): number {
    return this.running;
  }

  /** Requests waiting for a slot. */
  get queued(): number {
    return this.waiting.length;
  }

  /** Runs [run] when a slot is free and [wanted] still holds; `null` if it was dropped before. */
  request(
    owner: RequestOwner,
    wanted: () => boolean,
    run: () => Promise<PhotoPage>,
  ): Promise<PhotoPage | null> {
    return new Promise((resolve, reject) => {
      this.waiting.push({ owner, wanted, run, seq: this.seq++, resolve, reject });
      this.schedule();
    });
  }

  /** Drops the requests that are no longer wanted, for example after a fold. */
  prune(): void {
    this.schedule();
  }

  // In a microtask, so that the requests queued together (a reload of every open album) are ordered together.
  private schedule(): void {
    if (this.scheduled) return;
    this.scheduled = true;
    queueMicrotask(() => {
      this.scheduled = false;
      this.pump();
    });
  }

  private pump(): void {
    this.waiting = this.waiting.filter((job) => {
      if (job.wanted()) return true;
      job.resolve(null);
      return false;
    });
    while (this.running < this.maxInFlight && this.waiting.length > 0) {
      const job = this.next();
      this.running++;
      let run: Promise<PhotoPage>;
      try {
        run = job.run();
      } catch (e) {
        run = Promise.reject(e);
      }
      run.then(job.resolve, job.reject).finally(() => {
        this.running--;
        this.schedule();
      });
    }
  }

  private next(): Job {
    const keyed = this.waiting.map((job) => ({
      job,
      screen: job.owner.onScreen() ? 0 : 1,
      position: job.owner.position(),
    }));
    keyed.sort((a, b) => a.screen - b.screen || a.position - b.position || a.job.seq - b.job.seq);
    const job = keyed[0]!.job;
    this.waiting.splice(this.waiting.indexOf(job), 1);
    return job;
  }
}

export interface PhotoPagesOptions {
  /** Signed image URLs are valid for one to two hours. Pages are reloaded after this time at the latest. */
  maxAgeMs?: number;
  now?: () => number;
  /** Shared by every grid of the overview; its own queue otherwise. */
  queue?: PhotoRequestQueue;
  /** Whether the grid intersects the viewport now, for the queue's order. */
  onScreen?: () => boolean;
  /** The grid's place on the page, for the queue's order. */
  position?: () => number;
}

/**
 * The loaded pages of the photo list for one grid. Loads page by page through
 * the cursor and keeps the signed image URLs fresh. Every request goes through
 * the queue; answers for an older generation (query changed, folded, reloaded)
 * are discarded and not followed by the next page of their chain.
 */
export class PhotoPages implements RequestOwner {
  private pages: Page[] = [];
  private listeners = new Set<() => void>();
  private loadingMore: Promise<void> | null = null;
  private generation = 0;
  private pending = 0;
  private closed = false;
  private readonly maxAgeMs: number;
  private readonly now: () => number;
  private readonly queue: PhotoRequestQueue;
  private readonly screen: () => boolean;
  private readonly place: () => number;
  total = 0;
  /** The last request failed: the grid does not ask again until the next reload or stale-URL refresh. */
  stalled = false;

  constructor(
    private readonly api: Pick<Api, "photos">,
    private query: Omit<PhotoQuery, "cursor" | "limit">,
    options: PhotoPagesOptions = {},
  ) {
    this.maxAgeMs = options.maxAgeMs ?? MAX_PAGE_AGE_MS;
    this.now = options.now ?? Date.now;
    this.queue = options.queue ?? new PhotoRequestQueue();
    this.screen = options.onScreen ?? (() => true);
    this.place = options.position ?? (() => 0);
  }

  get items(): Photo[] {
    return this.pages.flatMap((p) => p.items);
  }

  get hasMore(): boolean {
    const last = this.pages.at(-1);
    return last === undefined || last.next !== null;
  }

  /** Whether the first page has arrived. */
  get loaded(): boolean {
    return this.pages.length > 0;
  }

  /** Whether a page of the current generation is on its way. */
  get loading(): boolean {
    return this.pending > 0;
  }

  onScreen(): boolean {
    return this.screen();
  }

  position(): number {
    return this.place();
  }

  subscribe(listener: () => void): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  /** New filter or new sort order: back to no page; the grid asks for the first one when it is near the screen. */
  setQuery(query: Omit<PhotoQuery, "cursor" | "limit">): void {
    this.query = query;
    this.restart();
    this.pages = [];
    this.total = 0;
    this.emit();
  }

  /** The grid was folded: its queued requests are dropped and late answers discarded. */
  close(): void {
    this.closed = true;
    this.restart();
    this.listeners.clear();
    this.queue.prune();
  }

  /**
   * Reload all pages loaded so far, for example when the library version has changed. One page after another,
   * since each needs the cursor of the one before; the old pages stay until the new ones are complete.
   */
  async reload(): Promise<void> {
    const count = this.pages.length;
    this.restart();
    const gen = this.generation;
    if (count === 0) {
      // Not loaded yet: a first page on its way was for the old library, so the grid asks again.
      this.emit();
      return;
    }
    await this.track(gen, async () => {
      const fresh: Page[] = [];
      let cursor: string | null = null;
      for (let i = 0; i < count; i++) {
        const page = await this.fetchPage(cursor, gen);
        if (!page) return;
        fresh.push(page);
        if (page.next === null) break;
        cursor = page.next;
      }
      this.pages = fresh;
    });
  }

  /** Append the next page (the first one too), if there is one. Concurrent calls load only once. */
  loadMore(): Promise<void> {
    if (this.loadingMore) return this.loadingMore;
    if (this.closed || !this.hasMore) return Promise.resolve();
    const gen = this.generation;
    const cursor = this.pages.at(-1)?.next ?? null;
    const loading: Promise<void> = this.track(gen, async () => {
      try {
        const page = await this.fetchPage(cursor, gen);
        if (page) this.pages.push(page);
      } finally {
        // Before the listeners hear of the end, so that a grid that asks again at once gets a new request.
        if (this.loadingMore === loading) this.loadingMore = null;
      }
    });
    this.loadingMore = loading;
    return loading;
  }

  /** Reload pages whose URLs are about to expire. Call regularly, for example every minute. */
  async refreshStale(): Promise<void> {
    if (this.stalled) {
      // A page that failed is asked for again at this pace, not in a loop.
      this.stalled = false;
      this.emit();
    }
    const gen = this.generation;
    const now = this.now();
    let changed = false;
    try {
      for (let i = 0; i < this.pages.length; i++) {
        const page = this.pages[i]!;
        if (now - page.loadedAt < this.maxAgeMs) continue;
        const fresh = await this.fetchPage(page.cursor, gen);
        if (!fresh) return;
        this.pages[i] = fresh;
        changed = true;
      }
    } finally {
      if (changed && this.current(gen)) this.emit();
    }
  }

  /** An image could not be loaded, presumably because of an expired signature: reload its page once. */
  async onImageError(photoId: string): Promise<void> {
    const gen = this.generation;
    const page = this.pages.find((p) => p.items.some((it) => it.id === photoId));
    if (!page || page.retried) return;
    // Marked at once, so that the other tiles of the page that fail meanwhile do not ask again.
    page.retried = true;
    const fresh = await this.fetchPage(page.cursor, gen);
    const i = this.pages.indexOf(page);
    if (!fresh || i < 0) return;
    fresh.retried = true;
    this.pages[i] = fresh;
    this.emit();
  }

  /** Replace a photo locally, for example after hiding, without reloading. */
  replace(photo: Photo): void {
    let found = false;
    for (const page of this.pages) {
      const j = page.items.findIndex((p) => p.id === photo.id);
      if (j >= 0) {
        page.items[j] = photo;
        found = true;
      }
    }
    if (found) this.emit();
  }

  private current(gen: number): boolean {
    return !this.closed && gen === this.generation;
  }

  /** A new generation: what is queued or on its way for the old one is dropped or discarded. */
  private restart(): void {
    this.generation++;
    this.pending = 0;
    this.loadingMore = null;
    this.stalled = false;
  }

  /** Runs [fn] as loading for generation [gen]; the listeners hear when it starts and when it ends. */
  private async track(gen: number, fn: () => Promise<void>): Promise<void> {
    this.pending++;
    this.emit();
    let failed = false;
    try {
      await fn();
    } catch (e) {
      failed = true;
      throw e;
    } finally {
      // After a new generation, `pending` belongs to it.
      if (this.current(gen)) {
        this.pending--;
        if (failed) this.stalled = true;
        this.emit();
      }
    }
  }

  /** One page through the queue, with the limit of its position: no cursor means the first page. */
  private async fetchPage(cursor: string | null, gen: number): Promise<Page | null> {
    const query = { ...this.query, cursor, limit: cursor === null ? FIRST_PAGE_LIMIT : PAGE_LIMIT };
    const res = await this.queue.request(
      this,
      () => this.current(gen),
      () => this.api.photos(query),
    );
    if (res === null || !this.current(gen)) return null;
    this.total = res.total;
    return { cursor, items: res.items, next: res.nextCursor, loadedAt: this.now(), retried: false };
  }

  private emit(): void {
    for (const l of this.listeners) l();
  }
}
