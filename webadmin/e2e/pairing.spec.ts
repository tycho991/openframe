import { expect, test } from "@playwright/test";
import { frameCode, pairInUi, reset } from "./helpers";

test.beforeEach(async () => {
  await reset();
});

test("First visit", async ({ page }) => {
  await page.goto("/");
  await expect(page.getByRole("heading", { name: "Mit dem Rahmen koppeln" })).toBeVisible();
  await expect(page.getByText("Den sechsstelligen Code zeigt der Rahmen an")).toBeVisible();
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
