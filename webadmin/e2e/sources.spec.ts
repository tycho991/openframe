import { expect, test, type Page } from "@playwright/test";
import {
  albumTiles,
  foldButton,
  FRAME_ORIGIN,
  nextcloudApprove,
  nextcloudFail,
  nextcloudPut,
  nextcloudRemove,
  nextcloudShare,
  openAlbum,
  openAllAlbums,
  pairInUi,
  reset,
  usb,
  photoDetail,
} from "./helpers";

test.beforeEach(async () => {
  await reset();
});

/** The photo overview with every heading open. */
async function openPhotos(page: Page) {
  await page.getByRole("link", { name: "Fotos" }).click();
  await openAllAlbums(page);
}

async function openSources(page: Page) {
  await page.getByRole("link", { name: "Quellen" }).click();
  await expect(page.getByRole("heading", { name: "Quellen", level: 1 })).toBeVisible();
}

/** Adds a share link through the form; the password field is left as it is when [password] is not given. */
async function addShare(
  page: Page,
  token: string,
  { name = "", password }: { name?: string; password?: string } = {},
) {
  await page.getByRole("tab", { name: "Freigabe-Link" }).click();
  await page.getByLabel("Link der Freigabe").fill(`https://cloud.example.org/s/${token}`);
  if (password !== undefined) await page.getByLabel("Passwort der Freigabe").fill(password);
  await page.getByLabel("Name in der Liste (optional)").fill(name);
  await page.getByRole("button", { name: "Hinzufügen" }).click();
}

const card = (page: Page, name: string) => page.getByRole("region", { name });

/** Connects an account through the login flow like a user: address, approval in the other tab, back in the interface. */
async function connect(page: Page) {
  await pairInUi(page);
  await openSources(page);
  await page.getByRole("tab", { name: "Nextcloud-Konto" }).click();
  await page.getByLabel("Adresse des Servers").fill(FRAME_ORIGIN);
  // A plain http address is called out.
  await expect(page.getByText("Diese Adresse nutzt http")).toBeVisible();
  const popup = page.waitForEvent("popup").catch(() => null);
  await page.getByRole("button", { name: "Verbinden" }).click();
  await expect(page.getByText("Warte auf deine Zustimmung")).toBeVisible();
  await popup;
  await nextcloudApprove();
  const account = card(page, "127.0.0.1");
  await expect(account.getByText(`Anna auf ${FRAME_ORIGIN}`)).toBeVisible({ timeout: 15_000 });
  // The card of a saved http source says that its connection is not encrypted.
  await expect(account.getByText("Diese Adresse nutzt http")).toBeVisible();
  // No folder is chosen yet: nothing syncs, and the interface says so.
  await expect(account.getByText("Wähle den Ordner")).toBeVisible();
  await expect(account.getByRole("button", { name: "Jetzt abgleichen" })).toBeDisabled();
  await account.getByLabel("Ordner auf dem Server").fill("/");
  await account.getByRole("button", { name: "Speichern" }).click();
  await expect(account.getByText("Gespeichert")).toBeVisible();
  await expect(account.getByRole("button", { name: "Jetzt abgleichen" })).toBeEnabled();
}

