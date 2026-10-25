import type { SourceSettings, SourceStatus } from "./api/client";
import type de from "./i18n/de.json";

type Key = keyof typeof de;

/** The fields of [to] that differ from [from], as a partial change for `PATCH /sources/{id}`. */
export function settingsPatch(from: SourceSettings, to: SourceSettings): Partial<SourceSettings> {
  const out: Partial<SourceSettings> = {};
  for (const key of Object.keys(to) as (keyof SourceSettings)[]) {
    if (to[key] !== from[key]) (out as Record<string, unknown>)[key] = to[key];
  }
  return out;
}

/** An address that sends the access without encryption. */
export function isPlainHttp(address: string): boolean {
  return /^\s*http:\/\//i.test(address);
}

/**
 * Whether [link] looks like the link of a Nextcloud share, `http(s)://host[/path][/index.php]/s/<token>`, the
 * same rule as the frame's. The frame checks it again and reads the share before it stores anything.
 */
export function isShareLink(link: string): boolean {
  let url: URL;
  try {
    url = new URL(link.trim());
  } catch {
    return false;
  }
  if (url.protocol !== "http:" && url.protocol !== "https:") return false;
  if (!url.hostname || url.username || url.password) return false;
  const segments = url.pathname.split("/").filter((s) => s !== "");
  return segments.length >= 2 && segments.at(-2) === "s" && /^[A-Za-z0-9]+$/.test(segments.at(-1) ?? "");
}

/** A time from the API for the interface language. */
export function formatTime(iso: string | null, lang: string): string {
  if (!iso) return "";
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? iso : date.toLocaleString(lang);
}

/** What the status line of a source says: a text key, its values, and whether it is an error. */
export interface StatusLine {
  key: Key;
  params: Record<string, string | number>;
  failed: boolean;
}

/** The status line of a source in the interface language [lang]. */
export function statusLine(status: SourceStatus, lang: string): StatusLine {
  if (status.syncing) return { key: "sources.syncing", params: {}, failed: false };
  if (status.ok === null) return { key: "sources.never", params: {}, failed: false };
  const time = formatTime(status.lastSyncAt, lang);
  if (status.ok) {
    return {
      key: "sources.lastOk",
      params: { time, added: status.added, updated: status.updated, removed: status.removed },
      failed: false,
    };
  }
  return { key: "sources.lastFailed", params: { time, error: status.error ?? "" }, failed: true };
}

/** The ranges the API accepts, for the message at a field. */
export const sourceRanges: Record<string, [number, number]> = {
  intervalMinutes: [5, 1440],
  originalsBudgetMb: [100, 50000],
};

/** The frame holds at most this many sources. */
export const MAX_SOURCES = 8;
