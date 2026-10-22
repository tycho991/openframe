import type { NextcloudSettings } from "./api/client";

/** The fields of [to] that differ from [from], as a partial change for `PATCH /nextcloud`. */
export function settingsPatch(from: NextcloudSettings, to: NextcloudSettings): Partial<NextcloudSettings> {
  const out: Partial<NextcloudSettings> = {};
  for (const key of Object.keys(to) as (keyof NextcloudSettings)[]) {
    if (to[key] !== from[key]) (out as Record<string, unknown>)[key] = to[key];
  }
  return out;
}

/** An address that sends the access without encryption. */
export function isPlainHttp(address: string): boolean {
  return /^\s*http:\/\//i.test(address);
}

/** A time from the API for the interface language. */
export function formatTime(iso: string | null, lang: string): string {
  if (!iso) return "";
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? iso : date.toLocaleString(lang);
}

/** The ranges the API accepts, for the message at a field. */
export const nextcloudRanges: Record<string, [number, number]> = {
  intervalMinutes: [5, 1440],
  originalsBudgetMb: [100, 50000],
};
