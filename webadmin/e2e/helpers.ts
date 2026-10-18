import { expect, type Page } from "@playwright/test";
import path from "node:path";

export const CONTROL = "http://127.0.0.1:8091";
export const FIXTURES = path.resolve(import.meta.dirname, "../../core/src/test/resources/fixtures");

async function control(pathAndQuery: string): Promise<string> {
  const res = await fetch(CONTROL + pathAndQuery);
  if (!res.ok) throw new Error(`${pathAndQuery}: ${res.status} ${await res.text()}`);
  return res.text();
}

/** Frischer Rahmen ohne Fotos, Kopplungen und Einstellungen. */
export const reset = () => control("/reset");

/** Der Code, den der Rahmen gerade anzeigt. */
export const frameCode = () => control("/code");

/** Eine Testdatei, als wäre sie per USB in den Fotoordner kopiert worden. */
export const usb = (fixture: string, to: string, { scan = true } = {}) =>
  control(`/usb?fixture=${encodeURIComponent(fixture)}&to=${encodeURIComponent(to)}&scan=${scan ? 1 : 0}`);

export const many = (n: number) => control(`/many?n=${n}`);

/** Koppelt einen weiteren Client direkt über die API und liefert sein Token. */
export async function pairOtherDevice(baseURL: string, name: string): Promise<string> {
  const code = await frameCode();
  const res = await fetch(baseURL + "/api/v1/pairing", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ code, deviceName: name }),
  });
  return ((await res.json()) as { token: string }).token;
}

/** Koppelt den Browser über den Dialog. */
export async function pairInUi(page: Page): Promise<void> {
  await page.goto("/");
  await expect(page.getByRole("heading", { name: "Mit dem Rahmen koppeln" })).toBeVisible();
  await page.getByLabel("Code vom Rahmen").fill(await frameCode());
  await page.getByLabel("Name dieses Geräts").fill("Playwright");
  await page.getByRole("button", { name: "Koppeln", exact: true }).click();
  await expect(page.getByRole("navigation")).toBeVisible();
}

/** Die API mit dem Token des Browsers aufrufen. */
export async function apiAsBrowser<T>(page: Page, pathAndQuery: string): Promise<T> {
  return page.evaluate(async (p) => {
    const token = localStorage.getItem("openframe.token");
    const res = await fetch("/api/v1" + p, { headers: { Authorization: `Bearer ${token}` } });
    return res.json();
  }, pathAndQuery) as Promise<T>;
}