test("Two share links side by side: a password at its field, one album from both, sync one, remove one", async ({
  page,
}) => {
  await usb("image.png", "meins.png");
  await nextcloudShare("Urlaub1", { password: "geheim" });
  await nextcloudPut("landscape.jpg", "Strand/a.jpg", { share: "Urlaub1" });
  await nextcloudPut("landscape.jpg", "Strand/b.jpg", { share: "Urlaub1" });
  await nextcloudShare("Familie2");
  await nextcloudPut("landscape.jpg", "Strand/c.jpg", { share: "Familie2" });
  await pairInUi(page);
  await openSources(page);
  await expect(page.getByText("Noch keine Quelle.")).toBeVisible();

  // A protected share: without and with a wrong password the frame asks at the password field.
  await addShare(page, "Urlaub1", { name: "Urlaub" });
  const passwordField = page.getByLabel("Passwort der Freigabe");
  await expect(page.getByText("Diese Freigabe ist geschützt. Gib ihr Passwort ein.")).toBeVisible();
  await expect(passwordField).toHaveAttribute("aria-invalid", "true");
  await addShare(page, "Urlaub1", { name: "Urlaub", password: "falsch" });
  await expect(page.getByText("Das Passwort ist falsch.")).toBeVisible();
  await expect(page.getByText("Noch keine Quelle.")).toBeVisible();

  // The right password: the source is listed and its first sync runs without choosing a folder.
  await addShare(page, "Urlaub1", { name: "Urlaub", password: "geheim" });
  const urlaub = card(page, "Urlaub");
  await expect(urlaub.getByText("Nextcloud-Freigabe-Link")).toBeVisible();
  await expect(urlaub.getByText(/2 neu, 0 geändert, 0 entfernt/)).toBeVisible({ timeout: 20_000 });
  await expect(page.getByLabel("Link der Freigabe")).toHaveValue("");

  // A second source next to it, with its own status.
  await addShare(page, "Familie2", { name: "Familie" });
  const familie = card(page, "Familie");
  await expect(familie.getByText(/1 neu, 0 geändert, 0 entfernt/)).toBeVisible({ timeout: 20_000 });
  await expect(urlaub.getByText(/2 neu, 0 geändert, 0 entfernt/)).toBeVisible();

  // Both are in the overview next to the local photo, the folder of the same name is one album.
  await openPhotos(page);
  await expect(page.locator(".tile")).toHaveCount(4);
  await expect(foldButton(page, "Strand")).toHaveAccessibleName("Strand, 3 Fotos");
  await expect(albumTiles(page, "Strand")).toHaveCount(3);

  // Syncing one leaves the other as it is.
  await nextcloudRemove("Strand/c.jpg", { share: "Familie2" });
  await nextcloudPut("landscape.jpg", "Strand/d.jpg", { share: "Familie2" });
  await openSources(page);
  await familie.getByRole("button", { name: "Jetzt abgleichen" }).click();
  await expect(familie.getByText(/1 neu, 0 geändert, 1 entfernt/)).toBeVisible({ timeout: 20_000 });
  await expect(urlaub.getByText(/2 neu, 0 geändert, 0 entfernt/)).toBeVisible();

  // Removing one: after the confirmation its photos go, the other source and the local photo stay.
  await urlaub.getByRole("button", { name: "Entfernen" }).click();
  await page.getByRole("dialog").getByRole("button", { name: "Entfernen" }).click();
  await expect(urlaub).toHaveCount(0);
  await expect(familie).toBeVisible();
  await openPhotos(page);
  await expect(page.locator(".tile")).toHaveCount(2);
});

test("A link that is no share, a share that does not exist, the share of a file and a file drop are refused at the link", async ({
  page,
}) => {
  await nextcloudShare("Datei3", { file: true });
  await nextcloudShare("Ablage4", { drop: true });
  await pairInUi(page);
  await openSources(page);
  const link = page.getByLabel("Link der Freigabe");

  await link.fill("https://cloud.example.org/apps/files/");
  await page.getByRole("button", { name: "Hinzufügen" }).click();
  await expect(page.getByText("Ein Link wie https://cloud.example.org/s/AbC123")).toBeVisible();
  await expect(link).toHaveAttribute("aria-invalid", "true");

  await addShare(page, "Nope42");
  await expect(page.getByText("Unter diesem Link gibt es keine Freigabe.")).toBeVisible();

  await addShare(page, "Datei3");
  await expect(page.getByText("Dieser Link teilt eine einzelne Datei.")).toBeVisible();

  await addShare(page, "Ablage4");
  await expect(page.getByText("Dieser Link nimmt nur Uploads an (Datei-Ablage).")).toBeVisible();
  await expect(page.getByText("Noch keine Quelle.")).toBeVisible();
});

