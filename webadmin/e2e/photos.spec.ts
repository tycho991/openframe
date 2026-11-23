import { expect, test, type Page, type Request } from "@playwright/test";
import path from "node:path";
import {
  albumContent,
  albumHeading,
  albumTiles,
  apiAsBrowser,
  albumSwitch,
  FIXTURES,
  foldButton,
  frameSecret,
  inPhotoFolder,
  many,
  openAlbum,
  pairInUi,
  photoDetail,
  reset,
  usb,
} from "./helpers";

interface PhotoList {
  items: { id: string; visible: boolean; hidden: boolean; path: string }[];
  total: number;
}

test.beforeEach(async () => {
  await reset();
});

test("Large library: only visible thumbnails", async ({ page }) => {
  await many(10_000);
  const thumbs = new Set<string>();
  page.on("request", (r) => {
    if (r.url().includes("size=thumb")) thumbs.add(r.url());
  });
  await page.setViewportSize({ width: 1280, height: 800 });
  await pairInUi(page);
  await expect(page.locator(".count")).toHaveText("10000 Fotos");
  await openAlbum(page, "viele");
  // Depending on the browser, lazy loading starts only after the layout.
  await expect.poll(() => thumbs.size).toBeGreaterThan(0);
  await page.waitForLoadState("networkidle");
  expect(thumbs.size).toBeLessThan(150);
  // Jump far down: again only what is visible there.
  const before = thumbs.size;
  await page.evaluate(() => window.scrollTo(0, 60_000));
  await expect.poll(() => thumbs.size - before).toBeGreaterThan(0);
  await page.waitForLoadState("networkidle");
  expect(thumbs.size - before).toBeLessThan(150);
  expect(await page.locator(".tile").count()).toBeLessThan(150);
});

test("HEIC over USB on an old device", async ({ page }) => {
  await usb("photo.heic", "holiday/IMG_0001.heic");
  await usb("landscape.jpg", "holiday/ok.jpg");
  await pairInUi(page);
  await expect(page.getByText("1 Datei im Fotoordner konnte der Rahmen nicht aufnehmen.")).toBeVisible();
  await page.getByRole("button", { name: "Anzeigen" }).click();
  await expect(page.getByText("holiday/IMG_0001.heic – nicht unterstützt")).toBeVisible();
});

test("Hide several", async ({ page }) => {
  for (const n of [1, 2, 3, 4]) await usb("landscape.jpg", `p${n}.jpg`);
  await pairInUi(page);
  await openAlbum(page, "");
  await expect(page.locator(".tile")).toHaveCount(4);
  await page.getByRole("button", { name: "Auswählen" }).click();
  for (const i of [0, 1, 2]) await page.locator(".tile").nth(i).click();
  await page.getByRole("toolbar").getByRole("button", { name: "Ausblenden" }).click();
  await expect(page.locator(".tile .badge")).toHaveCount(3);
  const res = await apiAsBrowser<PhotoList>(page, "/photos");
  expect(res.items.filter((p) => !p.visible)).toHaveLength(3);
});

test("Confirm delete", async ({ page }) => {
  await usb("landscape.jpg", "weg.jpg");
  await usb("landscape.jpg", "bleibt.jpg");
  await pairInUi(page);
  await openAlbum(page, "");
  await page.locator(".tile").first().click();
  await page.getByRole("button", { name: "Löschen" }).click();
  // The confirmation names the trash, its 7 days and who sees it (web-admin › Edit photos › Confirm delete).
  await expect(
    page.getByText(
      "Dieses Foto in den Papierkorb verschieben? Es bleibt dort 7 Tage, wo jedes gekoppelte Gerät es sehen und wiederherstellen kann.",
    ),
  ).toBeVisible();
  // Cancel deletes nothing.
  await page.getByRole("button", { name: "Abbrechen" }).click();
  expect((await apiAsBrowser<PhotoList>(page, "/photos")).total).toBe(2);
  await page.getByRole("button", { name: "Löschen" }).click();
  await page.getByRole("dialog").last().getByRole("button", { name: "Löschen" }).click();
  await expect(page.locator(".count")).toHaveText("1 Foto");
});

