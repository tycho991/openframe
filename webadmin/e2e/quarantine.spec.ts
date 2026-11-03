import { expect, test } from "@playwright/test";
import { pairInUi, photoDetail, quarantine, reset, usb } from "./helpers";

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
