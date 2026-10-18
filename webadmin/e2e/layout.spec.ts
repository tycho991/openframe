import { expect, test } from "@playwright/test";
import { pairInUi, reset, usb } from "./helpers";

test.beforeEach(async () => {
  await reset();
  await usb("landscape.jpg", "urlaub/a.jpg");
  await usb("rotated6.jpg", "b.jpg");
});

for (const width of [360, 390, 1280]) {
  test(`Ohne horizontales Scrollen bei ${width} px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 800 });
    await pairInUi(page);
    for (const nav of ["Fotos", "Alben", "Einstellungen", "Geräte"]) {
      await page.getByRole("link", { name: nav }).click();
      await page.waitForLoadState("networkidle");
      const overflow = await page.evaluate(
        () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
      );
      expect(overflow, `${nav} bei ${width} px`).toBeLessThanOrEqual(0);
    }
  });
}

test.describe("Deutscher Browser", () => {
  test.use({ locale: "de-DE" });
  test("erscheint auf Deutsch", async ({ page }) => {
    await page.goto("/");
    await expect(page.getByRole("heading", { name: "Mit dem Rahmen koppeln" })).toBeVisible();
    await expect(page.locator("html")).toHaveAttribute("lang", "de");
  });
});

test.describe("Englischer Browser", () => {
  test.use({ locale: "en-US" });
  test("erscheint auf Englisch, Wahl bleibt pro Browser", async ({ page }) => {
    await page.goto("/");
    await expect(page.getByRole("heading", { name: "Pair with the frame" })).toBeVisible();
  });
});

test("Offline im Heimnetz", async ({ page, baseURL }) => {
  const foreign: string[] = [];
  page.on("request", (r) => {
    if (!r.url().startsWith(baseURL!) && !r.url().startsWith("data:") && !r.url().startsWith("blob:"))
      foreign.push(r.url());
  });
  await pairInUi(page);
  for (const nav of ["Alben", "Einstellungen", "Geräte", "Fotos"])
    await page.getByRole("link", { name: nav }).click();
  await page.waitForLoadState("networkidle");
  expect(foreign).toEqual([]);
});
