import { expect, test } from "@playwright/test";
import { advanceFrameClock, apiAsBrowser, frameCode, frameSecret, pairInUi, reset } from "./helpers";

test.beforeEach(async () => {
  await reset();
});

test("First visit", async ({ page }) => {
  await page.goto("/");
  await expect(page.getByRole("heading", { name: "Mit dem Rahmen koppeln" })).toBeVisible();
  await expect(page.getByText("Scanne den QR-Code am Rahmen mit der Kamera deines Handys")).toBeVisible();
  await expect(page.getByLabel("Code vom Rahmen")).toBeVisible();
  await expect(page.getByLabel("Name dieses Geräts")).toBeVisible();
});

test("Opened through a pairing link", async ({ page }) => {
  const secret = await frameSecret();
  await page.goto("/#pair=" + secret);
  await expect(page.getByRole("navigation")).toBeVisible();
  await expect(page.getByText(/Dieses Gerät ist als „.+“ gekoppelt\./)).toBeVisible();
  expect(page.url()).not.toContain("#pair");
  expect(page.url()).not.toContain(secret);
  // Reloading does not try to pair again.
  await page.reload();
  await expect(page.getByRole("navigation")).toBeVisible();
  const { clients } = await apiAsBrowser<{ clients: unknown[] }>(page, "/clients");
  expect(clients).toHaveLength(1);
});

test("Rename after pairing through a link", async ({ page }) => {
  await page.goto("/#pair=" + (await frameSecret()));
  await page.getByRole("button", { name: "Umbenennen" }).click();
  await page.getByLabel("Neuer Name").fill("Annas iPhone");
  await page.getByRole("button", { name: "Speichern" }).click();
  await expect(page.getByText("Dieses Gerät ist als „Annas iPhone“ gekoppelt.")).toBeVisible();
});

test("Expired pairing link", async ({ page }) => {
  const secret = await frameSecret();
  await advanceFrameClock(301);
  await page.goto("/#pair=" + secret);
  await expect(page.getByRole("heading", { name: "Mit dem Rahmen koppeln" })).toBeVisible();
  await expect(page.getByRole("alert")).toHaveText(
    "Der Kopplungslink ist abgelaufen. Scanne den QR-Code am Rahmen noch einmal.",
  );
  expect(page.url()).not.toContain("#pair");
});

test("Pairing link used twice", async ({ page, browser }) => {
  const secret = await frameSecret();
  await page.goto("/#pair=" + secret);
  await expect(page.getByRole("navigation")).toBeVisible();
  const other = await browser.newContext({ locale: "de-DE" });
  const second = await other.newPage();
  await second.goto("/#pair=" + secret);
  await expect(second.getByRole("alert")).toHaveText(
    "Der Kopplungslink ist nicht mehr gültig. Scanne den QR-Code am Rahmen noch einmal.",
  );
  await other.close();
});

test("Already paired", async ({ page, baseURL }) => {
  await pairInUi(page);
  const secret = await frameSecret();
  await page.goto("/#pair=" + secret);
  await expect(page.getByRole("navigation")).toBeVisible();
  await expect(page.getByText(/gekoppelt\.$/)).toHaveCount(0);
  expect(page.url()).not.toContain("#pair");
  const { clients } = await apiAsBrowser<{ clients: unknown[] }>(page, "/clients");
  expect(clients).toHaveLength(1);
  // The link stays valid for another device.
  const res = await fetch(baseURL + "/api/v1/pairing", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ secret }),
  });
  expect(res.status).toBe(201);
});

test("Returning visit", async ({ page }) => {
  await pairInUi(page);
  await page.reload();
  await expect(page.getByRole("navigation")).toBeVisible();
  await expect(page.getByText("Noch keine Fotos")).toBeVisible();
});

test("Wrong code and show code on frame", async ({ page }) => {
  await page.goto("/");
  await page.getByRole("button", { name: "Code am Rahmen anzeigen" }).click();
  await expect(page.getByRole("status")).toHaveText("Der Rahmen zeigt jetzt 5 Minuten lang einen Code.");
  const code = await frameCode();
  const wrong = code === "000000" ? "111111" : "000000";
  await page.getByLabel("Code vom Rahmen").fill(wrong);
  await page.getByRole("button", { name: "Koppeln", exact: true }).click();
  await expect(page.getByRole("alert")).toHaveText("Der Code ist falsch.");
});

test("Back to the dialog after the pairing was revoked", async ({ page }) => {
  await pairInUi(page);
  await page.evaluate(() => localStorage.setItem("openframe.token", "invalid"));
  await page.reload();
  await expect(page.getByRole("heading", { name: "Mit dem Rahmen koppeln" })).toBeVisible();
});

test("Whole flow: QR code, invitation, rename, revoke the inviting device", async ({ page, browser }) => {
  // Phone 1 scans the QR code on the frame.
  await page.goto("/#pair=" + (await frameSecret()));
  await expect(page.getByRole("navigation")).toBeVisible();
  await page.getByRole("link", { name: "Geräte" }).click();
  await page.getByRole("button", { name: "Gerät einladen" }).click();
  const link = await page.getByLabel("Link").inputValue();

  // Phone 2 opens the invitation and renames itself.
  const other = await browser.newContext({ locale: "de-DE" });
  const second = await other.newPage();
  await second.goto(link);
  await second.getByRole("button", { name: "Umbenennen" }).click();
  await second.getByLabel("Neuer Name").fill("Zweites Handy");
  await second.getByRole("button", { name: "Speichern" }).click();
  await expect(second.getByText("Dieses Gerät ist als „Zweites Handy“ gekoppelt.")).toBeVisible();

  // Phone 1 sees it, phone 2 revokes phone 1.
  await expect(page.getByRole("listitem").filter({ hasText: "Zweites Handy" })).toBeVisible({
    timeout: 30_000,
  });
  await second.getByRole("link", { name: "Geräte" }).click();
  const first = second.getByRole("listitem").filter({ hasNotText: "Dieses Gerät" });
  await first.getByRole("button", { name: "Kopplung aufheben" }).click();
  await second.getByRole("dialog").getByRole("button", { name: "Kopplung aufheben" }).click();
  await expect(second.getByRole("listitem")).toHaveCount(1);

  // Phone 1 is back at the pairing dialog. That its invitation ends too is covered by InviteTest in :core.
  await page.reload();
  await expect(page.getByRole("heading", { name: "Mit dem Rahmen koppeln" })).toBeVisible();
  await other.close();
});