/** The bodies of the batch requests the page sends, and the URLs of single DELETE requests, of which there are none. */
function deleteRequests(page: Page) {
  const batches: { action: string; ids: string[] }[] = [];
  const singles: string[] = [];
  page.on("request", (r: Request) => {
    if (r.method() === "DELETE" && r.url().includes("/api/v1/photos/")) singles.push(r.url());
    if (r.method() === "POST" && r.url().endsWith("/api/v1/photos:batch")) batches.push(r.postDataJSON());
  });
  return { batches, singles };
}

test("Delete a single photo through the batch endpoint", async ({ page }) => {
  await usb("landscape.jpg", "weg.jpg");
  await usb("landscape.jpg", "bleibt.jpg");
  await pairInUi(page);
  await openAlbum(page, "");
  const before = await apiAsBrowser<PhotoList>(page, "/photos");
  const { batches, singles } = deleteRequests(page);
  await page.locator(".tile").first().click();
  await page.getByRole("button", { name: "Löschen" }).click();
  await page.getByRole("dialog").last().getByRole("button", { name: "Löschen" }).click();
  await expect(page.locator(".count")).toHaveText("1 Foto");
  const after = await apiAsBrowser<PhotoList>(page, "/photos");
  const gone = before.items.find((p) => !after.items.some((q) => q.id === p.id))!;
  expect(batches).toEqual([{ action: "delete", ids: [gone.id] }]);
  expect(singles).toEqual([]);
  expect(await inPhotoFolder(gone.path)).toBe(false);
  expect(await inPhotoFolder(after.items[0].path)).toBe(true);
});

test("Delete a selection through the batch endpoint", async ({ page }) => {
  for (const n of [1, 2, 3, 4]) await usb("landscape.jpg", `p${n}.jpg`);
  await pairInUi(page);
  await openAlbum(page, "");
  await expect(page.locator(".tile")).toHaveCount(4);
  const { batches, singles } = deleteRequests(page);
  await page.getByRole("button", { name: "Auswählen" }).click();
  for (const i of [0, 1, 2]) await page.locator(".tile").nth(i).click();
  await page.getByRole("toolbar").getByRole("button", { name: "Löschen" }).click();
  await expect(page.getByText("3 Fotos in den Papierkorb verschieben?")).toBeVisible();
  await page.getByRole("dialog").last().getByRole("button", { name: "Löschen" }).click();
  await expect(page.locator(".count")).toHaveText("1 Foto");
  expect(singles).toEqual([]);
  expect(batches).toHaveLength(1);
  expect(batches[0].action).toBe("delete");
  expect(batches[0].ids).toHaveLength(3);
  const left = (await apiAsBrowser<PhotoList>(page, "/photos")).items.map((p) => p.path);
  for (const n of [1, 2, 3, 4]) expect(await inPhotoFolder(`p${n}.jpg`)).toBe(left.includes(`p${n}.jpg`));
});

test("Partial failure on upload", async ({ page }) => {
  await pairInUi(page);
  await page.getByRole("button", { name: "Hochladen", exact: true }).click();
  const files = ["landscape.jpg", "rotated6.jpg", "image.png", "image.webp", "note.txt"].map((f) =>
    path.join(FIXTURES, f),
  );
  await page.getByTestId("upload-input").setInputFiles(files);
  await page.getByRole("dialog").getByLabel("Album", { exact: true }).selectOption("__new__");
  await page.getByLabel("Name des neuen Albums").fill("family");
  await page.getByRole("button", { name: "Hochladen starten" }).click();
  await expect(page.getByText("4 hochgeladen, 1 fehlgeschlagen")).toBeVisible();
  await expect(page.getByRole("alert")).toHaveText("Kein unterstütztes Format");
  await page.getByRole("button", { name: "Fertig" }).click();
  await expect(page.locator(".count")).toHaveText("4 Fotos");
  const res = await apiAsBrowser<PhotoList>(page, "/photos?album=family");
  expect(res.total).toBe(4);
  // The album the photos went to is open, although the overview started folded.
  await expect(foldButton(page, "family")).toHaveAttribute("aria-expanded", "true");
  await expect(albumTiles(page, "family")).toHaveCount(4);
});