test("An account: connect, first sync, a photo deleted on the server, a failing sync and removal", async ({
  page,
}) => {
  await usb("image.png", "meins.png");
  await nextcloudPut("landscape.jpg", "Urlaub/strand.jpg", { etag: "e1" });
  await nextcloudPut("landscape.jpg", "Urlaub/berg.jpg", { etag: "e2" });
  await connect(page);
  const account = card(page, "127.0.0.1");

  // First sync, on request.
  await expect(account.getByText("Noch kein Abgleich.")).toBeVisible();
  await account.getByRole("button", { name: "Jetzt abgleichen" }).click();
  await expect(account.getByText(/2 neu, 0 geändert, 0 entfernt/)).toBeVisible({ timeout: 20_000 });

  // The photos are in the overview next to the local one.
  await openPhotos(page);
  await expect(page.locator(".tile")).toHaveCount(3);

  // Deleted on the server: gone after the next sync.
  await nextcloudRemove("Urlaub/berg.jpg");
  await openSources(page);
  await account.getByRole("button", { name: "Jetzt abgleichen" }).click();
  await expect(account.getByText(/0 neu, 0 geändert, 1 entfernt/)).toBeVisible({ timeout: 20_000 });
  await openPhotos(page);
  await expect(page.locator(".tile")).toHaveCount(2);

  // A server that is down: a readable error in the interface language, the frame's text as a detail, and the
  // photos stay.
  await nextcloudFail(true, { kind: "unreachable" });
  await openSources(page);
  await account.getByRole("button", { name: "Jetzt abgleichen" }).click();
  await expect(account.getByRole("alert").filter({ hasText: "der Abgleich ist fehlgeschlagen" })).toBeVisible(
    {
      timeout: 20_000,
    },
  );
  await expect(
    account.getByText("Der Server ist nicht erreichbar oder hat zu langsam geantwortet."),
  ).toBeVisible();
  await expect(account.getByText("did not answer in time")).toBeVisible();
  await openPhotos(page);
  await expect(page.locator(".tile")).toHaveCount(2);
  await nextcloudFail(false);

  // Removal: the photos of the account go, the local one stays.
  await openSources(page);
  await account.getByRole("button", { name: "Entfernen" }).click();
  await page.getByRole("dialog").getByRole("button", { name: "Entfernen" }).click();
  await expect(page.getByText("Noch keine Quelle.")).toBeVisible();
  await openPhotos(page);
  await expect(page.locator(".tile")).toHaveCount(1);
});

test("A photo of a source cannot be deleted, only hidden", async ({ page }) => {
  await usb("image.png", "meins.png");
  await nextcloudShare("Fern1", { name: "Fernweh" });
  await nextcloudPut("landscape.jpg", "fern.jpg", { share: "Fern1" });
  await pairInUi(page);
  await openSources(page);
  // Without a name of its own the source is called after the shared folder.
  await addShare(page, "Fern1");
  await expect(card(page, "Fernweh").getByText(/1 neu/)).toBeVisible({ timeout: 20_000 });
  await openPhotos(page);
  await expect(page.locator(".tile")).toHaveCount(2);

  // In the selection: hiding is offered, deleting is not.
  await page.getByRole("button", { name: "Auswählen" }).click();
  await page.locator(".tile").nth(0).click();
  await page.locator(".tile").nth(1).click();
  const bar = page.getByRole("toolbar");
  await expect(bar.getByRole("button", { name: "Ausblenden" })).toBeVisible();
  await expect(bar.getByRole("button", { name: "Löschen" })).toHaveCount(0);
  await expect(bar.getByText("lassen sich hier nicht löschen")).toBeVisible();

  // In the detail view: the local photo can be deleted, the one of the source shows the hint instead.
  await page.reload();
  await openAlbum(page, "");
  await expect(page.locator(".tile")).toHaveCount(2);
  let deletable = 0;
  let hints = 0;
  for (const i of [0, 1]) {
    await page.locator(".tile").nth(i).click();
    const detail = photoDetail(page);
    await expect(detail).toBeVisible();
    deletable += await detail.getByRole("button", { name: "Löschen" }).count();
    hints += await detail.getByText("lassen sich hier nicht löschen").count();
    await detail.getByRole("button", { name: "Schließen" }).click();
  }
  expect({ deletable, hints }).toEqual({ deletable: 1, hints: 1 });
});

