import { expect, test } from "@playwright/test";
import { frameCode, pairInUi, reset } from "./helpers";

test.beforeEach(async () => {
  await reset();
});

test("Erster Besuch", async ({ page }) => {
  await page.goto("/");
  await expect(page.getByRole("heading", { name: "Mit dem Rahmen koppeln" })).toBeVisible();
  await expect(page.getByText("Den sechsstelligen Code zeigt der Rahmen an")).toBeVisible();
});

test("Wiederkehrender Besuch", async ({ page }) => {
  await pairInUi(page);
  await page.reload();
  await expect(page.getByRole("navigation")).toBeVisible();
  await expect(page.getByText("Noch keine Fotos")).toBeVisible();
});

test("Falscher Code und Code am Rahmen anzeigen", async ({ page }) => {
  await page.goto("/");
  await page.getByRole("button", { name: "Code am Rahmen anzeigen" }).click();
  await expect(page.getByRole("status")).toHaveText("Der Rahmen zeigt jetzt 5 Minuten lang einen Code.");
  const code = await frameCode();
  const wrong = code === "000000" ? "111111" : "000000";
  await page.getByLabel("Code vom Rahmen").fill(wrong);
  await page.getByRole("button", { name: "Koppeln", exact: true }).click();
  await expect(page.getByRole("alert")).toHaveText("Der Code ist falsch.");
});

test("Nach aufgehobener Kopplung zurück zum Dialog", async ({ page }) => {
  await pairInUi(page);
  await page.evaluate(() => localStorage.setItem("openframe.token", "ungültig"));
  await page.reload();
  await expect(page.getByRole("heading", { name: "Mit dem Rahmen koppeln" })).toBeVisible();
});