test("Nested albums: own photos before the sub-albums, photos without an album last", async ({ page }) => {
  await usb("landscape.jpg", "holiday/a.jpg");
  await usb("landscape.jpg", "holiday/2024/b.jpg");
  await usb("landscape.jpg", "family/c.jpg");
  await usb("landscape.jpg", "d.jpg");
  await pairInUi(page);
  await expect(page.locator(".count")).toHaveText("4 Fotos");
  // First visit: every heading folded, the top level only.
  const names = () =>
    page
      .getByRole("main")
      .getByRole("heading")
      .evaluateAll((els) =>
        els.map(
          (el) =>
            `${el.getAttribute("aria-level")} ${el.querySelector("button")!.getAttribute("aria-label")}`,
        ),
      );
  await expect.poll(names).toEqual(["2 family, 1 Foto", "2 holiday, 2 Fotos", "2 Kein Album, 1 Foto"]);

  await openAlbum(page, "holiday");
  await expect
    .poll(names)
    .toEqual(["2 family, 1 Foto", "2 holiday, 2 Fotos", "3 holiday / 2024, 1 Foto", "2 Kein Album, 1 Foto"]);
  // Below holiday its own photo comes first, then the indented heading of its sub-album.
  const content = albumContent(page, "holiday");
  await expect(content.locator(":scope > *").first()).toHaveClass(/grid-box/);
  await expect(albumTiles(page, "holiday")).toHaveCount(1);
  const parent = (await albumHeading(page, "holiday").boundingBox())!;
  const sub = (await albumHeading(page, "holiday/2024").boundingBox())!;
  expect(sub.x).toBeGreaterThan(parent.x);
  await albumTiles(page, "holiday").first().click();
  await expect(photoDetail(page).getByText("a.jpg")).toBeVisible();
  await page.getByRole("button", { name: "Schließen" }).click();

  await openAlbum(page, "holiday/2024");
  await expect(albumTiles(page, "holiday/2024")).toHaveCount(1);
  await albumTiles(page, "holiday/2024").first().click();
  await expect(photoDetail(page).getByText("holiday / 2024")).toBeVisible();
  await page.getByRole("button", { name: "Schließen" }).click();

  // Photos without an album: the last heading, with exactly those photos and no switch.
  await openAlbum(page, "");
  await expect(albumTiles(page, "")).toHaveCount(1);
  await expect(albumTiles(page, "").locator("img")).toHaveAttribute("alt", "d.jpg");
  await expect(albumHeading(page, "").getByRole("switch")).toHaveCount(0);
  await expect(albumHeading(page, "holiday").getByRole("switch")).toHaveCount(1);
});

test("New sub-album", async ({ page }) => {
  await usb("landscape.jpg", "family/a.jpg");
  await pairInUi(page);
  await page.getByRole("button", { name: "Hochladen", exact: true }).click();
  await page.getByTestId("upload-input").setInputFiles(path.join(FIXTURES, "rotated6.jpg"));
  await page.getByRole("dialog").getByLabel("Album", { exact: true }).selectOption("__new__");
  await page.getByLabel("Innerhalb von").selectOption("family");
  await page.getByLabel("Name des neuen Albums").fill("2025");
  await page.getByRole("button", { name: "Hochladen starten" }).click();
  await expect(page.getByText("1 hochgeladen, 0 fehlgeschlagen")).toBeVisible();
  await page.getByRole("button", { name: "Fertig" }).click();
  const res = await apiAsBrowser<{ items: { album: string | null }[] }>(
    page,
    "/photos?album=family/2025&subalbums=false",
  );
  expect(res.items.map((p) => p.album)).toEqual(["family/2025"]);
  await expect(foldButton(page, "family")).toHaveAttribute("aria-expanded", "true");
  await expect(albumTiles(page, "family/2025")).toHaveCount(1);
});

test("Photo over USB appears without reloading", async ({ page }) => {
  await pairInUi(page);
  await expect(page.getByText("Noch keine Fotos")).toBeVisible();
  await usb("landscape.jpg", "neu.jpg", { scan: false });
  // Its heading appears with the new count and keeps the remembered state, folded on a first visit.
  await expect(foldButton(page, "")).toHaveAttribute("aria-expanded", "false", { timeout: 30_000 });
  await openAlbum(page, "");
  await expect(page.locator(".tile")).toHaveCount(1);
});

