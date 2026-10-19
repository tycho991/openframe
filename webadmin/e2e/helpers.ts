import { expect, type Page } from "@playwright/test";
import path from "node:path";

export const CONTROL = "http://127.0.0.1:8091";
export const FIXTURES = path.resolve(import.meta.dirname, "../../core/src/test/resources/fixtures");

async function control(pathAndQuery: string): Promise<string> {
  const res = await fetch(CONTROL + pathAndQuery);
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

/** A test file as if it had been copied into the photo folder over USB. */
export const usb = (fixture: string, to: string, { scan = true } = {}) =>
  control(`/usb?fixture=${encodeURIComponent(fixture)}&to=${encodeURIComponent(to)}&scan=${scan ? 1 : 0}`);

export const many = (n: number) => control(`/many?n=${n}`);

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

/** Call the API with the browser's token. */
export async function apiAsBrowser<T>(page: Page, pathAndQuery: string): Promise<T> {
  return page.evaluate(async (p) => {
    const token = localStorage.getItem("openframe.token");
    const res = await fetch("/api/v1" + p, { headers: { Authorization: `Bearer ${token}` } });
    return res.json();
  }, pathAndQuery) as Promise<T>;
}
