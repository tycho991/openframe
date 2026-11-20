import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { Photo, PhotoPage, PhotoQuery } from "./api/client";
import {
  FIRST_PAGE_LIMIT,
  MAX_PAGE_AGE_MS,
  PAGE_LIMIT,
  PHOTO_REQUEST_DEADLINE_MS,
  PhotoPages,
  PhotoRequestQueue,
} from "./photos";

function ids(prefix: string, total: number): string[] {
  return Array.from({ length: total }, (_, i) => `${prefix}-${String(i).padStart(40, "0")}`);
}

/** One page of [all] for [q]. The signature in the URLs is the hour in which the page was loaded. */
function pageOf(all: string[], q: PhotoQuery, now: number): PhotoPage {
  const start = q.cursor ? all.indexOf(q.cursor) + 1 : 0;
  const slice = all.slice(start, start + (q.limit ?? 10));
  const hour = Math.floor(now / 3_600_000);
  const items = slice.map(
    (id) =>
      ({
        id,
        urls: { thumb: `/t/${id}?t=${hour}`, display: `/d/${id}?t=${hour}` },
      }) as unknown as Photo,
  );
  const last = slice.at(-1);
  return { items, total: all.length, nextCursor: last && start + slice.length < all.length ? last : null };
}

/** Fake API that answers at once, with [total] photos. */
function fakeApi(now: () => number, total = 300) {
  const all = ids("local", total);
  const photos = vi.fn(async (q: PhotoQuery = {}): Promise<PhotoPage> => pageOf(all, q, now()));
  return { photos };
}

/**
 * Fake API whose answers wait until the test sends them; it counts the requests in flight. The photos of a request
 * depend on its album, so that answers for different queries can be told apart.
 */
function heldApi(total = 300) {
  const held: { query: PhotoQuery; answer: () => void }[] = [];
  let inFlight = 0;
  let maxInFlight = 0;
  const photos = vi.fn(
    (q: PhotoQuery = {}): Promise<PhotoPage> =>
      new Promise((resolve) => {
        inFlight++;
        maxInFlight = Math.max(maxInFlight, inFlight);
        held.push({
          query: q,
          answer: () => {
            inFlight--;
            resolve(pageOf(ids(q.album ?? "none", total), q, Date.now()));
          },
        });
      }),
  );
  /** Sends the answer to the request at [index] of those still held, then lets the promises settle. */
  async function answer(index = 0) {
    const [h] = held.splice(index, 1);
    h!.answer();
    await settle();
  }
  return {
    photos,
    held,
    answer,
    get inFlight() {
      return inFlight;
    },
    get maxInFlight() {
      return maxInFlight;
    },
  };
}

/** Lets every pending promise and the queue's microtasks run. */
async function settle() {
  for (let i = 0; i < 20; i++) await Promise.resolve();
}