test("The list behind the detail view stays still", async ({ page }) => {
  // On a Pixel 8 a swipe on the open detail view scrolled the photo list underneath.
  await many(300);
  await page.setViewportSize({ width: 390, height: 844 });
  await pairInUi(page);
  await openAlbum(page, "viele");
  await expect(page.locator(".tile").first()).toBeVisible();
  await page.evaluate(() => window.scrollTo(0, 1200));
  await expect.poll(() => page.evaluate(() => window.scrollY)).toBeGreaterThan(1000);
  await page.locator(".tile").nth(20).click();
  const dialog = page.getByRole("dialog");
  await expect(dialog).toBeVisible();
  // Measured with the dialog open: the click itself may scroll the tile into view first.
  const before = await page.evaluate(() => window.scrollY);
  expect(before).toBeGreaterThan(0);
  const box = (await dialog.boundingBox())!;
  await page.mouse.move(box.x + box.width / 2, box.y + 40);
  await page.mouse.wheel(0, 800);
  await page.waitForTimeout(300);
  expect(await page.evaluate(() => window.scrollY)).toBe(before);
  await page.getByRole("button", { name: "Schließen" }).click();
  await expect(dialog).toBeHidden();
  await page.mouse.wheel(0, 800);
  await expect.poll(() => page.evaluate(() => window.scrollY)).toBeGreaterThan(before);
});

/** The photo list requests of the page from now on, and how many ran at a time at most. */
function photoListRequests(page: Page) {
  const urls: URL[] = [];
  let inFlight = 0;
  let max = 0;
  const isList = (r: Request) => r.method() === "GET" && new URL(r.url()).pathname === "/api/v1/photos";
  page.on("request", (r) => {
    if (!isList(r)) return;
    urls.push(new URL(r.url()));
    inFlight++;
    max = Math.max(max, inFlight);
  });
  const done = (r: Request) => {
    if (isList(r)) inFlight--;
  };
  page.on("requestfinished", done);
  page.on("requestfailed", done);
  return {
    urls,
    get max() {
      return max;
    },
  };
}

/** Uploads [fixture] through the dialog into the album chosen by [choose]; returns when the summary is shown. */
async function upload(page: Page, fixture: string, summary: string, choose?: () => Promise<unknown>) {
  await page.getByRole("button", { name: "Hochladen", exact: true }).click();
  await page.getByTestId("upload-input").setInputFiles(path.join(FIXTURES, fixture));
  if (choose) await choose();
  await page.getByRole("button", { name: "Hochladen starten" }).click();
  await expect(page.getByText(summary)).toBeVisible();
  await page.getByRole("button", { name: "Fertig" }).click();
}

const albumChoice = (page: Page) => page.getByRole("dialog").getByLabel("Album", { exact: true });

/** The ids of the photos of [album] by file name, through the API. */
async function idsByName(page: Page, album: string): Promise<Record<string, string>> {
  const res = await apiAsBrowser<PhotoList>(page, `/photos?album=${encodeURIComponent(album)}`);
  return Object.fromEntries(res.items.map((p) => [p.path.split("/").at(-1)!, p.id]));
}

const setHidden = (page: Page, id: string, hidden: boolean) =>
  apiAsBrowser(page, `/photos/${id}`, { method: "PATCH", body: { hidden } });

/**
 * Whether the grid of [album] shows tiles over the whole part of it that is on screen: `ok`, `empty` when rows on
 * screen have no tiles, `offscreen` when no part of it is on screen.
 */
function rowsOnScreen(page: Page, album: string): Promise<"ok" | "empty" | "offscreen"> {
  return albumContent(page, album)
    .locator(":scope > .grid-box")
    .evaluate((box) => {
      const r = box.getBoundingClientRect();
      const top = Math.max(r.top, 0);
      const bottom = Math.min(r.bottom, innerHeight);
      if (bottom - top < 20) return "offscreen";
      const tiles = [...box.querySelectorAll(".tile")].map((t) => t.getBoundingClientRect());
      if (tiles.length === 0) return "empty";
      const covered =
        Math.min(...tiles.map((t) => t.top)) <= top + 1 &&
        Math.max(...tiles.map((t) => t.bottom)) >= bottom - 1;
      return covered ? "ok" : "empty";
    });
}

