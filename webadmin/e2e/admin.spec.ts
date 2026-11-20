import { expect, test, type Page } from "@playwright/test";
import {
  albumHeading,
  albumSwitch,
  albumTiles,
  apiAsBrowser,
  escapeRegExp,
  foldButton,
  FRAME_ORIGIN,
  openAlbum,
  pairInUi,
  pairOtherDevice,
  reset,
  slowDisplay,
  usb,
} from "./helpers";

test.beforeEach(async () => {
  await reset();
});

test("Hide album", async ({ page }) => {
  await usb("landscape.jpg", "holiday/a.jpg");
  await usb("landscape.jpg", "family/b.jpg");
  await pairInUi(page);
  await albumSwitch(page, "holiday").uncheck();
  await expect(albumSwitch(page, "holiday")).not.toBeChecked();
  await expect(albumSwitch(page, "family")).toBeChecked();
  const albums = await apiAsBrowser<{ albums: { name: string; hidden: boolean }[] }>(page, "/albums");
  expect(albums.albums.find((a) => a.name === "holiday")?.hidden).toBe(true);
  const photos = await apiAsBrowser<{ items: { visible: boolean }[] }>(page, "/photos?album=holiday");
  expect(photos.items.every((p) => !p.visible)).toBe(true);
});

async function visiblePaths(page: Page): Promise<string[]> {
  const res = await apiAsBrowser<{ items: { path: string }[] }>(page, "/photos?visible=true&sort=name_asc");
  return res.items.map((p) => p.path);
}

test("Show a sub-album of a hidden album", async ({ page }) => {
  await usb("landscape.jpg", "holiday/a.jpg");
  await usb("landscape.jpg", "holiday/2024/b.jpg");
  await usb("landscape.jpg", "holiday/2024/may/c.jpg");
  await pairInUi(page);
  await expect(foldButton(page, "holiday")).toHaveAccessibleName("holiday, 3 Fotos");
  // Sub-albums appear when the album above is opened.
  await expect(foldButton(page, "holiday/2024")).toHaveCount(0);
  await openAlbum(page, "holiday/2024");
  await expect(foldButton(page, "holiday/2024/may")).toBeVisible();

  await albumSwitch(page, "holiday").uncheck();
  await expect(albumSwitch(page, "holiday/2024")).not.toBeChecked();
  await expect(albumSwitch(page, "holiday/2024/may")).not.toBeChecked();
  await albumSwitch(page, "holiday/2024").check();
  await expect(albumHeading(page, "holiday/2024").getByText("Ausnahme")).toBeVisible();
  await expect(albumSwitch(page, "holiday/2024/may")).toBeChecked();
  await expect(albumHeading(page, "holiday/2024/may").getByText("Ausnahme")).toHaveCount(0);
  await expect(albumSwitch(page, "holiday")).not.toBeChecked();
  expect(await visiblePaths(page)).toEqual(["holiday/2024/b.jpg", "holiday/2024/may/c.jpg"]);
});

test("A switch that fails shows the album's state again", async ({ page }) => {
  await usb("landscape.jpg", "holiday/a.jpg");
  await pairInUi(page);
  await page.route("**/api/v1/albums", (route) =>
    route.request().method() === "PATCH"
      ? route.fulfill({ status: 503, contentType: "application/json", body: "{}" })
      : route.fallback(),
  );
  const toggle = albumSwitch(page, "holiday");
  await toggle.click();
  await expect(page.getByRole("alert")).toBeVisible();
  await expect(toggle).toBeChecked();
  expect(await visiblePaths(page)).toEqual(["holiday/a.jpg"]);
});

test("Switching a parent removes exceptions", async ({ page }) => {
  await usb("landscape.jpg", "holiday/a.jpg");
  await usb("landscape.jpg", "holiday/2024/b.jpg");
  await pairInUi(page);
  await openAlbum(page, "holiday");
  await albumSwitch(page, "holiday").uncheck();
  await expect(albumSwitch(page, "holiday/2024")).not.toBeChecked();
  await albumSwitch(page, "holiday/2024").check();
  await expect(albumHeading(page, "holiday/2024").getByText("Ausnahme")).toBeVisible();

  await albumSwitch(page, "holiday").check();
  await expect(albumHeading(page, "holiday/2024").getByText("Ausnahme")).toHaveCount(0);
  await expect(albumSwitch(page, "holiday/2024")).toBeChecked();
  await albumSwitch(page, "holiday").uncheck();
  await expect(albumSwitch(page, "holiday/2024")).not.toBeChecked();
  await expect(albumHeading(page, "holiday/2024").getByText("Ausnahme")).toHaveCount(0);
  expect(await visiblePaths(page)).toEqual([]);
});

