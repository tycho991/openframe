import { expect, test, type Page } from "@playwright/test";
import {
  nextcloudApprove,
  nextcloudFail,
  nextcloudPut,
  nextcloudRemove,
  pairInUi,
  reset,
  usb,
} from "./helpers";

test.beforeEach(async () => {
  await reset();
});

/** Connects through the login flow like a user: address, approval in the other tab, back in the interface. */
async function connect(page: Page) {
  await pairInUi(page);
  await page.getByRole("link", { name: "Nextcloud" }).click();
  await expect(page.getByRole("heading", { name: "Nextcloud" })).toBeVisible();
  await page.getByLabel("Adresse des Servers").fill("http://127.0.0.1:8090");
  // A plain http address is called out.
  await expect(page.getByText("Diese Adresse nutzt http")).toBeVisible();
  const popup = page.waitForEvent("popup").catch(() => null);
  await page.getByRole("button", { name: "Verbinden" }).click();
  await expect(page.getByText("Warte auf deine Zustimmung")).toBeVisible();
  await popup;
  await nextcloudApprove();
  await expect(page.getByText("Verbunden mit http://127.0.0.1:8090 als Anna.")).toBeVisible({
    timeout: 15_000,
  });
  // No folder is chosen yet: nothing syncs, and the interface says so.
  await expect(page.getByText("Wähle den Ordner")).toBeVisible();
  await expect(page.getByRole("button", { name: "Jetzt abgleichen" })).toBeDisabled();
  await page.getByLabel("Ordner auf dem Server").fill("/");
  await page.getByRole("button", { name: "Speichern" }).click();
  await expect(page.getByText("Gespeichert")).toBeVisible();
  await expect(page.getByRole("button", { name: "Jetzt abgleichen" })).toBeEnabled();
}

test("Connect, first sync, a photo deleted on the server, a failing sync and disconnect", async ({
  page,
}) => {
  await usb("image.png", "meins.png");
  await nextcloudPut("landscape.jpg", "Urlaub/strand.jpg", "e1");
  await nextcloudPut("landscape.jpg", "Urlaub/berg.jpg", "e2");
  await connect(page);

  // First sync, on request.
  await expect(page.getByText("Noch kein Abgleich.")).toBeVisible();
  await page.getByRole("button", { name: "Jetzt abgleichen" }).click();
  await expect(page.getByText(/2 neu, 0 geändert, 0 entfernt/)).toBeVisible({ timeout: 20_000 });

  // The photos are in the overview next to the local one.
  await page.getByRole("link", { name: "Fotos" }).click();
  await expect(page.locator(".tile")).toHaveCount(3);

  // Deleted on the server: gone after the next sync.
  await nextcloudRemove("Urlaub/berg.jpg");
  await page.getByRole("link", { name: "Nextcloud" }).click();
  await page.getByRole("button", { name: "Jetzt abgleichen" }).click();
  await expect(page.getByText(/0 neu, 0 geändert, 1 entfernt/)).toBeVisible({ timeout: 20_000 });
  await page.getByRole("link", { name: "Fotos" }).click();
  await expect(page.locator(".tile")).toHaveCount(2);

  // A server that is down: a readable error, and the photos stay.
  await nextcloudFail(true);
  await page.getByRole("link", { name: "Nextcloud" }).click();
  await page.getByRole("button", { name: "Jetzt abgleichen" }).click();
  await expect(page.getByRole("alert").filter({ hasText: "der Abgleich ist fehlgeschlagen" })).toBeVisible({
    timeout: 20_000,
  });
  await expect(page.getByText("did not answer in time")).toBeVisible();
  await page.getByRole("link", { name: "Fotos" }).click();
  await expect(page.locator(".tile")).toHaveCount(2);
  await nextcloudFail(false);

  // Disconnect: the Nextcloud photos go, the local one stays.
  await page.getByRole("link", { name: "Nextcloud" }).click();
  await page.getByRole("button", { name: "Trennen" }).click();
  await page.getByRole("dialog").getByRole("button", { name: "Trennen" }).click();
  await expect(page.getByRole("button", { name: "Verbinden" })).toBeVisible();
  await page.getByRole("link", { name: "Fotos" }).click();
  await expect(page.locator(".tile")).toHaveCount(1);
});

test("A photo from Nextcloud cannot be deleted, only hidden", async ({ page }) => {
  await usb("image.png", "meins.png");
  await nextcloudPut("landscape.jpg", "fern.jpg", "e1");
  await connect(page);
  await page.getByRole("button", { name: "Jetzt abgleichen" }).click();
  await expect(page.getByText(/1 neu/)).toBeVisible({ timeout: 20_000 });
  await page.getByRole("link", { name: "Fotos" }).click();
  await expect(page.locator(".tile")).toHaveCount(2);

  // In the selection: hiding is offered, deleting is not.
  await page.getByRole("button", { name: "Auswählen" }).click();
  await page.locator(".tile").nth(0).click();
  await page.locator(".tile").nth(1).click();
  const bar = page.getByRole("toolbar");
  await expect(bar.getByRole("button", { name: "Ausblenden" })).toBeVisible();
  await expect(bar.getByRole("button", { name: "Löschen" })).toHaveCount(0);
  await expect(bar.getByText("lassen sich hier nicht löschen")).toBeVisible();

  // In the detail view: the local photo can be deleted, the one from Nextcloud shows the hint instead.
  await page.reload();
  await expect(page.locator(".tile")).toHaveCount(2);
  let deletable = 0;
  let hints = 0;
  for (const i of [0, 1]) {
    await page.locator(".tile").nth(i).click();
    const detail = page.getByRole("dialog");
    await expect(detail).toBeVisible();
    deletable += await detail.getByRole("button", { name: "Löschen" }).count();
    hints += await detail.getByText("lassen sich hier nicht löschen").count();
    await detail.getByRole("button", { name: "Schließen" }).click();
  }
  expect({ deletable, hints }).toEqual({ deletable: 1, hints: 1 });
});
