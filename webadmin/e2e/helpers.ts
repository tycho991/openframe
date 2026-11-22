import { expect, type Page } from "@playwright/test";
import path from "node:path";
import { CONTROL } from "./ports";

export { CONTROL, FRAME_ORIGIN } from "./ports";
export const FIXTURES = path.resolve(import.meta.dirname, "../../core/src/test/resources/fixtures");

/** [text] as a regular expression that matches exactly it, e.g. an address inside a pattern. */
export const escapeRegExp = (text: string) => text.replace(/[.*+?^${}()|[\]\\/]/g, "\\$&");

async function control(pathAndQuery: string): Promise<string> {
  // The control port refuses requests without this header, so that no other web page can steer it.
  const res = await fetch(CONTROL + pathAndQuery, { headers: { "x-openframe-control": "1" } });
  if (!res.ok) throw new Error(`${pathAndQuery}: ${res.status} ${await res.text()}`);
  return res.text();
}

/** Fresh frame without photos, pairings and settings. */
export const reset = () => control("/reset");

/** The code the frame is currently showing. */
export const frameCode = () => control("/code");

/** The secret of the QR code the frame is currently showing. */
export const frameSecret = () => control("/qr");

/** Moves the frame's clock forward, e.g. to let a pairing link expire. */
export const advanceFrameClock = (seconds: number) => control(`/advance?seconds=${seconds}`);

/**
 * The seconds from [now] to 00:05 of the next day from 23:50 on, otherwise 0. In local time, which the test server on
 * the same machine shares (UTC in CI).
 */
export function secondsToSkipMidnight(now: Date): number {
  if (now.getHours() < 23 || now.getMinutes() < 50) return 0;
  const after = new Date(now);
  after.setDate(after.getDate() + 1);
  after.setHours(0, 5, 0, 0);
  return Math.ceil((after.getTime() - now.getTime()) / 1000);
}

/**
 * Moves the frame's clock past midnight shortly before it, for a test that needs the schedule's day to last until
 * the test ends. Comes first in the test, so that pairing and display already run on the moved clock; `reset()` moves
 * it back.
 */
export async function avoidMidnight(): Promise<void> {
  const seconds = secondsToSkipMidnight(new Date());
  if (seconds > 0) await advanceFrameClock(seconds);
}

/** A test file as if it had been copied into the photo folder over USB. */
export const usb = (fixture: string, to: string, { scan = true } = {}) =>
  control(`/usb?fixture=${encodeURIComponent(fixture)}&to=${encodeURIComponent(to)}&scan=${scan ? 1 : 0}`);

/** Whether [file] is in the photo folder of the test server. */
export const inPhotoFolder = async (file: string) =>
  (await control(`/usb/exists?path=${encodeURIComponent(file)}`)) === "yes";

export const many = (n: number) => control(`/many?n=${n}`);

/** Restarts the frame in safe mode or in normal mode, with its data and pairings. */
export const safeMode = (on: boolean) => control(`/safe-mode?on=${on ? 1 : 0}`);

/** A local photo as if its rendering had crashed the decoder; returns its ID. */
export const quarantine = (path: string) => control(`/quarantine?path=${encodeURIComponent(path)}`);

/** The caption the frame shows over the photo on screen: its line and its place, `""` without one. */
export async function frameCaption(): Promise<{ line: string; place: string }> {
  const [line, place] = (await control("/caption")).split("\n");
  return { line, place };
}

/** The user approves the login in the Nextcloud tab; with [slowMs] the frame's question after it takes that long. */
export const nextcloudApprove = (slowMs = 0) => control(`/nextcloud/approve?slow=${slowMs}`);

/**
 * A share on the fake server, of a folder called [name]; `file` makes it the share of a single file, `drop` a
 * "file drop" that only accepts uploads.
 */
export const nextcloudShare = (
  token: string,
  { password = "", file = false, drop = false, name = "Geteilt" } = {},
) =>
  control(
    `/nextcloud/share?token=${token}&password=${encodeURIComponent(password)}&file=${file ? 1 : 0}&drop=${drop ? 1 : 0}` +
      `&name=${encodeURIComponent(name)}`,
  );

