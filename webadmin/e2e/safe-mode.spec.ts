import { expect, test } from "@playwright/test";
import { pairInUi, reset, safeMode } from "./helpers";

// Spec web-admin: "Safe mode notice"; kiosk-host: "Leaving safe mode".

test.beforeEach(async () => {
  await reset();
});

test("Safe mode: the banner, Start normally, and the banner gone once the frame runs normally", async ({
  page,
}) => {
  await pairInUi(page);
  await expect(page.getByText("Sicherer Modus")).toHaveCount(0);

  await safeMode(true);
  await page.reload();
  const banner = page.getByRole("status").filter({ hasText: "Sicherer Modus" });
  await expect(banner).toBeVisible();
  await expect(banner).toContainText("keine Fotos mehr");

  const exit = page.waitForRequest(
    (r) => r.method() === "POST" && r.url().endsWith("/api/v1/safe-mode/exit"),
  );
  await banner.getByRole("button", { name: "Normal starten" }).click();
  await exit;
  await expect(banner).toBeHidden({ timeout: 15_000 });
  // Still paired after the restart.
  await expect(page.getByRole("navigation")).toBeVisible();
});
