import { expect, test, type Page } from "@playwright/test";
import { apiAsBrowser, frameCaption, nextcloudPut, nextcloudShare, pairInUi, reset, usb } from "./helpers";

interface PhotoPage {
  items: { id: string; path: string; source: string; comment: string | null }[];
}

interface Settings {
  caption: { albumTitle: boolean; place: boolean; position: string };
}

test.beforeEach(async () => {
  await reset();
});

/** Every request that leaves the frame, for the checks that nothing is loaded from another server. */
function foreignRequests(page: Page, baseURL: string): string[] {
  const foreign: string[] = [];
  page.on("request", (r) => {
    const url = r.url();
    if (!url.startsWith(baseURL) && !url.startsWith("data:") && !url.startsWith("blob:")) foreign.push(url);
  });
  return foreign;
}

async function openPhoto(page: Page, index = 0) {
  await page.locator(".tile").nth(index).click();
  const detail = page.getByRole("dialog");
  await expect(detail).toBeVisible();
  return detail;
}

const photos = (page: Page) => apiAsBrowser<PhotoPage>(page, "/photos");

test.describe("Edit the comment", () => {
  test("Write a comment", async ({ page }) => {
    await usb("landscape.jpg", "holiday/a.jpg");
    await pairInUi(page);
    const detail = await openPhoto(page);
    const field = detail.getByLabel("Kommentar");
    await field.fill("  Omas 80. Geburtstag\n");
    await expect(detail.getByText("19 von 500 Zeichen")).toBeVisible();
    await detail.getByRole("button", { name: "Kommentar speichern" }).click();
    await expect(detail.getByRole("status")).toHaveText("Gespeichert");
    // Shown as the frame stored it, without reloading.
    await expect(field).toHaveValue("Omas 80. Geburtstag");
    expect((await photos(page)).items[0].comment).toBe("Omas 80. Geburtstag");
    // The frame shows it in the caption over the photo.
    await expect.poll(async () => (await frameCaption()).line).toBe("Omas 80. Geburtstag");
    // Closed and opened again, it is still there.
    await detail.getByRole("button", { name: "Schließen" }).click();
    await expect((await openPhoto(page)).getByLabel("Kommentar")).toHaveValue("Omas 80. Geburtstag");
  });

  test("Remove a comment", async ({ page }) => {
    await usb("landscape.jpg", "a.jpg");
    await pairInUi(page);
    const id = (await photos(page)).items[0].id;
    await apiAsBrowser(page, `/photos/${id}`, { method: "PATCH", body: { comment: "Weg damit" } });
    await page.reload();
    const detail = await openPhoto(page);
    const field = detail.getByLabel("Kommentar");
    await expect(field).toHaveValue("Weg damit");
    await field.fill("");
    await detail.getByRole("button", { name: "Kommentar speichern" }).click();
    await expect(detail.getByRole("status")).toHaveText("Gespeichert");
    expect((await photos(page)).items[0].comment).toBeNull();
    await expect.poll(async () => (await frameCaption()).line).toBe("");
  });

  test("Nextcloud photo", async ({ page }) => {
    await nextcloudShare("Kommentar1", { name: "Geteilt" });
    await nextcloudPut("landscape.jpg", "fern.jpg", { share: "Kommentar1" });
    await pairInUi(page);
    await apiAsBrowser(page, "/sources", {
      method: "POST",
      body: { kind: "nextcloud-share", link: "https://cloud.example.org/s/Kommentar1" },
    });
    await expect.poll(async () => (await photos(page)).items.length, { timeout: 20_000 }).toBe(1);
    await page.reload();
    const detail = await openPhoto(page);
    const field = detail.getByLabel("Kommentar");
    await expect(field).toBeEditable();
    await field.fill("Vom Server");
    await detail.getByRole("button", { name: "Kommentar speichern" }).click();
    await expect(detail.getByRole("status")).toHaveText("Gespeichert");
    const photo = (await photos(page)).items[0];
    expect(photo.source).not.toBe("local");
    expect(photo.comment).toBe("Vom Server");
  });

  test("Text too long", async ({ page }) => {
    await usb("landscape.jpg", "a.jpg");
    await pairInUi(page);
    const detail = await openPhoto(page);
    const field = detail.getByLabel("Kommentar");
    const text = "a".repeat(501);
    await field.fill(text);
    await expect(detail.getByText("501 von 500 Zeichen")).toBeVisible();
    await detail.getByRole("button", { name: "Kommentar speichern" }).click();
    await expect(detail.getByRole("alert")).toHaveText("Höchstens 500 Zeichen.");
    await expect(field).toHaveAttribute("aria-invalid", "true");
    await expect(field).toHaveValue(text);
    expect((await photos(page)).items[0].comment).toBeNull();
  });
});

