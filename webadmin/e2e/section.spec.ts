import { expect, test, type Locator, type Page } from "@playwright/test";
import { apiAsBrowser, pairInUi, reset, usb } from "./helpers";

// Spec `web-admin`: Edit photos (detail view as the frame shows the photo), Choose a section, Section editor on the
// phone. The test server's screen is 1024×600 in `landscape`; `rotated6.jpg` is a portrait photo of 300×400.

interface Photo {
  id: string;
  crop: { x: number; y: number; zoom: number } | null;
  presentation: { x: number; y: number; width: number; height: number; zoom: number };
}

test.beforeEach(async () => {
  await reset();
});

async function onlyPhoto(page: Page): Promise<Photo> {
  const res = await apiAsBrowser<{ items: Photo[] }>(page, "/photos");
  return res.items[0]!;
}

async function openDetail(page: Page) {
  await usb("rotated6.jpg", "hoch.jpg");
  await pairInUi(page);
  await page.locator(".tile").first().click();
  await expect(page.getByRole("dialog", { name: "hoch.jpg" })).toBeVisible();
}

async function box(locator: Locator) {
  const b = await locator.boundingBox();
  if (!b) throw new Error("not visible");
  return b;
}

/** Width of the shown photo as a share of the frame box in the detail view. */
async function shownWidth(page: Page): Promise<number> {
  const frame = await box(page.locator(".frame"));
  const img = await box(page.locator(".frame img"));
  return img.width / frame.width;
}

async function openEditor(page: Page) {
  await page.getByRole("button", { name: "Ausschnitt wählen" }).click();
  await expect(page.getByRole("dialog", { name: "Ausschnitt wählen" })).toBeVisible();
  // The viewport has its size once the editor is laid out.
  await expect.poll(async () => (await box(viewport(page))).width).toBeGreaterThan(100);
}

const viewport = (page: Page) =>
  page.getByRole("application", { name: "Ausschnitt, wie der Rahmen ihn zeigt" });

test("Detail view of a portrait photo", async ({ page }) => {
  await openDetail(page);
  const frame = await box(page.locator(".frame"));
  expect(frame.width / frame.height).toBeCloseTo(1024 / 600, 2);
  // `auto`: 300×400 at scale 2 shows 600 of 1024 pixels in width and three quarters of the height.
  const img = await box(page.locator(".frame img"));
  expect(img.width / frame.width).toBeCloseTo(600 / 1024, 2);
  expect(img.height / frame.height).toBeCloseTo(1, 2);
  // Borders of equal width left and right.
  expect(img.x - frame.x).toBeCloseTo(frame.x + frame.width - (img.x + img.width), 0);
  const photo = await onlyPhoto(page);
  expect(photo.presentation.height).toBeCloseTo(0.75, 4);
  await expect(page.getByText("Nach Füllmodus: Automatisch")).toBeVisible();
});

test("Switch the fill mode with the detail view open", async ({ page }) => {
  await openDetail(page);
  expect(await shownWidth(page)).toBeCloseTo(600 / 1024, 2);
  await apiAsBrowser(page, "/settings", { method: "PATCH", body: { fillMode: "fill" } });
  // Seen with the next poll of the versions.
  await expect.poll(() => shownWidth(page), { timeout: 30_000 }).toBeCloseTo(1, 2);
});

test("Move the section", async ({ page }) => {
  await openDetail(page);
  await openEditor(page);
  const v = await box(viewport(page));
  // Drag the portrait photo down: the upper part comes into view.
  await page.mouse.move(v.x + v.width / 2, v.y + v.height / 2);
  await page.mouse.down();
  await page.mouse.move(v.x + v.width / 2, v.y + v.height / 2 + v.height / 3, { steps: 8 });
  await page.mouse.up();
  await expect(viewport(page)).toHaveAttribute("data-y", "0");
  await page
    .getByRole("dialog", { name: "Ausschnitt wählen" })
    .getByRole("button", { name: "Speichern" })
    .click();
  await expect(page.getByRole("dialog", { name: "Ausschnitt wählen" })).toBeHidden();
  const photo = await onlyPhoto(page);
  expect(photo.crop).not.toBeNull();
  expect(photo.presentation.y).toBeCloseTo(0, 4);
  await expect(page.getByText("Von Hand gewählt")).toBeVisible();
  // The detail view shows the same part: its display URL is the one of the new section.
  const src = await page.locator(".frame img").getAttribute("src");
  const listed = await apiAsBrowser<{ items: { urls: { display: string } }[] }>(page, "/photos");
  expect(new URL(src!, page.url()).searchParams.get("p")).toBe(
    new URL(listed.items[0]!.urls.display, page.url()).searchParams.get("p"),
  );
});

