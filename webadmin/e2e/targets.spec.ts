import { expect, test, type Page } from "@playwright/test";
import {
  albumSwitch,
  foldButton,
  nextcloudPut,
  nextcloudShare,
  openAllAlbums,
  pairInUi,
  reset,
  usb,
} from "./helpers";

// Spec web-admin, "Touch targets": every control offers at least 44 × 44 px, its own box or the label around it.

test.beforeEach(async () => {
  await reset();
  await usb("landscape.jpg", "holiday/a.jpg");
  await usb("rotated6.jpg", "holiday/winter/b.jpg");
});

/** Visible controls whose own box and whose label's box are both smaller than 44 × 44 px. */
function smallTargets(page: Page): Promise<string[]> {
  return page.evaluate(() => {
    const MIN = 44 - 0.5;
    const big = (el: Element) => {
      const r = el.getBoundingClientRect();
      return r.width >= MIN && r.height >= MIN;
    };
    const visible = (el: Element) => {
      const r = el.getBoundingClientRect();
      // Visually hidden controls (`sr-only`, 1 × 1 px) are not touched; a visible control opens them.
      return r.width > 1 && r.height > 1 && getComputedStyle(el).visibility !== "hidden";
    };
    const describe = (el: Element) => {
      const r = el.getBoundingClientRect();
      const name =
        el.getAttribute("aria-label") ??
        (el as HTMLInputElement).labels?.[0]?.textContent ??
        el.textContent ??
        "";
      return `${el.tagName.toLowerCase()}${el.getAttribute("type") ? `[${el.getAttribute("type")}]` : ""} "${name.trim().slice(0, 40)}" ${Math.round(r.width)}×${Math.round(r.height)}`;
    };
    const controls = document.querySelectorAll(
      'button, a[href], input:not([type="hidden"]), select, textarea, [role="button"], [role="switch"]',
    );
    return [...controls]
      .filter(visible)
      .filter((el) => {
        if (big(el)) return false;
        const labels = [...((el as HTMLInputElement).labels ?? []), el.closest("label")].filter(
          (l): l is HTMLLabelElement => l !== null,
        );
        return !labels.some(big);
      })
      .map(describe);
  });
}

async function audit(page: Page, where: string) {
  await page.waitForLoadState("networkidle");
  expect(await smallTargets(page), where).toEqual([]);
}

test("Controls on a phone at 360 px", async ({ page }) => {
  await page.setViewportSize({ width: 360, height: 800 });
  await pairInUi(page);
  const nav = page.getByRole("navigation");

  // The overview with its nested headings open: fold buttons and switches of every level.
  await openAllAlbums(page);
  await expect(foldButton(page, "holiday/winter")).toBeVisible();
  await expect(page.getByRole("main").getByRole("button", { name: /a\.jpg/ })).toBeVisible();
  await audit(page, "Fotos");
  await page.getByRole("button", { name: "Auswählen" }).click();
  await page
    .getByRole("main")
    .getByRole("button", { name: /a\.jpg/ })
    .click();
  await expect(page.getByRole("toolbar")).toBeVisible();
  await audit(page, "Fotos, Auswahl");
  await page.getByRole("button", { name: "Fertig" }).click();
  await page.getByRole("button", { name: "Hochladen" }).click();
  await expect(page.getByRole("dialog")).toBeVisible();
  await audit(page, "Hochladen");
  await page.keyboard.press("Escape");

  await page.getByRole("button", { name: "Darstellung" }).click();
  await expect(page.getByRole("radio", { name: "Hell" })).toBeVisible();
  await audit(page, "Darstellung");
  await page.keyboard.press("Escape");

  await nav.getByRole("link", { name: "Quellen" }).click();
  await expect(page.getByRole("heading", { name: "Quelle hinzufügen" })).toBeVisible();
  await nextcloudShare("Ziele1", { name: "Ausflug" });
  await nextcloudPut("landscape.jpg", "x.jpg", { share: "Ziele1" });
  await page.getByLabel("Link der Freigabe").fill("https://cloud.example.org/s/Ziele1");
  await page.getByRole("button", { name: "Hinzufügen" }).click();
  await expect(page.getByRole("region", { name: "Ausflug" })).toBeVisible();
  await audit(page, "Quellen");

  await nav.getByRole("link", { name: "Einstellungen" }).click();
  await expect(page.getByLabel("Uhr anzeigen")).toBeVisible();
  await audit(page, "Einstellungen");

  await nav.getByRole("link", { name: "Geräte" }).click();
  await expect(page.getByRole("listitem")).toHaveCount(1);
  await audit(page, "Geräte");
  await page.getByRole("button", { name: "Gerät einladen" }).click();
  await expect(page.getByRole("img", { name: "QR-Code der Einladung" })).toBeVisible();
  await audit(page, "Einladung");
  await page.getByRole("listitem").getByRole("button", { name: "Umbenennen" }).click();
  await expect(page.getByLabel("Neuer Name")).toBeVisible();
  await audit(page, "Umbenennen");
});

// Spec web-admin, "Album headings for every screen and input".
test("Fold with the keyboard: fold buttons and switches by Tab, Enter and Space", async ({ page }) => {
  await page.setViewportSize({ width: 360, height: 800 });
  await pairInUi(page);
  const fold = foldButton(page, "holiday");
  await expect(fold).toHaveAttribute("aria-expanded", "false");
  const focusedIs = (target: typeof fold) => target.evaluate((el) => el === document.activeElement);
  for (let i = 0; i < 40 && !(await focusedIs(fold)); i++) await page.keyboard.press("Tab");
  await expect(fold).toBeFocused();

  await page.keyboard.press("Enter");
  await expect(fold).toHaveAttribute("aria-expanded", "true");
  await expect(fold).toBeFocused();
  await page.keyboard.press("Space");
  await expect(fold).toHaveAttribute("aria-expanded", "false");
  await page.keyboard.press("Enter");
  await expect(foldButton(page, "holiday/winter")).toBeVisible();

  // The switch comes next to the heading, and works with Space and Enter.
  const toggle = albumSwitch(page, "holiday");
  await page.keyboard.press("Tab");
  await expect(toggle).toBeFocused();
  await page.keyboard.press("Space");
  await expect(toggle).not.toBeChecked();
  await expect(toggle).toBeFocused();
  // One switch at a time: the next press counts once the frame has answered.
  await expect(toggle).toHaveAttribute("aria-disabled", "false");
  await page.keyboard.press("Enter");
  await expect(toggle).toBeChecked();
  await expect(toggle).toBeFocused();
});
