import { expect, test, type Page } from "@playwright/test";
import { chooseLanguage, pairInUi, reset, usb } from "./helpers";

test.beforeEach(async () => {
  await reset();
  await usb("landscape.jpg", "holiday/a.jpg");
  await usb("rotated6.jpg", "b.jpg");
});

for (const width of [360, 390, 1280]) {
  test(`No horizontal scrolling at ${width} px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 800 });
    await pairInUi(page);
    for (const nav of ["Fotos", "Alben", "Einstellungen", "Geräte"]) {
      await page.getByRole("link", { name: nav }).click();
      await page.waitForLoadState("networkidle");
      const overflow = await page.evaluate(
        () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
      );
      expect(overflow, `${nav} at ${width} px`).toBeLessThanOrEqual(0);
    }
  });
}

test.describe("German browser", () => {
  test.use({ locale: "de-DE" });
  test("appears in German", async ({ page }) => {
    await page.goto("/");
    await expect(page.getByRole("heading", { name: "Mit dem Rahmen koppeln" })).toBeVisible();
    await expect(page.locator("html")).toHaveAttribute("lang", "de");
  });
});

test.describe("English browser", () => {
  test.use({ locale: "en-US" });
  test("appears in English, choice stays per browser", async ({ page }) => {
    await page.goto("/");
    await expect(page.getByRole("heading", { name: "Pair with the frame" })).toBeVisible();
  });
});

test("Offline on the home network", async ({ page, baseURL }) => {
  const foreign: string[] = [];
  const fonts: string[] = [];
  page.on("request", (r) => {
    if (r.resourceType() === "font") fonts.push(r.url());
    if (!r.url().startsWith(baseURL!) && !r.url().startsWith("data:") && !r.url().startsWith("blob:"))
      foreign.push(r.url());
  });
  await pairInUi(page);
  for (const nav of ["Alben", "Einstellungen", "Geräte", "Fotos"])
    await page.getByRole("link", { name: nav }).click();
  await page.waitForLoadState("networkidle");
  expect(foreign).toEqual([]);
  expect(fonts.some((url) => url.startsWith(baseURL!) && url.endsWith(".woff2"))).toBe(true);
});

test("Invitation and renaming at 360 px without horizontal scrolling", async ({ page }) => {
  await page.setViewportSize({ width: 360, height: 800 });
  await pairInUi(page);
  await page.getByRole("link", { name: "Geräte" }).click();
  await page.getByRole("button", { name: "Gerät einladen" }).click();
  await expect(page.getByRole("img", { name: "QR-Code der Einladung" })).toBeVisible();
  await page.getByRole("listitem").getByRole("button", { name: "Umbenennen" }).click();
  await expect(page.getByLabel("Neuer Name")).toBeVisible();
  const overflow = await page.evaluate(
    () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
  );
  expect(overflow).toBeLessThanOrEqual(0);
});

// Spec web-admin, "Navigation by width": a bar at the bottom, a rail at the left edge, a side navigation with labels.
for (const { width, form } of [
  { width: 390, form: "bar" },
  { width: 800, form: "rail" },
  { width: 1280, form: "side" },
]) {
  test(`Navigation as ${form} at ${width} px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 800 });
    await pairInUi(page);
    const nav = page.getByRole("navigation");
    const box = (await nav.boundingBox())!;
    const current = nav.getByRole("link", { name: "Fotos" });
    await expect(current).toHaveAttribute("aria-current", "page");
    if (form === "bar") {
      expect(box.y + box.height).toBeCloseTo(800, 0);
      expect(box.width).toBe(width);
      expect(box.height).toBeLessThan(120);
    } else {
      expect(box.x).toBe(0);
      expect(box.height).toBe(800);
      expect(box.width).toBeLessThan(form === "rail" ? 120 : 300);
    }
    // The labels: under the icon in bar and rail, next to it in the side navigation.
    const link = (await current.boundingBox())!;
    if (form === "side") expect(link.width).toBeGreaterThan(link.height * 3);
    else expect(link.height).toBeGreaterThan(48);

    await nav.getByRole("link", { name: "Alben" }).click();
    await expect(nav.getByRole("link", { name: "Alben" })).toHaveAttribute("aria-current", "page");
    await expect(current).not.toHaveAttribute("aria-current");
  });
}

const background = (page: Page) => page.evaluate(() => getComputedStyle(document.body).backgroundColor);
const LIGHT_BG = "rgb(246, 245, 242)";
const DARK_BG = "rgb(21, 21, 20)";

test("Theme follows the system without a choice", async ({ page }) => {
  await pairInUi(page);
  await page.emulateMedia({ colorScheme: "dark" });
  await expect.poll(() => background(page)).toBe(DARK_BG);
  await page.emulateMedia({ colorScheme: "light" });
  await expect.poll(() => background(page)).toBe(LIGHT_BG);
  await expect(page.locator("html")).not.toHaveAttribute("data-theme");
});

test("Chosen light theme is kept after reloading under a dark system", async ({ page }) => {
  await page.emulateMedia({ colorScheme: "dark" });
  await pairInUi(page);
  await page.getByRole("button", { name: "Darstellung" }).click();
  await page.getByRole("radio", { name: "Hell" }).check();
  await expect.poll(() => background(page)).toBe(LIGHT_BG);
  await page.reload();
  await expect(page.getByRole("navigation")).toBeVisible();
  await expect(page.locator("html")).toHaveAttribute("data-theme", "light");
  expect(await background(page)).toBe(LIGHT_BG);
});

test("Language in the appearance menu", async ({ page }) => {
  await pairInUi(page);
  await chooseLanguage(page, "English");
  await expect(page.getByRole("navigation").getByRole("link", { name: "Photos" })).toBeVisible();
  await page.reload();
  await expect(page.getByRole("navigation").getByRole("link", { name: "Photos" })).toBeVisible();
  await expect(page.locator("html")).toHaveAttribute("lang", "en");
});

test("Licences of font and icons", async ({ page, request }) => {
  await pairInUi(page);
  await page.getByRole("button", { name: "Darstellung" }).click();
  await expect(page.getByRole("link", { name: /Inter/ })).toHaveAttribute("href", "/licenses/Inter-OFL.txt");
  const inter = await request.get("/licenses/Inter-OFL.txt");
  expect(inter.ok()).toBe(true);
  expect(await inter.text()).toContain("SIL OPEN FONT LICENSE Version 1.1");
  const icons = await request.get("/licenses/MaterialSymbols-LICENSE.txt");
  expect(await icons.text()).toContain("Apache License");
});

test("Texts in Inter", async ({ page }) => {
  await pairInUi(page);
  const loaded = await page.evaluate(async () => {
    await document.fonts.ready;
    return {
      check: document.fonts.check("16px Inter"),
      inter: [...document.fonts].some((f) => f.family.replace(/"/g, "") === "Inter" && f.status === "loaded"),
    };
  });
  expect(loaded).toEqual({ check: true, inter: true });
});