// Spec web-admin, "Album switch in the photo overview".
test("Grids follow the switch before the next regular check", async ({ page }) => {
  await usb("landscape.jpg", "holiday/a.jpg");
  await pairInUi(page);
  await openAlbum(page, "holiday");
  const tiles = albumTiles(page, "holiday");
  await expect(tiles).toHaveCount(1);
  await expect(tiles.locator(".badge")).toHaveCount(0);
  await albumSwitch(page, "holiday").uncheck();
  // The regular check runs every 10 seconds.
  await expect(tiles.locator(".badge")).toHaveText("Ausgeblendet", { timeout: 5_000 });
});

/** Hides [album] by its switch through the API, so that every photo in it is hidden by the album. */
const hideAlbum = (page: Page, album: string) =>
  apiAsBrowser(page, "/albums", { method: "PATCH", body: { name: album, hidden: true } });

test("Switch with a filter: the heading stays in place with its switch focused", async ({ page }) => {
  await usb("landscape.jpg", "holiday/a.jpg");
  await usb("landscape.jpg", "holiday/b.jpg");
  await usb("landscape.jpg", "family/c.jpg");
  await pairInUi(page);
  await hideAlbum(page, "holiday");
  await page.reload();
  await page.getByRole("combobox", { name: "Sichtbarkeit" }).selectOption("hidden");
  await expect(foldButton(page, "family")).toHaveCount(0);
  await openAlbum(page, "holiday");
  await expect(albumTiles(page, "holiday")).toHaveCount(2);
  const toggle = albumSwitch(page, "holiday");
  const before = (await albumHeading(page, "holiday").boundingBox())!;
  await toggle.check();
  await expect(toggle).toBeChecked();
  await expect(toggle).toBeFocused();
  await expect(foldButton(page, "holiday")).toHaveAccessibleName("holiday, 0 Fotos");
  await expect(albumTiles(page, "holiday")).toHaveCount(0);
  expect((await albumHeading(page, "holiday").boundingBox())!.y).toBe(before.y);
  // A new filter lets it go.
  await page.getByRole("combobox", { name: "Sortierung" }).selectOption("name_asc");
  await expect(foldButton(page, "holiday")).toHaveCount(0);
});

test("Switch a sub-album with a filter: it and the album above stay", async ({ page }) => {
  await usb("landscape.jpg", "holiday/2024/a.jpg");
  await usb("landscape.jpg", "family/b.jpg");
  await pairInUi(page);
  await hideAlbum(page, "holiday/2024");
  await page.reload();
  await page.getByRole("combobox", { name: "Sichtbarkeit" }).selectOption("hidden");
  await openAlbum(page, "holiday/2024");
  const toggle = albumSwitch(page, "holiday/2024");
  await toggle.check();
  await expect(toggle).toBeChecked();
  await expect(toggle).toBeFocused();
  await expect(foldButton(page, "holiday")).toHaveAccessibleName("holiday, 0 Fotos");
  await expect(foldButton(page, "holiday/2024")).toHaveAccessibleName("holiday / 2024, 0 Fotos");
});

test("Error at the field", async ({ page }) => {
  await pairInUi(page);
  await page.getByRole("link", { name: "Einstellungen" }).click();
  const interval = page.getByLabel("Wechsel alle (Sekunden)");
  await interval.fill("1");
  await page.getByLabel("Uhr anzeigen").uncheck();
  await page.getByRole("button", { name: "Speichern" }).click();
  await expect(page.getByRole("alert")).toHaveText("Erlaubt: 5 bis 86400");
  await expect(interval).toHaveAttribute("aria-invalid", "true");
  const settings = await apiAsBrowser<{ intervalSeconds: number; clock: { enabled: boolean } }>(
    page,
    "/settings",
  );
  expect(settings.intervalSeconds).toBe(30);
  expect(settings.clock.enabled).toBe(true);
  // Save validly: confirmed without reloading.
  await interval.fill("60");
  await page.getByRole("button", { name: "Speichern" }).click();
  await expect(page.getByRole("status")).toHaveText("Gespeichert");
  expect((await apiAsBrowser<{ intervalSeconds: number }>(page, "/settings")).intervalSeconds).toBe(60);
});

