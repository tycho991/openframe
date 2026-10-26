import { expect, test } from "@playwright/test";
import { apiAsBrowser, displayAdmin, pairInUi, powerButton, reset } from "./helpers";

// Spec `web-admin`: Display schedule in the interface, Display state and manual switch.

interface DisplayState {
  available: boolean;
  on: boolean;
  cause: string;
  nextChange: string | null;
}

interface Schedule {
  enabled: boolean;
  weekdays: { on: string; off: string };
  weekend: { on: string; off: string };
  wakeMinutes: number;
}

test.beforeEach(async () => {
  await reset();
});

test("Set the schedule", async ({ page }) => {
  await pairInUi(page);
  await page.getByRole("link", { name: "Einstellungen" }).click();
  await page.getByLabel("Display nachts ausschalten").check();
  await page.getByLabel("Samstag und Sonntag: an um").fill("09:00");
  await page.getByRole("button", { name: "Speichern" }).click();
  await expect(page.getByRole("status")).toHaveText("Gespeichert");
  const settings = await apiAsBrowser<{ displaySchedule: Schedule }>(page, "/settings");
  expect(settings.displaySchedule).toEqual({
    enabled: true,
    weekdays: { on: "07:00", off: "22:00" },
    weekend: { on: "09:00", off: "23:00" },
    wakeMinutes: 5,
  });
  // The frame follows the schedule now: on by it, or woken because it was on when the schedule began.
  const display = await apiAsBrowser<DisplayState>(page, "/display");
  expect(["schedule", "wake"]).toContain(display.cause);
});

test("A schedule that is on can be switched off without device administration", async ({ page }) => {
  await pairInUi(page);
  await page.getByRole("link", { name: "Einstellungen" }).click();
  await page.getByLabel("Display nachts ausschalten").check();
  await page.getByRole("button", { name: "Speichern" }).click();
  await expect(page.getByRole("status")).toHaveText("Gespeichert");
  await displayAdmin(false);
  await page.reload();
  const schedule = page.getByLabel("Display nachts ausschalten");
  await expect(schedule).toBeChecked();
  await expect(schedule).toBeEnabled();
  await schedule.uncheck();
  await page.getByRole("button", { name: "Speichern" }).click();
  await expect(page.getByRole("status")).toHaveText("Gespeichert");
  const settings = await apiAsBrowser<{ displaySchedule: Schedule }>(page, "/settings");
  expect(settings.displaySchedule.enabled).toBe(false);
  // Switching it on again needs device administration.
  await expect(schedule).toBeDisabled();
});

test("Invalid time at the field", async ({ page }) => {
  await pairInUi(page);
  await page.getByRole("link", { name: "Einstellungen" }).click();
  const minutes = page.getByLabel("Nach dem Wecken wieder aus (Minuten)");
  await minutes.fill("0");
  await page.getByRole("button", { name: "Speichern" }).click();
  await expect(page.getByRole("alert")).toHaveText("Erlaubt: 1 bis 120");
  await expect(minutes).toHaveAttribute("aria-invalid", "true");
});

test("No device administration", async ({ page }) => {
  await displayAdmin(false);
  await pairInUi(page);
  await page.getByRole("link", { name: "Einstellungen" }).click();
  await expect(
    page.getByText("Der Rahmen darf sein Display noch nicht ausschalten.", { exact: false }),
  ).toBeVisible();
  await expect(page.getByText("„Display-Zeitplan: nicht erlaubt“", { exact: false })).toBeVisible();
  await expect(page.getByLabel("Display nachts ausschalten")).toBeDisabled();
  await expect(page.getByRole("button", { name: "Display ausschalten" })).toBeDisabled();
  // The times can still be set.
  await page.getByLabel("Montag bis Freitag: aus um").fill("23:00");
  await page.getByRole("button", { name: "Speichern" }).click();
  await expect(page.getByRole("status")).toHaveText("Gespeichert");
});

test("Switch off now", async ({ page }) => {
  await pairInUi(page);
  // On all day, so that it is day whenever the test runs.
  await apiAsBrowser(page, "/settings", {
    method: "PATCH",
    body: {
      displaySchedule: {
        enabled: true,
        weekdays: { on: "00:00", off: "23:59" },
        weekend: { on: "00:00", off: "23:59" },
      },
    },
  });
  await page.getByRole("link", { name: "Einstellungen" }).click();
  await expect(page.getByText(/^Nach Zeitplan an bis /)).toBeVisible();
  await page.getByRole("button", { name: "Display ausschalten" }).click();
  await expect(page.getByText(/^Von Hand ausgeschaltet bis .+00:00$/)).toBeVisible();
  await expect(page.getByRole("button", { name: "Display einschalten" })).toBeEnabled();
  const display = await apiAsBrowser<DisplayState>(page, "/display");
  expect(display).toMatchObject({ on: false, cause: "manual" });
  expect(display.nextChange).not.toBeNull();
});

test("Changed on the frame", async ({ page }) => {
  await pairInUi(page);
  await apiAsBrowser(page, "/display", { method: "PUT", body: { on: false } });
  await page.clock.install();
  await page.getByRole("link", { name: "Einstellungen" }).click();
  await expect(page.getByText("Von Hand ausgeschaltet", { exact: true })).toBeVisible();
  // Someone wakes the frame with the power button.
  await powerButton();
  await page.clock.fastForward("00:31");
  await expect(page.getByText("An", { exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: "Display ausschalten" })).toBeVisible();
});
