import { expect, test, type Locator, type Page } from "@playwright/test";
import {
  apiAsBrowser,
  escapeRegExp,
  FRAME_ORIGIN,
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
  await page.getByRole("link", { name: "Alben" }).click();
  const holiday = page.getByRole("listitem").filter({ hasText: "holiday" });
  await holiday.getByRole("switch", { name: "In der Slideshow zeigen" }).uncheck();
  await expect(holiday.getByRole("switch")).not.toBeChecked();
  const albums = await apiAsBrowser<{ albums: { name: string; hidden: boolean }[] }>(page, "/albums");
  expect(albums.albums.find((a) => a.name === "holiday")?.hidden).toBe(true);
  const photos = await apiAsBrowser<{ items: { visible: boolean }[] }>(page, "/photos?album=holiday");
  expect(photos.items.every((p) => !p.visible)).toBe(true);
});

/** A row of the albums page by its title, the last level of the album's path. */
function albumRow(page: Page, title: string): Locator {
  return page.getByRole("listitem").filter({ has: page.getByRole("heading", { name: title, exact: true }) });
}

async function visiblePaths(page: Page): Promise<string[]> {
  const res = await apiAsBrowser<{ items: { path: string }[] }>(page, "/photos?visible=true&sort=name_asc");
  return res.items.map((p) => p.path);
}

test("Show a sub-album of a hidden album", async ({ page }) => {
  await usb("landscape.jpg", "holiday/a.jpg");
  await usb("landscape.jpg", "holiday/2024/b.jpg");
  await usb("landscape.jpg", "holiday/2024/may/c.jpg");
  await pairInUi(page);
  await page.getByRole("link", { name: "Alben" }).click();
  await expect(albumRow(page, "holiday").getByText("3 Fotos")).toBeVisible();
  // The second level is shown, deeper ones only after opening.
  await expect(albumRow(page, "2024")).toBeVisible();
  await expect(albumRow(page, "may")).toHaveCount(0);
  await page.getByRole("button", { name: "Unteralben von holiday/2024 aufklappen" }).click();
  await expect(albumRow(page, "may")).toBeVisible();

  await albumRow(page, "holiday").getByRole("switch").uncheck();
  await expect(albumRow(page, "2024").getByRole("switch")).not.toBeChecked();
  await expect(albumRow(page, "may").getByRole("switch")).not.toBeChecked();
  await albumRow(page, "2024").getByRole("switch").check();
  await expect(albumRow(page, "2024").getByText("Ausnahme")).toBeVisible();
  await expect(albumRow(page, "may").getByRole("switch")).toBeChecked();
  await expect(albumRow(page, "may").getByText("Ausnahme")).toHaveCount(0);
  await expect(albumRow(page, "holiday").getByRole("switch")).not.toBeChecked();
  expect(await visiblePaths(page)).toEqual(["holiday/2024/b.jpg", "holiday/2024/may/c.jpg"]);
});

test("A switch that fails shows the album's state again", async ({ page }) => {
  await usb("landscape.jpg", "holiday/a.jpg");
  await pairInUi(page);
  await page.getByRole("link", { name: "Alben" }).click();
  await page.route("**/api/v1/albums", (route) =>
    route.request().method() === "PATCH"
      ? route.fulfill({ status: 503, contentType: "application/json", body: "{}" })
      : route.fallback(),
  );
  const toggle = albumRow(page, "holiday").getByRole("switch");
  await toggle.click();
  await expect(page.getByRole("alert")).toBeVisible();
  await expect(toggle).toBeChecked();
  expect(await visiblePaths(page)).toEqual(["holiday/a.jpg"]);
});

test("Switching a parent removes exceptions", async ({ page }) => {
  await usb("landscape.jpg", "holiday/a.jpg");
  await usb("landscape.jpg", "holiday/2024/b.jpg");
  await pairInUi(page);
  await page.getByRole("link", { name: "Alben" }).click();
  await albumRow(page, "holiday").getByRole("switch").uncheck();
  await expect(albumRow(page, "2024").getByRole("switch")).not.toBeChecked();
  await albumRow(page, "2024").getByRole("switch").check();
  await expect(albumRow(page, "2024").getByText("Ausnahme")).toBeVisible();

  await albumRow(page, "holiday").getByRole("switch").check();
  await expect(albumRow(page, "2024").getByText("Ausnahme")).toHaveCount(0);
  await expect(albumRow(page, "2024").getByRole("switch")).toBeChecked();
  await albumRow(page, "holiday").getByRole("switch").uncheck();
  await expect(albumRow(page, "2024").getByRole("switch")).not.toBeChecked();
  await expect(albumRow(page, "2024").getByText("Ausnahme")).toHaveCount(0);
  expect(await visiblePaths(page)).toEqual([]);
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