/** A file in the folder of the account on the fake server, or of the share with the token `share`. */
export const nextcloudPut = (fixture: string, to: string, { etag = "etag-" + to, share = "" } = {}) =>
  control(
    `/nextcloud/put?fixture=${encodeURIComponent(fixture)}&path=${encodeURIComponent(to)}&etag=${etag}` +
      (share ? `&share=${share}` : ""),
  );

export const nextcloudRemove = (from: string, { share = "" } = {}) =>
  control(`/nextcloud/remove?path=${encodeURIComponent(from)}` + (share ? `&share=${share}` : ""));

/**
 * Lets listing the folder of the account (or of a share) fail like a server that is down, or with another [kind], a
 * value of the contract's `SourceErrorCode`.
 */
export const nextcloudFail = (on: boolean, { share = "", kind = "" } = {}) =>
  control(
    `/nextcloud/fail?on=${on ? 1 : 0}` + (share ? `&share=${share}` : "") + (kind ? `&kind=${kind}` : ""),
  );

/** The frame's display found its GPU too slow to fade, so it changes photos instantly from now on. */
export const slowDisplay = () => control("/transitions/slow");

/** Device administration activated or deactivated on the frame. The test server starts with it active. */
export const displayAdmin = (on: boolean) => control(`/display/admin?on=${on ? 1 : 0}`);

/** Someone presses the frame's power button. */
export const powerButton = () => control("/display/power");

/** Modifying system settings allowed or withdrawn on the frame. The test server starts with it allowed. */
export const brightnessPermission = (on: boolean) => control(`/brightness/permission?on=${on ? 1 : 0}`);

/** A frame with or without a light sensor. The test server starts with one. */
export const lightSensor = (on: boolean) => control(`/brightness/sensor?on=${on ? 1 : 0}`);

/** Android's brightness on the frame now, such as `40 manual` or `50 auto`. */
export const frameBrightness = () => control("/brightness");

/** Pairs another client directly through the API and returns its token. */
export async function pairOtherDevice(baseURL: string, name: string): Promise<string> {
  const code = await frameCode();
  const res = await fetch(baseURL + "/api/v1/pairing", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ code, deviceName: name }),
  });
  return ((await res.json()) as { token: string }).token;
}

/** Pairs the browser through the dialog. */
export async function pairInUi(page: Page): Promise<void> {
  await page.goto("/");
  await expect(page.getByRole("heading", { name: "Mit dem Rahmen koppeln" })).toBeVisible();
  await page.getByLabel("Code vom Rahmen").fill(await frameCode());
  await page.getByLabel("Name dieses Geräts").fill("Playwright");
  await page.getByRole("button", { name: "Koppeln", exact: true }).click();
  await expect(page.getByRole("navigation")).toBeVisible();
}

/** `holiday/2024` as the photo overview names it; `""` is the heading for photos without an album. */
function albumName(path: string): RegExp {
  return path === "" ? /(Kein Album|No album)/ : new RegExp(escapeRegExp(path.split("/").join(" / ")));
}

/** The fold button of an album heading in the photo overview, named with the path and the count. */
export const foldButton = (page: Page, path: string) =>
  page.getByRole("button", {
    name: new RegExp(`^${albumName(path).source}, (1 (Foto|photo)|\\d+ (Fotos|photos))$`),
  });

/** The "Show in slideshow" switch on the heading of album [path]. */
export const albumSwitch = (page: Page, path: string) =>
  page.getByRole("switch", {
    name: new RegExp(`^(In der Slideshow zeigen|Show in slideshow): ${albumName(path).source}$`),
  });

/** The ids the photo overview gives a heading and what is below it. */
const headingId = (path: string) => (path === "" ? "photos-no-album" : "album-" + encodeURIComponent(path));

/** The row of an album heading: fold button, count, exception mark and switch. */
export const albumHeading = (page: Page, path: string) => page.locator(`[id="${headingId(path)}"]`);

