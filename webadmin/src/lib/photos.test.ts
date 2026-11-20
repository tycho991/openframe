import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { Photo, PhotoPage, PhotoQuery } from "./api/client";
import { FIRST_PAGE_LIMIT, MAX_PAGE_AGE_MS, PAGE_LIMIT, PhotoPages, PhotoRequestQueue } from "./photos";

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