describe("PhotoPages", () => {
  beforeEach(() => vi.useFakeTimers({ now: new Date("2026-05-01T10:15:00Z") }));
  afterEach(() => vi.useRealTimers());

  it("loads page by page through the cursor, the first page with 60 photos and every other with 200", async () => {
    const api = fakeApi(Date.now);
    const pages = new PhotoPages(api, {});
    await pages.loadMore();
    expect(pages.items).toHaveLength(FIRST_PAGE_LIMIT);
    await pages.loadMore();
    await pages.loadMore();
    expect(pages.items).toHaveLength(300);
    expect(pages.hasMore).toBe(false);
    await pages.loadMore();
    expect(api.photos.mock.calls.map(([q]) => q!.limit)).toEqual([FIRST_PAGE_LIMIT, PAGE_LIMIT, PAGE_LIMIT]);
  });

  it("Tab open for hours: URLs stay fresh", async () => {
    const api = fakeApi(Date.now);
    const pages = new PhotoPages(api, {});
    await pages.loadMore();
    await pages.loadMore();
    const refresh = setInterval(() => void pages.refreshStale(), 60_000);
    for (let minute = 0; minute < 180; minute++) {
      await vi.advanceTimersByTimeAsync(60_000);
      // No loaded URL is older than 50 minutes plus the check every minute.
      const hourNow = Math.floor(Date.now() / 3_600_000);
      for (const p of pages.items) {
        const signedHour = Number(p.urls.thumb.split("t=")[1]);
        expect(hourNow - signedHour).toBeLessThanOrEqual(1);
      }
    }
    clearInterval(refresh);
    expect(pages.items).toHaveLength(260);
    expect(api.photos.mock.calls.length).toBeGreaterThanOrEqual(
      2 + 2 * Math.floor((180 * 60_000) / MAX_PAGE_AGE_MS),
    );
  });

  it("refreshes a stale page with the limit of its position", async () => {
    const api = fakeApi(Date.now);
    const pages = new PhotoPages(api, {});
    await pages.loadMore();
    await pages.loadMore();
    api.photos.mockClear();
    vi.setSystemTime(Date.now() + MAX_PAGE_AGE_MS);
    await pages.refreshStale();
    expect(api.photos.mock.calls.map(([q]) => [q!.cursor ?? null, q!.limit])).toEqual([
      [null, FIRST_PAGE_LIMIT],
      [pages.items[FIRST_PAGE_LIMIT - 1]!.id, PAGE_LIMIT],
    ]);
    expect(pages.items).toHaveLength(260);
  });

  it("reloads the page once after an image error, with the limit of its position", async () => {
    const api = fakeApi(Date.now);
    const pages = new PhotoPages(api, {});
    await pages.loadMore();
    await pages.loadMore();
    api.photos.mockClear();
    const later = pages.items[100]!.id;
    await pages.onImageError(later);
    await pages.onImageError(later);
    const first = pages.items[10]!.id;
    await pages.onImageError(first);
    await pages.onImageError(first);
    expect(api.photos.mock.calls.map(([q]) => q!.limit)).toEqual([PAGE_LIMIT, FIRST_PAGE_LIMIT]);
    expect(pages.items).toHaveLength(260);
  });

  it("reloads all loaded pages when the library changed, one page after another", async () => {
    const api = heldApi();
    const pages = new PhotoPages(api, { album: "holiday" });
    void pages.loadMore();
    await settle();
    await api.answer();
    void pages.loadMore();
    await settle();
    await api.answer();
    const listener = vi.fn();
    pages.subscribe(listener);
    const reload = pages.reload();
    await settle();
    // The second page needs the cursor of the first, so it is queued only after the first answer.
    expect(api.held.map((h) => [h.query.cursor ?? null, h.query.limit])).toEqual([[null, FIRST_PAGE_LIMIT]]);
    expect(pages.loading).toBe(true);
    await api.answer();
    expect(api.held.map((h) => h.query.limit)).toEqual([PAGE_LIMIT]);
    // The old pages stay until the new ones are complete.
    expect(pages.items).toHaveLength(260);
    await api.answer();
    await reload;
    expect(pages.items).toHaveLength(260);
    expect(pages.loading).toBe(false);
    expect(api.maxInFlight).toBe(1);
    expect(api.photos.mock.calls.at(-1)![0]).toMatchObject({ album: "holiday" });
    expect(listener).toHaveBeenCalled();
  });

  it("two quick setQuery() calls whose first answer arrives last leave the pages of the second query", async () => {
    const api = heldApi();
    const pages = new PhotoPages(api, { album: "first" });
    void pages.loadMore();
    await settle();
    pages.setQuery({ album: "second" });
    void pages.loadMore();
    await settle();
    expect(api.held.map((h) => h.query.album)).toEqual(["first", "second"]);
    await api.answer(1);
    await api.answer(0);
    expect(pages.items).toHaveLength(FIRST_PAGE_LIMIT);
    expect(pages.items.every((p) => p.id.startsWith("second-"))).toBe(true);
    // The late answer is not followed by the next page of its chain either.
    expect(api.photos).toHaveBeenCalledTimes(2);
  });

  it("a reload discards the answer of a page that was already on its way", async () => {
    const api = heldApi();
    const pages = new PhotoPages(api, { album: "a" });
    void pages.loadMore();
    await settle();
    await api.answer();
    void pages.loadMore();
    await settle();
    void pages.reload();
    await settle();
    expect(api.held.map((h) => h.query.limit)).toEqual([PAGE_LIMIT, FIRST_PAGE_LIMIT]);
    await api.answer(0);
    expect(pages.items).toHaveLength(FIRST_PAGE_LIMIT);
    await api.answer(0);
    expect(pages.items).toHaveLength(FIRST_PAGE_LIMIT);
    expect(pages.loading).toBe(false);
  });
});