test("Remove old device", async ({ page, baseURL }) => {
  await pairInUi(page);
  await pairOtherDevice(baseURL!, "Alter Laptop");
  await page.getByRole("link", { name: "Geräte" }).click();
  const old = page.getByRole("listitem").filter({ hasText: "Alter Laptop" });
  await expect(page.getByRole("listitem").filter({ hasText: "Dieses Gerät" })).toContainText("Playwright");
  await old.getByRole("button", { name: "Kopplung aufheben" }).click();
  await page.getByRole("dialog").getByRole("button", { name: "Kopplung aufheben" }).click();
  await expect(old).toHaveCount(0);
  await expect(page.getByRole("listitem")).toHaveCount(1);
  await page.reload();
  await expect(page.getByRole("navigation")).toBeVisible();
});

test("Rename device", async ({ page }) => {
  await pairInUi(page);
  await page.getByRole("link", { name: "Geräte" }).click();
  const current = page.getByRole("listitem").filter({ hasText: "Dieses Gerät" });
  await current.getByRole("button", { name: "Umbenennen" }).click();
  await page.getByLabel("Neuer Name").fill("Büro-Laptop");
  await page.getByRole("button", { name: "Speichern" }).click();
  await expect(page.getByRole("listitem").filter({ hasText: "Dieses Gerät" })).toContainText("Büro-Laptop");
  expect((await apiAsBrowser<{ clients: { name: string }[] }>(page, "/clients")).clients[0]?.name).toBe(
    "Büro-Laptop",
  );
});

test("Show invitation", async ({ page }) => {
  await pairInUi(page);
  await page.getByRole("link", { name: "Geräte" }).click();
  await page.getByRole("button", { name: "Gerät einladen" }).click();
  await expect(page.getByRole("img", { name: "QR-Code der Einladung" })).toBeVisible();
  await expect(page.getByLabel("Link")).toHaveValue(
    new RegExp(`^${escapeRegExp(FRAME_ORIGIN)}/#pair=[A-Za-z0-9_-]{22}$`),
  );
  await expect(page.getByRole("timer")).toHaveText(/^Noch [45]:\d\d gültig$/);
});

test("Invitation accepted", async ({ page, browser }) => {
  await pairInUi(page);
  await page.getByRole("link", { name: "Geräte" }).click();
  await page.getByRole("button", { name: "Gerät einladen" }).click();
  const link = await page.getByLabel("Link").inputValue();

  const other = await browser.newContext({ locale: "de-DE" });
  const second = await other.newPage();
  await second.goto(link);
  await expect(second.getByRole("navigation")).toBeVisible();
  await other.close();

  await expect(page.getByRole("listitem")).toHaveCount(2, { timeout: 30_000 });
  await expect(page.getByText(/^„.+“ ist jetzt gekoppelt\.$/)).toBeVisible();
});

test("Invitation expires", async ({ page }) => {
  await pairInUi(page);
  await page.getByRole("link", { name: "Geräte" }).click();
  await page.clock.install();
  await page.getByRole("button", { name: "Gerät einladen" }).click();
  await expect(page.getByRole("timer")).toBeVisible();
  await page.clock.fastForward("05:01");
  await expect(page.getByText("Die Einladung ist abgelaufen.")).toBeVisible();
  await page.getByRole("button", { name: "Neue Einladung" }).click();
  await expect(page.getByRole("timer")).toBeVisible();
});

test("Note on instant changes", async ({ page }) => {
  await pairInUi(page);
  await page.getByRole("link", { name: "Einstellungen" }).click();
  const note = page.getByText("deshalb wechseln die Fotos sofort");
  await expect(note).toBeHidden();
  await slowDisplay();
  // The interface polls the state every 10 seconds; a reload shows it at once.
  await page.reload();
  await page.getByRole("link", { name: "Einstellungen" }).click();
  await expect(note).toBeVisible();
  // The transition stays as chosen; changing its duration tries it again.
  expect((await apiAsBrowser<{ transition: string }>(page, "/settings")).transition).toBe("fade");
  await page.getByLabel("Dauer des Übergangs (ms)").fill("1200");
  await expect(note).toBeHidden();
  await page.getByRole("button", { name: "Speichern" }).click();
  await expect(page.getByRole("status")).toHaveText("Gespeichert");
  await expect(note).toBeHidden();
  expect((await apiAsBrowser<{ transitionFallback: boolean }>(page, "/state")).transitionFallback).toBe(
    false,
  );
});
