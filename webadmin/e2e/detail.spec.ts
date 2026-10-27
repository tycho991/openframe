import { expect, test, type Page } from "@playwright/test";
import { detailSettled, many, pairInUi, photoDetail, reset } from "./helpers";

// Spec web-admin: "Detail views by width", "Layout follows width and orientation", "Keyboard operation";
// spec visual-design: "Motion".

test.beforeEach(async () => {
  await reset();
  await many(120);
});

interface VisibleTile {
  id: string;
  path: string;
}

/** The tiles fully inside the window and not covered by the top bar or the navigation, sorted by path. */
function visibleTiles(page: Page): Promise<VisibleTile[]> {
  return page.evaluate(() =>
    [...document.querySelectorAll<HTMLElement>(".tile")]
      .filter((tile) => {
        const r = tile.getBoundingClientRect();
        const centre = document.elementFromPoint(r.left + r.width / 2, r.top + r.height / 2);
        return r.top >= 0 && r.bottom <= innerHeight && centre?.closest(".tile") === tile;
      })
      .map((tile) => ({ id: tile.dataset.photo!, path: tile.querySelector("img")!.alt }))
      .sort((a, b) => a.path.localeCompare(b.path)),
  );
}

/** The visible tiles once the grid shows more than four of them after loading or scrolling. */
async function settledTiles(page: Page): Promise<VisibleTile[]> {
  let tiles: VisibleTile[] = [];
  await expect.poll(async () => (tiles = await visibleTiles(page)).length).toBeGreaterThan(4);
  return tiles;
}

const focusedPhoto = (page: Page) =>
  page.evaluate(() => (document.activeElement as HTMLElement | null)?.dataset.photo ?? null);

const tile = (page: Page, index: number) => page.locator(".tile").nth(index);

/** The tile of one photo; the n-th rendered tile changes while the grid's visible slice moves. */
const tileOf = (page: Page, id: string) => page.locator(`.tile[data-photo="${id}"]`);

/** Scrolls the page to [y]; again on every poll, because the grid may still be loading and the page too short. */
async function scrollGrid(page: Page, y: number) {
  await expect
    .poll(() =>
      page.evaluate((top) => {
        window.scrollTo(0, top);
        return window.scrollY;
      }, y),
    )
    .toBeGreaterThan(y - 50);
}

test("Detail as a panel", async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 800 });
  await pairInUi(page);
  const first = await tile(page, 0).locator("img").getAttribute("alt");
  const second = await tile(page, 1).locator("img").getAttribute("alt");
  await tile(page, 0).click();
  const panel = page.getByRole("complementary");
  await expect(panel).toHaveAccessibleName(first!);
  await expect(page.getByRole("dialog")).toHaveCount(0);

  // The grid stays usable next to the panel: another photo goes into the panel.
  await tile(page, 1).click();
  await expect(panel).toHaveAccessibleName(second!);
  await detailSettled(page);
  const panelBox = (await panel.boundingBox())!;
  const tileBox = (await tile(page, 1).boundingBox())!;
  expect(tileBox.x + tileBox.width).toBeLessThanOrEqual(panelBox.x);
  await expect(tile(page, 1)).toBeInViewport();
});

test("Detail on a phone", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await pairInUi(page);
  await scrollGrid(page, 900);
  const before = await settledTiles(page);

  await tileOf(page, before[2]!.id).click();
  const detail = photoDetail(page);
  await expect(page.getByRole("dialog")).toBeVisible();
  await detailSettled(page);
  const box = (await detail.boundingBox())!;
  expect(box).toEqual({ x: 0, y: 0, width: 390, height: 844 });

  await page.getByRole("button", { name: "Schließen" }).click();
  await expect(page.getByRole("dialog")).toHaveCount(0);
  expect(await visibleTiles(page)).toEqual(before);
});

test("Rotate a tablet", async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 800 });
  await pairInUi(page);
  await scrollGrid(page, 1500);
  const { id, path } = (await settledTiles(page))[3]!;
  await tileOf(page, id).click();
  await expect(page.getByRole("complementary")).toHaveAccessibleName(path);

  await page.setViewportSize({ width: 800, height: 1280 });
  const sheet = page.getByRole("dialog");
  await expect(sheet).toHaveAccessibleName(path);
  await expect(page.getByRole("complementary")).toHaveCount(0);
  await detailSettled(page);
  // A sheet from the bottom, not the whole screen.
  const box = (await sheet.boundingBox())!;
  expect(box.y + box.height).toBeCloseTo(1280, 0);
  expect(box.y).toBeGreaterThan(0);

  await page.getByRole("button", { name: "Schließen" }).click();
  await expect(sheet).toHaveCount(0);
  const back = tileOf(page, id);
  await expect(back).toBeInViewport();
  await expect(back).toBeFocused();
});

test("Unsaved input", async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 800 });
  await pairInUi(page);
  await page.getByRole("navigation").getByRole("link", { name: "Einstellungen" }).click();
  const name = page.getByLabel("Name des Rahmens");
  await name.fill("Wohnzimmer neu");
  for (const width of [800, 390, 1280]) {
    await page.setViewportSize({ width, height: 800 });
    await expect(name, `${width} px`).toHaveValue("Wohnzimmer neu");
  }
});

for (const width of [390, 1280]) {
  test(`Close the photo detail with Escape at ${width} px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 800 });
    await pairInUi(page);
    const opener = tile(page, 2);
    const id = await opener.getAttribute("data-photo");
    await opener.focus();
    await page.keyboard.press("Enter");
    await expect(photoDetail(page)).toBeVisible();
    // The keyboard is in the detail now.
    expect(await focusedPhoto(page)).toBeNull();
    await page.keyboard.press("Escape");
    await expect(photoDetail(page)).toHaveCount(0);
    await expect.poll(() => focusedPhoto(page)).toBe(id);
  });
}

test("Reduced motion", async ({ page }) => {
  await page.emulateMedia({ reducedMotion: "reduce" });
  for (const width of [390, 800, 1280]) {
    await page.setViewportSize({ width, height: 800 });
    if (width === 390) await pairInUi(page);
    await tile(page, 0).click();
    const durations = await photoDetail(page).evaluate((el) => getComputedStyle(el).transitionDuration);
    expect(
      durations.split(", ").every((d) => d === "0s"),
      `${width} px: ${durations}`,
    ).toBe(true);
    await page.getByRole("button", { name: "Schließen" }).click();
    await expect(photoDetail(page)).toHaveCount(0);
  }
  // Without the preference the detail moves.
  await page.emulateMedia({ reducedMotion: "no-preference" });
  await tile(page, 0).click();
  expect(await photoDetail(page).evaluate((el) => getComputedStyle(el).transitionDuration)).toContain(
    "0.25s",
  );
});
