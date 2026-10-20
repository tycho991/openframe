import { expect, test } from "@playwright/test";
import path from "node:path";
import { apiAsBrowser, FIXTURES, many, pairInUi, reset, usb } from "./helpers";

interface Page {
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
  await expect(page.getByText("10000 Fotos")).toBeVisible();
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
  await expect(page.locator(".tile")).toHaveCount(4);
  await page.getByRole("button", { name: "Auswählen" }).click();
  for (const i of [0, 1, 2]) await page.locator(".tile").nth(i).click();
  await page.getByRole("toolbar").getByRole("button", { name: "Ausblenden" }).click();
  await expect(page.locator(".tile .badge")).toHaveCount(3);
  const res = await apiAsBrowser<Page>(page, "/photos");
  expect(res.items.filter((p) => !p.visible)).toHaveLength(3);
});

test("Confirm delete", async ({ page }) => {
  await usb("landscape.jpg", "weg.jpg");
  await usb("landscape.jpg", "bleibt.jpg");
  await pairInUi(page);
  await page.locator(".tile").first().click();
  await page.getByRole("button", { name: "Löschen" }).click();
  await expect(page.getByText("Dieses Foto endgültig löschen?")).toBeVisible();
  // Cancel deletes nothing.
  await page.getByRole("button", { name: "Abbrechen" }).click();
  expect((await apiAsBrowser<Page>(page, "/photos")).total).toBe(2);
  await page.getByRole("button", { name: "Löschen" }).click();
  await page.getByRole("dialog").last().getByRole("button", { name: "Löschen" }).click();
  await expect(page.locator(".count")).toHaveText("1 Foto");
});

test("Partial failure on upload", async ({ page }) => {
  await pairInUi(page);
  await page.getByRole("button", { name: "Hochladen", exact: true }).click();
  const files = ["landscape.jpg", "rotated6.jpg", "image.png", "image.webp", "note.txt"].map((f) =>
    path.join(FIXTURES, f),
  );
  await page.getByTestId("upload-input").setInputFiles(files);
  await page.getByLabel("Album").last().selectOption("__new__");
  await page.getByLabel("Name des neuen Albums").fill("family");
  await page.getByRole("button", { name: "Hochladen starten" }).click();
  await expect(page.getByText("4 hochgeladen, 1 fehlgeschlagen")).toBeVisible();
  await expect(page.getByRole("alert")).toHaveText("Kein unterstütztes Format");
  await page.getByRole("button", { name: "Fertig" }).click();
  await expect(page.locator(".count")).toHaveText("4 Fotos");
  const res = await apiAsBrowser<Page>(page, "/photos?album=family");
  expect(res.total).toBe(4);
});

test("Photo over USB appears without reloading", async ({ page }) => {
  await pairInUi(page);
  await expect(page.getByText("Noch keine Fotos")).toBeVisible();
  await usb("landscape.jpg", "neu.jpg", { scan: false });
  await expect(page.locator(".tile")).toHaveCount(1, { timeout: 30_000 });
});
