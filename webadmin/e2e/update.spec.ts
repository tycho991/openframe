import { expect, test, type Page } from "@playwright/test";
import {
  answerInstallDialog,
  installAllowed,
  installedVersion,
  officialBuild,
  pairInUi,
  publishRelease,
  reset,
  usb,
} from "./helpers";

// Spec web-admin: Software update in the interface, Release notes as plain text, Installing explained in the
// interface, Reload after a frame update, Unknown values from the frame (spec app-update; app-auto-update).

const INSTALLED = "0.3.0";

test.beforeEach(async () => {
  await reset();
});

async function openSoftware(page: Page) {
  await page.getByRole("navigation").getByRole("link", { name: "Einstellungen" }).click();
  const section = page.getByRole("group", { name: "Software" });
  await expect(section).toBeVisible();
  return section;
}

/** Publishes 0.4.0 and lets the frame find it through "Check now". */
async function readyUpdate(page: Page, notes = "Schneller Start") {
  await publishRelease("0.4.0", { notes });
  await pairInUi(page);
  const section = await openSoftware(page);
  await section.getByRole("button", { name: "Jetzt prüfen" }).click();
  await expect(section.getByText("Version 0.4.0 ist bereit zur Installation.")).toBeVisible();
  return section;
}

test("Update ready: installed and latest version, the notes and Install on the frame", async ({ page }) => {
  const section = await readyUpdate(page);
  await expect(section.getByText(INSTALLED, { exact: true })).toBeVisible();
  await expect(section.getByText(/^0\.4\.0, veröffentlicht am /)).toBeVisible();
  await expect(section.getByText("Schneller Start")).toBeVisible();
  await expect(section.getByText(/^Letzte Prüfung: .*, Update gefunden$/)).toBeVisible();
  await expect(section.getByRole("button", { name: "Auf dem Rahmen installieren" })).toBeEnabled();
  await expect(section.getByText("Die Installation muss auf dem Rahmen bestätigt werden.")).toBeVisible();
  await expect(section.getByLabel("Automatisch nach Updates suchen")).toBeChecked();
  await expect(section.getByText(/GitHub \(api\.github\.com\)/)).toBeVisible();
});

test("Check now without an update shows the result without reloading", async ({ page }) => {
  await publishRelease(INSTALLED);
  await pairInUi(page);
  const section = await openSoftware(page);
  await expect(section.getByText("Noch nicht geprüft.")).toBeVisible();
  await page.evaluate(() => ((window as unknown as { marker: number }).marker = 1));
  await section.getByRole("button", { name: "Jetzt prüfen" }).click();
  await expect(section.getByText(/^Letzte Prüfung: .*, aktuell$/)).toBeVisible();
  await expect(section.getByText("Kein Update bereit.")).toBeVisible();
  expect(await page.evaluate(() => (window as unknown as { marker?: number }).marker)).toBe(1);
});

test("Own build: updates only for official builds, no check offered", async ({ page }) => {
  await officialBuild(false);
  await pairInUi(page);
  const section = await openSoftware(page);
  await expect(section.getByText(/^Updates gibt es nur für offizielle Builds von openframe/)).toBeVisible();
  await expect(section.getByRole("button", { name: "Jetzt prüfen" })).toHaveCount(0);
  await expect(section.getByRole("button", { name: "Auf dem Rahmen installieren" })).toHaveCount(0);
});

test("Release notes are plain text: markup and links take no effect", async ({ page }) => {
  page.on("dialog", (dialog) => {
    throw new Error(`A dialog opened: ${dialog.message()}`);
  });
  const notes = "<img src=x onerror=alert(1)> [Klick](javascript:alert(2))";
  const section = await readyUpdate(page, notes);
  await expect(section.getByText(notes)).toBeVisible();
  await expect(section.locator("img")).toHaveCount(0);
  await expect(section.locator("a[href^='javascript:']")).toHaveCount(0);
});