/** Scrolls so that the grid of [album] starts [y] px below the top of the window. */
async function scrollGridTo(page: Page, album: string, y: number) {
  await albumContent(page, album)
    .locator(":scope > .grid-box")
    .evaluate((box, y) => window.scrollTo(0, window.scrollY + box.getBoundingClientRect().top - y), y);
}

/** [n] photos in [album], scanned once at the end. */
async function photosIn(album: string, n: number) {
  for (let i = 0; i < n; i++)
    await usb("landscape.jpg", `${album}/${String(i).padStart(3, "0")}.jpg`, { scan: i === n - 1 });
}

test("First visit: every heading folded, no photo list requested", async ({ page }) => {
  await usb("landscape.jpg", "holiday/a.jpg");
  await usb("landscape.jpg", "holiday/2024/b.jpg");
  await usb("landscape.jpg", "c.jpg");
  const requests = photoListRequests(page);
  await pairInUi(page);
  await expect(foldButton(page, "holiday")).toHaveAttribute("aria-expanded", "false");
  await expect(foldButton(page, "")).toHaveAttribute("aria-expanded", "false");
  await expect(foldButton(page, "holiday/2024")).toHaveCount(0);
  // The content below a folded heading exists, empty, for the fold button's aria-controls.
  await expect(albumContent(page, "holiday")).toBeAttached();
  await expect(albumContent(page, "holiday")).toBeEmpty();
  await expect(foldButton(page, "holiday")).toHaveAttribute("aria-controls", "album-holiday-content");
  await page.waitForLoadState("networkidle");
  expect(requests.urls).toEqual([]);
  await expect(page.locator(".tile")).toHaveCount(0);
});

test("Remembered state across a reload", async ({ page }) => {
  await usb("landscape.jpg", "holiday/a.jpg");
  await usb("landscape.jpg", "family/b.jpg");
  await pairInUi(page);
  await openAlbum(page, "holiday");
  await expect(albumTiles(page, "holiday")).toHaveCount(1);
  await page.reload();
  await expect(foldButton(page, "holiday")).toHaveAttribute("aria-expanded", "true");
  await expect(foldButton(page, "family")).toHaveAttribute("aria-expanded", "false");
  await expect(albumTiles(page, "holiday")).toHaveCount(1);
  await expect(page.locator(".tile")).toHaveCount(1);
});

test("Count follows the filter, and an album without matching photos is left out", async ({ page }) => {
  await usb("landscape.jpg", "holiday/a.jpg");
  await usb("landscape.jpg", "holiday/b.jpg");
  await usb("landscape.jpg", "holiday/2024/c.jpg");
  await usb("landscape.jpg", "holiday/2024/d.jpg");
  await usb("landscape.jpg", "family/e.jpg");
  await pairInUi(page);
  const ids = await idsByName(page, "holiday");
  await setHidden(page, ids["a.jpg"]!, true);
  await setHidden(page, ids["c.jpg"]!, true);
  await page.reload();
  await expect(foldButton(page, "holiday")).toHaveAccessibleName("holiday, 4 Fotos");
  await expect(page.locator(".count")).toHaveText("5 Fotos");

  const filter = page.getByRole("combobox", { name: "Sichtbarkeit" });
  await filter.selectOption("hidden");
  await expect(foldButton(page, "holiday")).toHaveAccessibleName("holiday, 2 Fotos");
  await expect(page.locator(".count")).toHaveText("2 Fotos");
  await expect(foldButton(page, "family")).toHaveCount(0);
  // Within the album only its hidden photos.
  await openAlbum(page, "holiday");
  await expect(albumTiles(page, "holiday")).toHaveCount(1);
  await expect(albumTiles(page, "holiday").locator("img")).toHaveAttribute("alt", "holiday/a.jpg");
  await expect(foldButton(page, "holiday/2024")).toHaveAccessibleName("holiday / 2024, 1 Foto");

  await filter.selectOption("visible");
  await expect(foldButton(page, "holiday")).toHaveAccessibleName("holiday, 2 Fotos");
  await expect(foldButton(page, "family")).toHaveAccessibleName("family, 1 Foto");
  await expect(page.locator(".count")).toHaveText("3 Fotos");
  await expect(albumTiles(page, "holiday").locator("img")).toHaveAttribute("alt", "holiday/b.jpg");
});