describe("PhotoPages while pages change", () => {
  beforeEach(() => vi.useFakeTimers({ now: new Date("2026-05-01T10:15:00Z") }));
  afterEach(() => vi.useRealTimers());

  /** A fake API over a list that the test can change, whose answers wait until the test sends them. */
  function changingApi(initial: string[]) {
    let all = initial;
    const held: { query: PhotoQuery; answer: () => void }[] = [];
    const photos = vi.fn(
      (q: PhotoQuery = {}): Promise<PhotoPage> =>
        new Promise((resolve) => held.push({ query: q, answer: () => resolve(pageOf(all, q, Date.now())) })),
    );
    return {
      photos,
      held,
      set: (list: string[]) => (all = list),
      /** Answers the held request for [cursor] (the first page for `null`). */
      async answer(cursor: string | null) {
        const i = held.findIndex((h) => (h.query.cursor ?? null) === cursor);
        expect(i, `a request for ${cursor}`).toBeGreaterThanOrEqual(0);
        held.splice(i, 1)[0]!.answer();
        await settle();
      },
    };
  }

  it("a stale-URL refresh during a reload leaves the reloaded pages as they came", async () => {
    const old = ids("old", 300);
    const api = changingApi(old);
    const pages = new PhotoPages(api, {});
    void pages.loadMore();
    await settle();
    await api.answer(null);
    void pages.loadMore();
    await settle();
    await api.answer(old[59]!);
    expect(pages.items).toHaveLength(260);

    // Five new photos at the start, and the URLs are stale: a reload and a refresh run at the same time.
    const now = [...ids("new", 5), ...old];
    api.set(now);
    vi.setSystemTime(Date.now() + MAX_PAGE_AGE_MS);
    const reload = pages.reload();
    const refresh = pages.refreshStale();
    await settle();
    expect(api.held.map((h) => h.query.cursor ?? null)).toEqual([null, null]);
    // The reload's first page, then the refresh's, which asks next for the second page with the old cursor.
    await api.answer(null);
    await api.answer(null);
    expect(api.held.map((h) => h.query.cursor ?? null).sort()).toEqual([now[59], old[59]].sort());
    // The reload completes first; the refresh's late page with the old cursor must not go into the new pages.
    await api.answer(now[59]!);
    await reload;
    await api.answer(old[59]!);
    await refresh;
    expect(pages.items.map((p) => p.id)).toEqual(now.slice(0, 260));
  });

  it("overlapping stale-URL refreshes ask for each stale page once", async () => {
    const api = changingApi(ids("a", 300));
    const pages = new PhotoPages(api, {});
    void pages.loadMore();
    await settle();
    await api.answer(null);
    void pages.loadMore();
    await settle();
    await api.answer(pages.items[59]!.id);
    api.photos.mockClear();
    vi.setSystemTime(Date.now() + MAX_PAGE_AGE_MS);
    const first = pages.refreshStale();
    const second = pages.refreshStale();
    await settle();
    await api.answer(null);
    await api.answer(pages.items[59]!.id);
    await Promise.all([first, second]);
    expect(api.photos).toHaveBeenCalledTimes(2);
    // Once that refresh has ended, the next one runs again (nothing is stale now, so it asks for nothing).
    await pages.refreshStale();
    expect(api.photos).toHaveBeenCalledTimes(2);
  });
});

describe("PhotoRequestQueue", () => {
  /** A grid with its own pages in the shared queue, on screen or not. */
  function grid(api: ReturnType<typeof heldApi>, queue: PhotoRequestQueue, album: string, place: number) {
    const state = { onScreen: false };
    const pages = new PhotoPages(
      api,
      { album, subalbums: false },
      {
        queue,
        onScreen: () => state.onScreen,
        position: () => place,
      },
    );
    return { pages, state };
  }

  it("Reload with many open albums: at most two photo list requests at a time", async () => {
    const api = heldApi(100);
    const queue = new PhotoRequestQueue();
    const grids = Array.from({ length: 20 }, (_, i) => grid(api, queue, `album ${i}`, i));
    for (const g of grids) void g.pages.loadMore();
    await settle();
    expect(api.inFlight).toBe(2);
    expect(queue.queued).toBe(18);
    while (api.held.length > 0) await api.answer();
    expect(api.maxInFlight).toBe(2);
    expect(api.photos).toHaveBeenCalledTimes(20);
    expect(api.photos.mock.calls.every(([q]) => q!.limit === FIRST_PAGE_LIMIT)).toBe(true);
    expect(grids.every((g) => g.pages.items.length === FIRST_PAGE_LIMIT)).toBe(true);
  });

  it("takes the grids on screen first, decided when a slot frees, then the rest in page order", async () => {
    const api = heldApi();
    const queue = new PhotoRequestQueue();
    const [a, b, c, d, e] = ["a", "b", "c", "d", "e"].map((name, i) => grid(api, queue, name, i));
    void a!.pages.loadMore();
    void b!.pages.loadMore();
    await settle();
    // Both slots are busy; c, d and e wait, e is on screen.
    for (const g of [d!, c!, e!]) void g.pages.loadMore();
    e!.state.onScreen = true;
    await settle();
    // d is scrolled into view after it was queued: it overtakes c, and e, which lies below it.
    d!.state.onScreen = true;
    await api.answer();
    expect(api.held.map((h) => h.query.album)).toEqual(["b", "d"]);
    await api.answer();
    expect(api.held.map((h) => h.query.album)).toEqual(["d", "e"]);
    await api.answer();
    expect(api.held.map((h) => h.query.album)).toEqual(["e", "c"]);
  });

  it("drops the queued requests of a folded grid and of an outdated query", async () => {
    const api = heldApi();
    const queue = new PhotoRequestQueue();
    const [a, b, folded, changed] = ["a", "b", "folded", "changed"].map((name, i) =>
      grid(api, queue, name, i),
    );
    void a!.pages.loadMore();
    void b!.pages.loadMore();
    void folded!.pages.loadMore();
    void changed!.pages.loadMore();
    await settle();
    expect(queue.queued).toBe(2);
    folded!.pages.close();
    changed!.pages.setQuery({ album: "changed", subalbums: false, visible: false });
    await settle();
    expect(queue.queued).toBe(0);
    await api.answer();
    await api.answer();
    expect(api.photos.mock.calls.map(([q]) => q!.album)).toEqual(["a", "b"]);
    // The grid with the new query asks again for its first page, which now goes through.
    void changed!.pages.loadMore();
    await settle();
    expect(api.held.map((h) => [h.query.album, h.query.visible])).toEqual([["changed", false]]);
  });

  it("discards the answer for a grid folded while its request was in flight", async () => {
    const api = heldApi();
    const queue = new PhotoRequestQueue();
    const g = grid(api, queue, "a", 0);
    const listener = vi.fn();
    g.pages.subscribe(listener);
    void g.pages.loadMore();
    await settle();
    listener.mockClear();
    g.pages.close();
    await api.answer();
    expect(g.pages.items).toEqual([]);
    expect(listener).not.toHaveBeenCalled();
  });

  it("stops after a failed page until the next stale-URL refresh", async () => {
    let fail = true;
    const all = ids("a", 300);
    const photos = vi.fn(async (q: PhotoQuery = {}) => {
      if (fail) throw new Error("frame unreachable");
      return pageOf(all, q, Date.now());
    });
    const pages = new PhotoPages({ photos }, {});
    await expect(pages.loadMore()).rejects.toThrow("frame unreachable");
    expect(pages.stalled).toBe(true);
    expect(pages.loading).toBe(false);
    fail = false;
    await pages.refreshStale();
    expect(pages.stalled).toBe(false);
    await pages.loadMore();
    expect(pages.items).toHaveLength(FIRST_PAGE_LIMIT);
  });
});