// Spec web-admin, "Photos from Nextcloud in the overview".
test("Selection in a folded album: both photos stay selected, deleting is not offered", async ({ page }) => {
  await usb("image.png", "Zuhause/meins.png");
  await nextcloudShare("Fern2", { name: "Fernweh" });
  await nextcloudPut("landscape.jpg", "Reise/fern.jpg", { share: "Fern2" });
  await pairInUi(page);
  await openSources(page);
  await addShare(page, "Fern2");
  await expect(card(page, "Fernweh").getByText(/1 neu/)).toBeVisible({ timeout: 20_000 });
  await page.getByRole("link", { name: "Fotos" }).click();
  await openAlbum(page, "Reise");
  await openAlbum(page, "Zuhause");

  await page.getByRole("button", { name: "Auswählen" }).click();
  await albumTiles(page, "Reise").first().click();
  await albumTiles(page, "Zuhause").first().click();
  const bar = page.getByRole("toolbar");
  await expect(bar.getByText("2 ausgewählt")).toBeVisible();
  await expect(bar.getByRole("button", { name: "Löschen" })).toHaveCount(0);

  // Folding the album of the Nextcloud photo drops its tiles, not the selection.
  await foldButton(page, "Reise").click();
  await expect(albumTiles(page, "Reise")).toHaveCount(0);
  await expect(bar.getByText("2 ausgewählt")).toBeVisible();
  await expect(bar.getByRole("button", { name: "Ausblenden" })).toBeVisible();
  await expect(bar.getByRole("button", { name: "Löschen" })).toHaveCount(0);
  await expect(bar.getByText("lassen sich hier nicht löschen")).toBeVisible();
});

test("A slow approval is not reported as expired", async ({ page }) => {
  await pairInUi(page);
  await openSources(page);
  await page.getByRole("tab", { name: "Nextcloud-Konto" }).click();
  await page.getByLabel("Adresse des Servers").fill(FRAME_ORIGIN);
  const popup = page.waitForEvent("popup").catch(() => null);
  await page.getByRole("button", { name: "Verbinden" }).click();
  await popup;
  // The answer to the approved login takes longer than the interval between two questions.
  await nextcloudApprove(5_000);
  await expect(card(page, "127.0.0.1").getByText(`Anna auf ${FRAME_ORIGIN}`)).toBeVisible({
    timeout: 20_000,
  });
  await expect(page.getByText("Die Anmeldung ist abgelaufen")).toHaveCount(0);
});

test("A full frame offers no new share, but an account can log in again", async ({ page }) => {
  await pairInUi(page);
  await openSources(page);
  for (let i = 1; i <= 8; i++) {
    await nextcloudShare(`Voll${i}`, { name: `Ordner ${i}` });
    await addShare(page, `Voll${i}`);
    await expect(card(page, `Ordner ${i}`)).toBeVisible();
  }
  await expect(page.getByText("Der Rahmen hat 8 Quellen")).toBeVisible();
  await expect(page.getByRole("tab")).toHaveCount(0);
  await expect(page.getByLabel("Link der Freigabe")).toHaveCount(0);
  await expect(page.getByLabel("Adresse des Servers")).toBeVisible();
  await expect(page.getByRole("button", { name: "Verbinden" })).toBeVisible();
});

test("The address of the Nextcloud section of earlier versions leads to the sources", async ({ page }) => {
  await pairInUi(page);
  await page.goto("/nextcloud");
  await expect(page.getByRole("heading", { name: "Quellen", level: 1 })).toBeVisible();
  await expect(page).toHaveURL(/\/sources$/);
});

test("A double click on Connect starts one login", async ({ page }) => {
  await pairInUi(page);
  await openSources(page);
  await page.getByRole("tab", { name: "Nextcloud-Konto" }).click();
  await page.getByLabel("Adresse des Servers").fill(FRAME_ORIGIN);
  const starts: string[] = [];
  page.on("request", (request) => {
    if (request.method() === "POST" && new URL(request.url()).pathname === "/api/v1/sources/login")
      starts.push(request.url());
  });
  const popups: unknown[] = [];
  page.context().on("page", (popup) => popups.push(popup));
  await page.getByRole("button", { name: "Verbinden" }).dblclick();
  await expect(page.getByText("Warte auf deine Zustimmung")).toBeVisible();
  // Time for a second request, had the double click sent one.
  await page.waitForTimeout(500);
  expect(starts).toHaveLength(1);
  expect(popups.length).toBeLessThanOrEqual(1);
});