test("Album without photos of its own: no photo list for it", async ({ page }) => {
  await usb("landscape.jpg", "holiday/2024/a.jpg");
  const requests = photoListRequests(page);
  await pairInUi(page);
  await openAlbum(page, "holiday/2024");
  await expect(albumTiles(page, "holiday/2024")).toHaveCount(1);
  await page.waitForLoadState("networkidle");
  await expect(albumContent(page, "holiday").locator(":scope > .grid-box")).toHaveCount(0);
  expect(requests.urls.map((u) => [u.searchParams.get("album"), u.searchParams.get("subalbums")])).toEqual([
    ["holiday/2024", "false"],
  ]);
});

test("Photos without an album are asked for with noalbum", async ({ page }) => {
  await usb("landscape.jpg", "a.jpg");
  await usb("landscape.jpg", "holiday/b.jpg");
  const requests = photoListRequests(page);
  await pairInUi(page);
  await openAlbum(page, "");
  await expect(albumTiles(page, "")).toHaveCount(1);
  expect(requests.urls.map((u) => [u.searchParams.get("noalbum"), u.searchParams.get("album")])).toEqual([
    ["true", null],
  ]);
});

test("Reload with many open albums: two photo lists at a time, only near the screen", async ({ page }) => {
  const albums = Array.from({ length: 20 }, (_, i) => `album${String(i).padStart(2, "0")}`);
  for (const [i, album] of albums.entries()) await usb("landscape.jpg", `${album}/a.jpg`, { scan: i === 19 });
  await page.setViewportSize({ width: 1280, height: 800 });
  await pairInUi(page);
  await expect(foldButton(page, "album19")).toBeVisible();
  await page.evaluate(
    (paths) => localStorage.setItem("openframe.photos.openAlbums", JSON.stringify(paths)),
    albums,
  );
  const requests = photoListRequests(page);
  await page.reload();
  await expect(albumTiles(page, "album00")).toHaveCount(1);
  await page.waitForLoadState("networkidle");
  const asked = () => requests.urls.map((u) => u.searchParams.get("album"));
  expect(requests.max).toBeLessThanOrEqual(2);
  expect(asked()).toContain("album00");
  expect(asked()).not.toContain("album19");
  expect(requests.urls.every((u) => u.searchParams.get("limit") === "60")).toBe(true);
  // Scrolled to the end, the albums there are asked for, still two at a time.
  await page.evaluate(() => window.scrollTo(0, document.documentElement.scrollHeight));
  await expect(albumTiles(page, "album19")).toHaveCount(1);
  await page.waitForLoadState("networkidle");
  expect(requests.max).toBeLessThanOrEqual(2);
  expect(new Set(asked()).size).toBe(asked().length);
});

test("Empty library: no heading, the hint to upload", async ({ page }) => {
  await pairInUi(page);
  await expect(page.getByText("Noch keine Fotos")).toBeVisible();
  await expect(page.getByRole("main").getByRole("heading")).toHaveCount(0);
});

test("Upload on the first visit opens the heading for photos without an album", async ({ page }) => {
  await usb("landscape.jpg", "holiday/a.jpg");
  await pairInUi(page);
  await expect(foldButton(page, "holiday")).toBeVisible();
  await upload(page, "rotated6.jpg", "1 hochgeladen, 0 fehlgeschlagen");
  await expect(foldButton(page, "")).toHaveAttribute("aria-expanded", "true");
  await expect(albumTiles(page, "")).toHaveCount(1);
  await expect(foldButton(page, "holiday")).toHaveAttribute("aria-expanded", "false");
  await page.reload();
  await expect(foldButton(page, "")).toHaveAttribute("aria-expanded", "true");
});