test("Zoom out to borders", async ({ page }) => {
  await openDetail(page);
  await openEditor(page);
  const zoomOut = page.getByRole("button", { name: "Verkleinern" });
  for (let i = 0; i < 10 && (await zoomOut.isEnabled()); i++) await zoomOut.click();
  await expect(zoomOut).toBeDisabled();
  // The wheel does not zoom out further either.
  await viewport(page).hover();
  await page.mouse.wheel(0, 500);
  await expect(viewport(page)).toHaveAttribute("data-height", "1");
  await page
    .getByRole("dialog", { name: "Ausschnitt wählen" })
    .getByRole("button", { name: "Speichern" })
    .click();
  await expect(page.getByRole("dialog", { name: "Ausschnitt wählen" })).toBeHidden();
  const photo = await onlyPhoto(page);
  expect(photo.presentation).toMatchObject({ x: 0, y: 0, width: 1, height: 1 });
  // In the detail view: the whole photo, 300×400 at 600 pixels high is 450 of 1024 wide.
  await expect.poll(() => shownWidth(page)).toBeCloseTo(450 / 1024, 2);
});

test("Back to automatic", async ({ page }) => {
  await usb("rotated6.jpg", "hoch.jpg");
  await pairInUi(page);
  const id = (await onlyPhoto(page)).id;
  await apiAsBrowser(page, `/photos/${id}`, { method: "PATCH", body: { crop: { x: 0.5, y: 0, zoom: 1 } } });
  await page.reload();
  await page.locator(".tile").first().click();
  await expect(page.getByText("Von Hand gewählt")).toBeVisible();
  await openEditor(page);
  await page.getByRole("button", { name: "Automatisch" }).click();
  await page
    .getByRole("dialog", { name: "Ausschnitt wählen" })
    .getByRole("button", { name: "Speichern" })
    .click();
  await expect(page.getByRole("dialog", { name: "Ausschnitt wählen" })).toBeHidden();
  expect((await onlyPhoto(page)).crop).toBeNull();
  await expect(page.getByText("Nach Füllmodus: Automatisch")).toBeVisible();
});

test("Cancel", async ({ page }) => {
  await openDetail(page);
  await openEditor(page);
  const v = await box(viewport(page));
  await page.mouse.move(v.x + v.width / 2, v.y + v.height / 2);
  await page.mouse.down();
  await page.mouse.move(v.x + v.width / 2, v.y + v.height, { steps: 5 });
  await page.mouse.up();
  await page.getByRole("button", { name: "Abbrechen" }).click();
  await expect(page.getByRole("dialog", { name: "Ausschnitt wählen" })).toBeHidden();
  expect((await onlyPhoto(page)).crop).toBeNull();
});

test("Section editor on the phone", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await openDetail(page);
  await openEditor(page);
  const v = await box(viewport(page));
  // The viewport takes the width of the screen, apart from the dialog's margins.
  expect(v.width).toBeGreaterThan(390 - 60);
  expect(v.x).toBeGreaterThanOrEqual(0);
  expect(v.x + v.width).toBeLessThanOrEqual(390);
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390);
  for (const button of await page
    .getByRole("dialog", { name: "Ausschnitt wählen" })
    .getByRole("button")
    .all()) {
    const b = await box(button);
    expect(b.width).toBeGreaterThanOrEqual(44);
    expect(b.height).toBeGreaterThanOrEqual(44);
    expect(b.x + b.width).toBeLessThanOrEqual(390);
  }
  const touch = (type: string, id: number, x: number, y: number) =>
    viewport(page).dispatchEvent(type, {
      pointerId: id,
      pointerType: "touch",
      isPrimary: id === 1,
      clientX: v.x + x,
      clientY: v.y + y,
      bubbles: true,
    });
  // One finger moves the photo down.
  const yBefore = Number(await viewport(page).getAttribute("data-y"));
  await touch("pointerdown", 1, v.width / 2, v.height / 2);
  await touch("pointermove", 1, v.width / 2, v.height / 2 + 40);
  await touch("pointerup", 1, v.width / 2, v.height / 2 + 40);
  await expect.poll(async () => Number(await viewport(page).getAttribute("data-y"))).toBeLessThan(yBefore);
  // Two fingers spread apart zoom in.
  const zoomBefore = Number(await viewport(page).getAttribute("data-zoom"));
  await touch("pointerdown", 1, v.width / 2 - 20, v.height / 2);
  await touch("pointerdown", 2, v.width / 2 + 20, v.height / 2);
  await touch("pointermove", 2, v.width / 2 + 80, v.height / 2);
  await touch("pointerup", 2, v.width / 2 + 80, v.height / 2);
  await touch("pointerup", 1, v.width / 2 - 20, v.height / 2);
  await expect
    .poll(async () => Number(await viewport(page).getAttribute("data-zoom")))
    .toBeGreaterThan(zoomBefore);
});
