import { expect, test } from "@playwright/test";
import { albumTiles, openAlbum, pairInUi, photoDetail, quarantine, reset, usb } from "./helpers";

// Spec web-admin: "Quarantined photos in the interface", "Skipped files".

test.beforeEach(async () => {
  await reset();
});

test("A quarantined photo: a placeholder without an image request, no section editor, and try again", async ({
  page,
}) => {
  await usb("landscape.jpg", "holiday/a.jpg");
  await usb("gps-siena.jpg", "holiday/b.jpg");
  const id = await quarantine("holiday/a.jpg");
  const requested: string[] = [];
  page.on("request", (r) => {
    if (r.url().includes(`/photos/${id}/image`)) requested.push(r.url());
  });
  await page.setViewportSize({ width: 1280, height: 800 });
  await pairInUi(page);
  await openAlbum(page, "holiday");

  const tile = page.locator(`.tile[data-photo="${id}"]`);
  await expect(tile.getByRole("img", { name: "holiday/a.jpg: nicht angezeigt", exact: false })).toBeVisible();
  await expect(tile.locator("img")).toHaveCount(0);
  await tile.click();
  const detail = photoDetail(page);
  await expect(
    detail.getByText("Dieses Foto hat den Bilddecoder des Rahmens abstürzen lassen"),
  ).toBeVisible();
  await expect(detail.getByRole("button", { name: "Ausschnitt wählen" })).toHaveCount(0);
  await expect(detail.getByRole("button", { name: "Ausblenden" })).toBeVisible();
  await page.waitForLoadState("networkidle");
  expect(requested).toEqual([]);

  await detail.getByRole("button", { name: "Noch einmal versuchen" }).click();
  await expect(detail.getByRole("button", { name: "Ausschnitt wählen" })).toBeVisible();
  await expect.poll(() => requested.length).toBeGreaterThan(0);
  await expect(tile.locator("img")).toHaveCount(1);
});

test("Album with only quarantined photos: placeholders, no image request", async ({ page }) => {
  await usb("landscape.jpg", "crashed/a.jpg");
  await usb("gps-siena.jpg", "crashed/b.jpg");
  const ids = [await quarantine("crashed/a.jpg"), await quarantine("crashed/b.jpg")];
  const requested: string[] = [];
  page.on("request", (r) => {
    if (ids.some((id) => r.url().includes(`/photos/${id}/image`))) requested.push(r.url());
  });
  await pairInUi(page);
  await openAlbum(page, "crashed");
  const tiles = albumTiles(page, "crashed");
  await expect(tiles).toHaveCount(2);
  await expect(tiles.locator("img")).toHaveCount(0);
  await expect(tiles.getByRole("img", { name: /nicht angezeigt/ })).toHaveCount(2);
  await page.waitForLoadState("networkidle");
  expect(requested).toEqual([]);
});

test("A deleted quarantined photo shows the placeholder in the trash without an image request", async ({
  page,
}) => {
  await usb("landscape.jpg", "holiday/a.jpg");
  await usb("gps-siena.jpg", "holiday/b.jpg");
  const id = await quarantine("holiday/a.jpg");
  const requested: string[] = [];
  page.on("request", (r) => {
    if (r.url().includes("/api/v1/trash/") && r.url().includes("/thumb")) requested.push(r.url());
  });
  await pairInUi(page);
  await openAlbum(page, "holiday");
  await page.locator(`.tile[data-photo="${id}"]`).click();
  await photoDetail(page).getByRole("button", { name: "Löschen" }).click();
  await page.getByRole("dialog").last().getByRole("button", { name: "Löschen" }).click();
  const trash = page.getByRole("region", { name: "Papierkorb" });
  await trash.getByRole("button", { name: "Anzeigen" }).click();
  const row = trash.getByRole("listitem");
  await expect(row.getByRole("img", { name: "a.jpg: nicht angezeigt", exact: false })).toBeVisible();
  await expect(row.locator("img")).toHaveCount(0);
  await expect(row.getByRole("button", { name: "Wiederherstellen" })).toBeVisible();
  await page.waitForLoadState("networkidle");
  expect(requested).toEqual([]);
});