test("Upload into a folded sub-album opens it with the album above", async ({ page }) => {
  await usb("landscape.jpg", "holiday/a.jpg");
  await usb("landscape.jpg", "holiday/2024/b.jpg");
  await pairInUi(page);
  await expect(foldButton(page, "holiday")).toHaveAttribute("aria-expanded", "false");
  await upload(page, "rotated6.jpg", "1 hochgeladen, 0 fehlgeschlagen", () =>
    albumChoice(page).selectOption("holiday/2024"),
  );
  await expect(foldButton(page, "holiday")).toHaveAttribute("aria-expanded", "true");
  await expect(foldButton(page, "holiday/2024")).toHaveAttribute("aria-expanded", "true");
  await expect(albumTiles(page, "holiday/2024")).toHaveCount(2);
});

test("Upload into a new album opens the album as the frame cleaned its name", async ({ page }) => {
  await usb("landscape.jpg", "holiday/a.jpg");
  await pairInUi(page);
  await upload(page, "rotated6.jpg", "1 hochgeladen, 0 fehlgeschlagen", async () => {
    await albumChoice(page).selectOption("__new__");
    await page.getByLabel("Innerhalb von").selectOption("holiday");
    await page.getByLabel("Name des neuen Albums").fill(" .trip");
  });
  await expect(foldButton(page, "holiday/trip")).toHaveAttribute("aria-expanded", "true");
  await expect(albumTiles(page, "holiday/trip")).toHaveCount(1);
});

test("An upload that fails completely opens nothing", async ({ page }) => {
  await usb("landscape.jpg", "holiday/a.jpg");
  await pairInUi(page);
  await upload(page, "note.txt", "0 hochgeladen, 1 fehlgeschlagen", () =>
    albumChoice(page).selectOption("holiday"),
  );
  await expect(foldButton(page, "holiday")).toHaveAttribute("aria-expanded", "false");
  expect(await page.evaluate(() => localStorage.getItem("openframe.photos.openAlbums"))).toBeNull();
});

// Spec web-admin, "Grids follow the layout".
test("Fold a heading above an open album", async ({ page }) => {
  await photosIn("family", 40);
  await many(300);
  await page.setViewportSize({ width: 1280, height: 800 });
  await pairInUi(page);
  await openAlbum(page, "family");
  await openAlbum(page, "viele");
  await expect(albumTiles(page, "viele").first()).toBeVisible();
  await scrollGridTo(page, "viele", 100);
  await expect.poll(() => rowsOnScreen(page, "viele")).toBe("ok");
  // Folded without scrolling to its button, as a click on a heading that is still on screen would.
  await foldButton(page, "family").evaluate((button: HTMLButtonElement) => button.click());
  await expect(foldButton(page, "family")).toHaveAttribute("aria-expanded", "false");
  await expect.poll(() => rowsOnScreen(page, "viele"), { timeout: 1_000 }).toBe("ok");
});

test("Grid above grows while a lower one is looked at", async ({ page }) => {
  await photosIn("family", 70);
  await many(300);
  // The second page of family waits until the user looks at viele.
  let release!: () => void;
  const held = new Promise<void>((resolve) => (release = resolve));
  await page.route("**/api/v1/photos?*", async (route) => {
    const url = new URL(route.request().url());
    if (url.searchParams.get("album") === "family" && url.searchParams.has("cursor")) await held;
    await route.fallback();
  });
  await page.setViewportSize({ width: 1280, height: 800 });
  await pairInUi(page);
  await openAlbum(page, "family");
  await openAlbum(page, "viele");
  await expect(albumTiles(page, "viele").first()).toBeAttached();
  await scrollGridTo(page, "viele", 300);
  await expect.poll(() => rowsOnScreen(page, "viele")).toBe("ok");
  const before = await albumContent(page, "family")
    .locator(":scope > .grid-box")
    .evaluate((b) => b.clientHeight);
  release();
  await expect
    .poll(() =>
      albumContent(page, "family")
        .locator(":scope > .grid-box")
        .evaluate((b) => b.clientHeight),
    )
    .toBeGreaterThan(before);
  await expect.poll(() => rowsOnScreen(page, "viele"), { timeout: 1_000 }).toBe("ok");
});