test.describe("Location in the detail view", () => {
  test("Photo with position", async ({ page, baseURL }) => {
    const foreign = foreignRequests(page, baseURL!);
    await usb("gps-siena.jpg", "toskana/siena.jpg");
    await pairInUi(page);
    // The frame set to German, the interface in English.
    await apiAsBrowser(page, "/settings", { method: "PATCH", body: { language: "de" } });
    await page.getByRole("combobox", { name: "Sprache der Oberfläche" }).selectOption("en");
    await page.reload();
    const detail = await openPhoto(page);
    await expect(detail.getByText("Siena, Italien")).toBeVisible();
    await expect(detail.getByText("43.3188, 11.3308")).toBeVisible();
    await page.waitForLoadState("networkidle");
    expect(foreign).toEqual([]);
    // In German the decimals take a comma.
    await detail.getByRole("button", { name: "Close" }).click();
    await page.getByRole("combobox", { name: "Interface language" }).selectOption("de");
    await expect((await openPhoto(page)).getByText("43,3188; 11,3308")).toBeVisible();
  });

  test("Photo without position", async ({ page }) => {
    await usb("landscape.jpg", "a.jpg");
    await pairInUi(page);
    const detail = await openPhoto(page);
    await expect(detail.getByText("Datei", { exact: true })).toBeVisible();
    await expect(detail.getByText("Ort", { exact: true })).toHaveCount(0);
    await expect(detail.getByText("Koordinaten")).toHaveCount(0);
  });
});

test.describe("Caption settings", () => {
  test("Attribution of the place data - settings open", async ({ page, baseURL }) => {
    const foreign = foreignRequests(page, baseURL!);
    // Without internet access: anything for another host fails.
    await page.route(
      (url) => !url.href.startsWith(baseURL!),
      (route) => route.abort("internetdisconnected"),
    );
    await pairInUi(page);
    await page.getByRole("link", { name: "Einstellungen" }).click();
    const place = page.getByLabel("Ort in einer Zeile darunter");
    await expect(place).toBeVisible();
    await expect(page.getByText("Ortsnamen: GeoNames, CC BY 4.0")).toBeVisible();
    // Next to the place switch, which it describes.
    await expect(place).toHaveAccessibleDescription(/GeoNames, CC BY 4\.0/);
    await page.waitForLoadState("networkidle");
    expect(foreign).toEqual([]);
  });

  test("Save the caption settings", async ({ page }) => {
    await pairInUi(page);
    await page.getByRole("link", { name: "Einstellungen" }).click();
    await page.getByLabel("Albumtitel vor dem Kommentar").check();
    await page.getByLabel("Ort in einer Zeile darunter").check();
    const corner = page.locator("#s-caption-pos");
    await expect(corner).toHaveValue("bottom-left");
    // The same corners as the clock, also the clock's own.
    await corner.selectOption({ label: "Unten rechts" });
    await page.getByRole("button", { name: "Speichern" }).click();
    await expect(page.getByRole("status")).toHaveText("Gespeichert");
    const settings = await apiAsBrowser<Settings>(page, "/settings");
    expect(settings.caption).toEqual({ albumTitle: true, place: true, position: "bottom-right" });
  });

  test("An error at the caption's corner is shown at the field", async ({ page }) => {
    await pairInUi(page);
    await page.route("**/api/v1/settings", async (route) => {
      if (route.request().method() !== "PATCH") return route.continue();
      await route.fulfill({
        status: 400,
        contentType: "application/json",
        body: JSON.stringify({
          error: {
            code: "validation_failed",
            message: "Invalid values: caption.position",
            fields: { "caption.position": "Allowed: top-left, top-right, bottom-left, bottom-right" },
          },
        }),
      });
    });
    await page.getByRole("link", { name: "Einstellungen" }).click();
    const corner = page.locator("#s-caption-pos");
    await corner.selectOption({ label: "Oben rechts" });
    await page.getByRole("button", { name: "Speichern" }).click();
    await expect(page.getByRole("alert")).toHaveText("Ungültiger Wert");
    await expect(corner).toHaveAttribute("aria-invalid", "true");
  });
});
