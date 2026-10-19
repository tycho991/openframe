import { expect, test } from "@playwright/test";
import { apiAsBrowser, pairInUi, pairOtherDevice, reset, usb } from "./helpers";

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