test("Notice above the overview closes, then the user scrolls", async ({ page }) => {
  await many(300);
  await page.setViewportSize({ width: 1280, height: 800 });
  await page.goto("/#pair=" + (await frameSecret()));
  const notice = page.getByRole("status").filter({ hasText: "gekoppelt" });
  await expect(notice).toBeVisible();
  await openAlbum(page, "viele");
  await expect(albumTiles(page, "viele").first()).toBeVisible();
  await notice.getByRole("button", { name: "Schließen" }).click();
  await expect(notice).toHaveCount(0);
  await page.evaluate(() => window.scrollTo(0, 1500));
  await expect.poll(() => rowsOnScreen(page, "viele")).toBe("ok");
});

test("An error appears above the overview, then the user scrolls", async ({ page }) => {
  await many(300);
  await page.setViewportSize({ width: 1280, height: 800 });
  await pairInUi(page);
  await openAlbum(page, "viele");
  await expect(albumTiles(page, "viele").first()).toBeVisible();
  await page.route("**/api/v1/albums", (route) =>
    route.request().method() === "PATCH"
      ? route.fulfill({ status: 503, contentType: "application/json", body: "{}" })
      : route.fallback(),
  );
  await albumSwitch(page, "viele").click();
  await expect(page.getByRole("alert")).toBeVisible();
  await page.evaluate(() => window.scrollTo(0, 1500));
  await expect.poll(() => rowsOnScreen(page, "viele")).toBe("ok");
});

// Spec web-admin: "Trash in the interface".

test("Restore a deleted photo", async ({ page }) => {
  await usb("landscape.jpg", "urlaub/strand.jpg");
  await usb("gps-siena.jpg", "urlaub/stadt.jpg");
  await pairInUi(page);
  await openAlbum(page, "urlaub");
  await expect(albumTiles(page, "urlaub")).toHaveCount(2);
  await page.locator(".tile").first().click();
  await page.getByRole("button", { name: "Löschen" }).click();
  await page.getByRole("dialog").last().getByRole("button", { name: "Löschen" }).click();
  await expect(albumTiles(page, "urlaub")).toHaveCount(1);

  const trash = page.getByRole("region", { name: "Papierkorb" });
  await expect(trash.getByText(/^1 gelöschtes Foto im Papierkorb/)).toBeVisible();
  await trash.getByRole("button", { name: "Anzeigen" }).click();
  const row = trash.getByRole("listitem");
  await expect(row).toHaveCount(1);
  await expect(row).toContainText("Album: urlaub");
  await expect(row).toContainText("von diesem Gerät");
  await expect(row).toContainText("frühestens einen Tag nach dem Löschen");
  await expect(row.locator("img")).toHaveCount(1);
  await row.getByRole("button", { name: "Wiederherstellen" }).click();

  await expect(trash).toHaveCount(0);
  await expect(albumTiles(page, "urlaub")).toHaveCount(2);
  const { items } = await apiAsBrowser<PhotoList>(page, "/photos");
  expect(items.map((p) => p.path).sort()).toEqual(["urlaub/stadt.jpg", "urlaub/strand.jpg"]);
});

test("Restore all", async ({ page }) => {
  for (const n of [1, 2, 3]) await usb("landscape.jpg", `p${n}.jpg`);
  await pairInUi(page);
  await openAlbum(page, "");
  await expect(page.locator(".tile")).toHaveCount(3);
  await page.getByRole("button", { name: "Auswählen" }).click();
  for (const i of [0, 1, 2]) await page.locator(".tile").nth(i).click();
  await page.getByRole("toolbar").getByRole("button", { name: "Löschen" }).click();
  await page.getByRole("dialog").last().getByRole("button", { name: "Löschen" }).click();
  const trash = page.getByRole("region", { name: "Papierkorb" });
  await expect(trash.getByText(/^3 gelöschte Fotos im Papierkorb/)).toBeVisible();
  await trash.getByRole("button", { name: "Anzeigen" }).click();
  await trash.getByRole("button", { name: "Alle wiederherstellen" }).click();
  await expect(trash).toHaveCount(0);
  expect((await apiAsBrowser<PhotoList>(page, "/photos")).total).toBe(3);
  for (const n of [1, 2, 3]) expect(await inPhotoFolder(`p${n}.jpg`)).toBe(true);
});