test("Install on the frame: the request is sent and the interface says to confirm on the frame", async ({
  page,
}) => {
  const section = await readyUpdate(page);
  const request = page.waitForRequest(
    (r) => r.method() === "POST" && r.url().endsWith("/api/v1/update/install"),
  );
  await section.getByRole("button", { name: "Auf dem Rahmen installieren" }).click();
  await request;
  await expect(
    section.getByText("Der Installationsdialog ist jetzt auf dem Rahmen geöffnet. Bestätige ihn dort."),
  ).toBeVisible();
  // Cancelled on the frame: the update stays ready, and the section says why.
  await answerInstallDialog("cancelled");
  await expect(section.getByText("Die Installation wurde auf dem Rahmen abgebrochen.")).toBeVisible();
  await expect(section.getByRole("button", { name: "Auf dem Rahmen installieren" })).toBeVisible();
});

test("Installing not allowed: the button is disabled with a notice that names the setting", async ({
  page,
}) => {
  await installAllowed(false);
  const section = await readyUpdate(page);
  await expect(section.getByRole("button", { name: "Auf dem Rahmen installieren" })).toBeDisabled();
  await expect(section.getByText(/Apps aus unbekannten Quellen/)).toBeVisible();
});

test("After the installation the interface reloads and shows the new version", async ({ page }) => {
  const section = await readyUpdate(page);
  await section.getByRole("button", { name: "Auf dem Rahmen installieren" }).click();
  await expect(section.getByText(/^Der Installationsdialog ist jetzt/)).toBeVisible();
  const reloaded = page.waitForEvent("load", { timeout: 30_000 });
  await answerInstallDialog("confirmed");
  await reloaded;
  const after = await openSoftware(page);
  await expect(after.getByText("0.4.0", { exact: true })).toBeVisible();
});

test("A version change reloads on the photo page as well", async ({ page }) => {
  await pairInUi(page);
  await expect(page.getByRole("region", { name: "Fotos" })).toBeVisible();
  await page.evaluate(() => ((window as unknown as { marker: number }).marker = 1));
  await installedVersion("9.9.9");
  await expect
    .poll(() => page.evaluate(() => (window as unknown as { marker?: number }).marker ?? 0), {
      timeout: 30_000,
    })
    .toBe(0);
  // The test server's interface is still the old one: no second reload, a notice instead.
  await expect(page.getByText("Der Rahmen wurde auf Version 9.9.9 aktualisiert.")).toBeVisible({
    timeout: 15_000,
  });
});

test("With an unsaved comment the interface keeps the input and offers to reload", async ({ page }) => {
  await usb("landscape.jpg", "a.jpg");
  await pairInUi(page);
  await page.locator(".tile").first().click();
  const field = page.getByLabel("Kommentar");
  await field.fill("Noch nicht gespeichert");
  await installedVersion("9.9.9");
  const banner = page
    .getByRole("status")
    .filter({ hasText: "Der Rahmen wurde auf Version 9.9.9 aktualisiert." });
  await expect(banner).toBeVisible({ timeout: 30_000 });
  await expect(field).toHaveValue("Noch nicht gespeichert");
  await expect(banner.getByRole("button", { name: "Neu laden" })).toBeVisible();
});

test("Unknown values from the frame show as themselves and the section stays usable", async ({ page }) => {
  await page.route("**/api/v1/update", (route) =>
    route.fulfill({
      json: {
        installedVersion: INSTALLED,
        phase: "resting",
        latest: null,
        lastCheck: { at: "2026-11-04T10:00:00Z", result: "postponed" },
        error: { code: "update_gone", message: "x" },
        installAllowed: true,
      },
    }),
  );
  await pairInUi(page);
  const section = await openSoftware(page);
  await expect(section.getByText("resting", { exact: true })).toBeVisible();
  await expect(section.getByText("update_gone", { exact: true })).toBeVisible();
  await expect(section.getByText(/^Letzte Prüfung: .*, postponed$/)).toBeVisible();
  await expect(section.getByRole("button", { name: "Jetzt prüfen" })).toBeEnabled();
});