describe("PhotoRequestQueue deadline and close", () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  /** A frame that never answers; it records the query and the signal of every request. */
  function silentApi() {
    const signals: AbortSignal[] = [];
    const queries: PhotoQuery[] = [];
    const photos = vi.fn((q: PhotoQuery = {}, signal?: AbortSignal) => {
      queries.push(q);
      signals.push(signal!);
      return new Promise<PhotoPage>(() => {});
    });
    return { photos, signals, queries };
  }

  it("a request without an answer is aborted after the deadline, frees its slot and fails", async () => {
    const api = silentApi();
    const queue = new PhotoRequestQueue();
    const [a, b, c] = ["a", "b", "c"].map((album) => new PhotoPages(api, { album }, { queue }));
    const loads = [a!, b!].map((p) => p.loadMore().catch((e: unknown) => e));
    void c!.loadMore().catch(() => {});
    await settle();
    expect(queue.inFlight).toBe(2);
    expect(api.photos).toHaveBeenCalledTimes(2);

    await vi.advanceTimersByTimeAsync(PHOTO_REQUEST_DEADLINE_MS - 1);
    expect(api.photos).toHaveBeenCalledTimes(2);
    await vi.advanceTimersByTimeAsync(1);
    await settle();
    expect(api.signals.slice(0, 2).every((s) => s.aborted)).toBe(true);
    expect(await loads[0]).toMatchObject({ code: "network" });
    // Counted as failed: the grid waits for the next refresh or reload.
    expect(a!.stalled).toBe(true);
    expect(a!.loading).toBe(false);
    // The freed slot takes the next request.
    expect(api.photos).toHaveBeenCalledTimes(3);
    expect(api.queries[2]).toMatchObject({ album: "c" });
    expect(queue.inFlight).toBe(1);
  });

  it("close() aborts the running requests and drops the queued ones", async () => {
    const api = silentApi();
    const queue = new PhotoRequestQueue();
    const grids = ["a", "b", "c"].map((album) => new PhotoPages(api, { album }, { queue }));
    for (const g of grids) void g.loadMore();
    await settle();
    expect(queue.inFlight).toBe(2);
    expect(queue.queued).toBe(1);
    for (const g of grids) g.close();
    queue.close();
    await settle();
    expect(api.signals.every((s) => s.aborted)).toBe(true);
    expect(queue.inFlight).toBe(0);
    expect(queue.queued).toBe(0);
    expect(api.photos).toHaveBeenCalledTimes(2);
    // Nothing fails: the grids are gone, not stalled.
    expect(grids.every((g) => !g.stalled)).toBe(true);
  });
});
