import type { SourceErrorCode, SourceLoginState, SourceSettings, SourceStatus } from "./api/client";
import type de from "./i18n/de.json";
import { sourceErrorKeys } from "./i18n/errorCodes";

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

/**
 * An error of a sync or a login in the interface language: the text of its code ([reason]), or, for a result without a
 * code, the frame's text itself ([text]); an unknown code shows as it is. [detail] is the frame's English text when the
 * code has a text of its own.
 */
export interface SourceError {
  reason?: Key;
  text: string;
  detail: string | null;
}

/** The error with [code] and the frame's English [text]. */
export function sourceError(code: string | null | undefined, text: string | null | undefined): SourceError {
  const english = text ?? "";
  if (!code) return { text: english, detail: null };
  const reason = (sourceErrorKeys as Record<string, Key | undefined>)[code];
  if (!reason) return { text: english ? `${code}: ${english}` : code, detail: null };
  return { reason, text: "", detail: english || null };
}

/** Whether [address] may be opened as the login page: only `http` and `https` (design decision 16). */
export function isWebAddress(address: string): boolean {
  try {
    const url = new URL(address);
    return url.protocol === "http:" || url.protocol === "https:";
  } catch {
    return false;
  }
}

/** What the status line of a source says: a text key, its values, and whether it is an error. */
export interface StatusLine {
  key: Key;
  params: Record<string, string | number>;
  failed: boolean;
  /** For a failure with a known code: the key of its text, which goes into the parameter `reason`. */
  reason?: Key;
  /** For a failure with a known code: the frame's English text. */
  detail?: string | null;
  /** Rejected access: the frame tries again at most once a day until the user acts. */
  daily?: boolean;
}

/** The status line of a source in the interface language [lang]. */
export function statusLine(status: SourceStatus, lang: string): StatusLine {
  if (status.syncing) return { key: "sources.syncing", params: {}, failed: false };
  if (status.waiting) return { key: "sources.waitingForOther", params: {}, failed: false };
  if (status.ok === null) return { key: "sources.never", params: {}, failed: false };
  const time = formatTime(status.lastSyncAt, lang);
  if (status.ok) {
    return {
      key: "sources.lastOk",
      params: { time, added: status.added, updated: status.updated, removed: status.removed },
      failed: false,
    };
  }
  const error = sourceError(status.errorCode as SourceErrorCode | string | null, status.error);
  if (error.reason) {
    return {
      key: "sources.lastFailedReason",
      params: { time },
      failed: true,
      reason: error.reason,
      detail: error.detail,
      daily: status.rejected,
    };
  }
  return {
    key: "sources.lastFailed",
    params: { time, error: error.text },
    failed: true,
    daily: status.rejected,
  };
}

/** The ranges the API accepts, for the message at a field. */
export const sourceRanges: Record<string, [number, number]> = {
  intervalMinutes: [5, 1440],
  originalsBudgetMb: [100, 50000],
};

/** The frame holds at most this many sources. */
export const MAX_SOURCES = 8;

/** How a login flow ended: approved (with the source it is for), expired, or failed with an error. */
export type LoginOutcome =
  { state: "done"; sourceId: string | null } | { state: "expired" } | { state: "failed"; error: SourceError };

/**
 * Asks the frame every [intervalMs] whether the user approved the login [flowId], one question at a time, until the
 * flow ends; then [onEnd] gets the outcome, or [onError] the error of a question that failed. Returns a function that
 * stops it: an answer that comes after that is dropped. For a new login and a confirmation login alike.
 */
export function pollLogin(
  poll: (flowId: string) => Promise<SourceLoginState>,
  flowId: string,
  onEnd: (outcome: LoginOutcome) => void,
  onError: (e: unknown) => void,
  intervalMs = 2000,
): () => void {
  let over = false;
  let timer: ReturnType<typeof setTimeout> | undefined;
  const ask = async () => {
    try {
      const polled = await poll(flowId);
      if (over) return;
      if (polled.state === "pending") {
        timer = setTimeout(() => void ask(), intervalMs);
        return;
      }
      over = true;
      if (polled.state === "done") onEnd({ state: "done", sourceId: polled.sourceId });
      else if (polled.state === "failed")
        onEnd({ state: "failed", error: sourceError(polled.errorCode, polled.error) });
      else onEnd({ state: "expired" });
    } catch (e) {
      if (over) return;
      over = true;
      onError(e);
    }
  };
  timer = setTimeout(() => void ask(), intervalMs);
  return () => {
    over = true;
    clearTimeout(timer);
  };
}
