import { expect, test } from "@playwright/test";
import { apiAsBrowser, brightnessPermission, frameBrightness, lightSensor, pairInUi, reset } from "./helpers";

// Spec `web-admin`: Brightness in the settings, Display schedule in the interface (dim time), Display state and manual
// switch (brightness now).

interface Brightness {
  mode: string;
  level: number;
}

test.beforeEach(async () => {
  await reset();
});

test("Fixed level", async ({ page }) => {
  await pairInUi(page);
  await page.getByRole("link", { name: "Einstellungen" }).click();
  const mode = page.getByRole("combobox", { name: "Helligkeit" });
  const level = page.getByLabel("Stufe (%)");
  await expect(level).toBeDisabled();
  await mode.selectOption("manual");
  await level.fill("40");
  await page.getByRole("button", { name: "Speichern" }).click();
  await expect(page.getByRole("status")).toHaveText("Gespeichert");
  const settings = await apiAsBrowser<{ brightness: Brightness }>(page, "/settings");
  expect(settings.brightness).toEqual({ mode: "manual", level: 40 });
  expect(await frameBrightness()).toBe("40 manual");
});

test("Invalid level at the field", async ({ page }) => {
  await pairInUi(page);
  await page.getByRole("link", { name: "Einstellungen" }).click();
  await page.getByRole("combobox", { name: "Helligkeit" }).selectOption("manual");
  const level = page.getByLabel("Stufe (%)");
  await level.fill("0");
  await page.getByRole("button", { name: "Speichern" }).click();
  await expect(page.getByRole("alert")).toHaveText("Erlaubt: 1 bis 100");
  await expect(level).toHaveAttribute("aria-invalid", "true");
});

test("No permission", async ({ page }) => {
  await brightnessPermission(false);
  await pairInUi(page);
  await page.getByRole("link", { name: "Einstellungen" }).click();
  const mode = page.getByRole("combobox", { name: "Helligkeit" });
  await expect(mode.getByRole("option", { name: "Feste Helligkeit" })).toBeDisabled();
  await expect(mode.getByRole("option", { name: "Automatisch (Lichtsensor)" })).toBeDisabled();
  await expect(mode.getByRole("option", { name: "Einstellung von Android" })).toBeEnabled();
  await expect(
    page.getByText("Der Rahmen darf seine Helligkeit noch nicht ändern.", { exact: false }).first(),
  ).toBeVisible();
  await expect(
    page.getByText("„Helligkeitssteuerung: nicht erlaubt“", { exact: false }).first(),
  ).toBeVisible();
  await expect(page.getByRole("slider", { name: "Helligkeit jetzt" })).toBeDisabled();
});

test("No light sensor", async ({ page }) => {
  await lightSensor(false);
  await pairInUi(page);
  await page.getByRole("link", { name: "Einstellungen" }).click();
  const mode = page.getByRole("combobox", { name: "Helligkeit" });
  await expect(mode.getByRole("option", { name: "Feste Helligkeit" })).toBeEnabled();
  await expect(mode.getByRole("option", { name: "Automatisch (Lichtsensor)" })).toHaveCount(0);
});

test("Dim in the evening", async ({ page }) => {
  await pairInUi(page);
  await page.getByRole("link", { name: "Einstellungen" }).click();
  const weekdays = page.getByLabel("Montag bis Freitag: dimmen ab");
  // After the off time of the default window, 22:00: rejected at the field.
  await weekdays.fill("23:00");
  await page.getByRole("button", { name: "Speichern" }).click();
  await expect(page.getByRole("alert")).toHaveText("Ungültiger Wert");
  await expect(weekdays).toHaveAttribute("aria-invalid", "true");
  await weekdays.fill("20:30");
  await page.getByLabel("Gedimmte Helligkeit (%)").fill("25");
  await page.getByRole("button", { name: "Speichern" }).click();
  await expect(page.getByRole("status")).toHaveText("Gespeichert");
  const settings = await apiAsBrowser<{
    displaySchedule: { weekdays: { dim: string | null }; weekend: { dim: string | null }; dimLevel: number };
  }>(page, "/settings");
  expect(settings.displaySchedule.weekdays.dim).toBe("20:30");
  expect(settings.displaySchedule.weekend.dim).toBeNull();
  expect(settings.displaySchedule.dimLevel).toBe(25);
  // Emptied, the field removes the dim time.
  await weekdays.fill("");
  await page.getByRole("button", { name: "Speichern" }).click();
  await expect(page.getByRole("status")).toHaveText("Gespeichert");
  const after = await apiAsBrowser<{ displaySchedule: { weekdays: { dim: string | null } } }>(
    page,
    "/settings",
  );
  expect(after.displaySchedule.weekdays.dim).toBeNull();
});

test("Brightness now", async ({ page }) => {
  await pairInUi(page);
  await page.getByRole("link", { name: "Einstellungen" }).click();
  const slider = page.getByRole("slider", { name: "Helligkeit jetzt" });
  await expect(slider).toHaveValue("50");
  await slider.fill("60");
  await expect(page.getByText("60 %", { exact: true })).toBeVisible();
  await expect.poll(frameBrightness).toBe("60 manual");
  // Outside a dim period the level becomes the fixed brightness, and the settings follow.
  await expect(page.getByRole("combobox", { name: "Helligkeit" })).toHaveValue("manual");
  await expect(page.getByLabel("Stufe (%)")).toHaveValue("60");
});

test("One request per drag", async ({ page }) => {
  await pairInUi(page);
  await page.getByRole("link", { name: "Einstellungen" }).click();
  const slider = page.getByRole("slider", { name: "Helligkeit jetzt" });
  await expect(slider).toHaveValue("50");
  const requests: string[] = [];
  page.on("request", (r) => {
    if (r.method() === "PUT" && r.url().endsWith("/api/v1/display")) requests.push(r.postData() ?? "");
  });
  await slider.scrollIntoViewIfNeeded();
  const box = (await slider.boundingBox())!;
  const y = box.y + box.height / 2;
  await page.mouse.move(box.x + box.width / 2, y);
  await page.mouse.down();
  for (let i = 1; i <= 10; i++) await page.mouse.move(box.x + box.width * (0.5 + i * 0.03), y);
  await page.mouse.up();
  await expect.poll(() => requests.length).toBe(1);
  const sent = (JSON.parse(requests[0]!) as { brightness: number }).brightness;
  expect(sent).toBeGreaterThan(70);
  await expect.poll(frameBrightness).toBe(`${sent} manual`);
});
