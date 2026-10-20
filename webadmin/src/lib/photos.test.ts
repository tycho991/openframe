import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { Photo, PhotoPage, PhotoQuery } from "./api/client";
import { MAX_PAGE_AGE_MS, PhotoPages } from "./photos";

/** Fake API with 25 photos. The signature in the URLs is the hour in which the page was loaded. */
function fakeApi(now: () => number, total = 25) {
  const all = Array.from({ length: total }, (_, i) => `local-${String(i).padStart(40, "0")}`);
  const photos = vi.fn(async (q: PhotoQuery = {}): Promise<PhotoPage> => {
    const start = q.cursor ? all.indexOf(q.cursor) + 1 : 0;
    const ids = all.slice(start, start + (q.limit ?? 10));
    const hour = Math.floor(now() / 3_600_000);
    const items = ids.map(
      (id) =>
        ({
          id,
          urls: { thumb: `/t/${id}?t=${hour}`, display: `/d/${id}?t=${hour}` },
        }) as unknown as Photo,
    );
    const last = ids.at(-1);
    return { items, total, nextCursor: last && start + ids.length < total ? last : null };
  });
  return { photos };
}

describe("PhotoPages", () => {
  beforeEach(() => vi.useFakeTimers({ now: new Date("2026-05-01T10:15:00Z") }));
  afterEach(() => vi.useRealTimers());

  it("loads page by page through the cursor", async () => {
    const api = fakeApi(Date.now);
    const pages = new PhotoPages(api, {}, { pageSize: 10 });
    await pages.loadMore();
    await pages.loadMore();
    await pages.loadMore();
    expect(pages.items).toHaveLength(25);
    expect(pages.hasMore).toBe(false);
    await pages.loadMore();
    expect(api.photos).toHaveBeenCalledTimes(3);
  });

  it("Tab open for hours: URLs stay fresh", async () => {
    const api = fakeApi(Date.now);
    const pages = new PhotoPages(api, {}, { pageSize: 10 });
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
    expect(pages.items).toHaveLength(20);
    expect(api.photos.mock.calls.length).toBeGreaterThanOrEqual(
      2 + 2 * Math.floor((180 * 60_000) / MAX_PAGE_AGE_MS),
    );
  });

  it("reloads the page once after an image error", async () => {
    const api = fakeApi(Date.now);
    const pages = new PhotoPages(api, {}, { pageSize: 10 });
    await pages.loadMore();
    await pages.loadMore();
    const id = pages.items[12]!.id;
    await pages.onImageError(id);
    await pages.onImageError(id);
    expect(api.photos).toHaveBeenCalledTimes(3);
  });

  it("reloads all loaded pages when the library changed", async () => {
    const api = fakeApi(Date.now);
    const pages = new PhotoPages(api, { album: "holiday" }, { pageSize: 10 });
    await pages.loadMore();
    await pages.loadMore();
    const listener = vi.fn();
    pages.subscribe(listener);
    await pages.reload();
    expect(pages.items).toHaveLength(20);
    expect(listener).toHaveBeenCalledOnce();
    expect(api.photos.mock.calls.at(-1)![0]).toMatchObject({ album: "holiday" });
  });
});
