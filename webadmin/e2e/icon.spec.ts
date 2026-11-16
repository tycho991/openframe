import { expect, test, type Page } from "@playwright/test";
import { pairInUi, reset } from "./helpers";

test.beforeEach(async () => {
  await reset();
});

// Spec visual-design, "Icon in the web UI", scenario "Browser tab".
test("The page links the app icon as its favicon", async ({ page, request }) => {
  await page.goto("/");
  await expect(page.locator('link[rel="icon"]')).toHaveAttribute("href", "/favicon.svg");
  const icon = await request.get("/favicon.svg");
  expect(icon.status()).toBe(200);
  expect(icon.headers()["content-type"]).toContain("image/svg+xml");
});

// Spec visual-design, "Icon in the web UI", scenario "Bookmark on the home screen".
test("The page links the app icon for the home screen", async ({ page, request }) => {
  await page.goto("/");
  await expect(page.locator('link[rel="apple-touch-icon"]')).toHaveAttribute("href", "/apple-touch-icon.png");
  const icon = await request.get("/apple-touch-icon.png");
  expect(icon.status()).toBe(200);
  expect(icon.headers()["content-type"]).toBe("image/png");
});

/** The brand mark that is visible at this width (top bar on a phone, navigation on a wide screen) has loaded. */
async function expectBrandMark(page: Page) {
  const brand = page.locator(".brand").filter({ visible: true }).first();
  const logo = brand.locator("img.logo");
  await expect(logo).toBeVisible();
  await expect(logo).toHaveAttribute("src", "/favicon.svg");
  await expect
    .poll(() => logo.evaluate((img: HTMLImageElement) => img.complete && img.naturalWidth > 0))
    .toBe(true);
  await expect(brand.locator(".name")).toBeVisible();
}

// Spec visual-design, "Icon in the web UI", scenario "Header".
for (const width of [390, 1280]) {
  test(`The header shows the app icon next to the frame's name in both themes at ${width} px`, async ({
    page,
  }) => {
    await page.setViewportSize({ width, height: 800 });
    await page.emulateMedia({ colorScheme: "light" });
    await pairInUi(page);
    await expectBrandMark(page);
    await page.emulateMedia({ colorScheme: "dark" });
    await expectBrandMark(page);
  });
}