/** What is below a heading while it is open: the album's grid, then its sub-albums. */
export const albumContent = (page: Page, path: string) => page.locator(`[id="${headingId(path)}-content"]`);

/** The tiles of the photos directly in album [path], not those of its sub-albums. */
export const albumTiles = (page: Page, path: string) =>
  albumContent(page, path).locator(":scope > .grid-box .tile");

/** Opens the heading of album [path] and every heading above it in the photo overview, those that are folded. */
export async function openAlbum(page: Page, path: string): Promise<void> {
  const levels = path.split("/").map((_, i, all) => all.slice(0, i + 1).join("/"));
  for (const level of levels) {
    const button = foldButton(page, level);
    await expect(button).toBeVisible();
    if ((await button.getAttribute("aria-expanded")) !== "true") await button.click();
    await expect(button).toHaveAttribute("aria-expanded", "true");
  }
}

/** Opens every folded heading of the photo overview, sub-albums included, once the headings are there. */
export async function openAllAlbums(page: Page): Promise<void> {
  const headings = page.getByRole("main").getByRole("heading");
  await expect(headings.getByRole("button").first()).toBeVisible();
  const folded = headings.getByRole("button", { expanded: false });
  for (let i = 0; i < 100 && (await folded.count()) > 0; i++) await folded.first().click();
  await expect(folded).toHaveCount(0);
}

/** The photo detail: a panel next to the grid from 1200 px, a modal dialog (layer or sheet) below. */
export const photoDetail = (page: Page, name?: string) =>
  page.getByRole("complementary", { name }).or(page.getByRole("dialog", { name }));

/** Waits until the photo detail has finished moving in, for measurements of its contents. */
export async function detailSettled(page: Page): Promise<void> {
  await photoDetail(page).evaluate((el) =>
    Promise.all(el.getAnimations({ subtree: true }).map((a) => a.finished)),
  );
}

/** Chooses the interface language in the appearance menu, whose button is named in the current language. */
export async function chooseLanguage(page: Page, language: "Deutsch" | "English"): Promise<void> {
  await page.getByRole("button", { name: /^(Darstellung|Appearance)$/ }).click();
  await page.getByRole("radio", { name: language }).check();
  await page.keyboard.press("Escape");
}

/** Call the API with the browser's token; with [body] as a mutating request. */
export async function apiAsBrowser<T>(
  page: Page,
  pathAndQuery: string,
  { method = "GET", body }: { method?: string; body?: unknown } = {},
): Promise<T> {
  return page.evaluate(
    async ({ p, method, body }) => {
      const token = localStorage.getItem("openframe.token");
      const headers: Record<string, string> = { Authorization: `Bearer ${token}` };
      if (body !== undefined) headers["Content-Type"] = "application/json";
      const res = await fetch("/api/v1" + p, {
        method,
        headers,
        body: body === undefined ? undefined : JSON.stringify(body),
      });
      return res.json();
    },
    { p: pathAndQuery, method, body },
  ) as Promise<T>;
}

/** A release of openframe on the fake GitHub; `apk: false` publishes it without an APK. */
export const publishRelease = (version: string, { notes = "", apk = true } = {}) =>
  control(
    `/update/publish?version=${version}${notes ? `&notes=${encodeURIComponent(notes)}` : ""}${apk ? "" : "&apk=0"}`,
  );

/** The frame runs [version] from now on, as after an update. */
export const installedVersion = (version: string) => control(`/update/installed?version=${version}`);

/** Someone answers the installation dialog on the frame. */
export const answerInstallDialog = (result: "confirmed" | "cancelled" | "failed") =>
  control(`/update/answer?result=${result}`);

/** Android allows openframe to install apps, or not. */
export const installAllowed = (on: boolean) => control(`/update/allowed?on=${on ? 1 : 0}`);

/** The frame runs an official build, or one signed with another key; the frame restarts. */
export const officialBuild = (on: boolean) => control(`/update/official?on=${on ? 1 : 0}`);
