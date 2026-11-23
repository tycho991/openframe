import type { Client, TrashEntry, TrashRestoreResult } from "./api/client";
import { errorText } from "./i18n/errors";
import { i18n, t } from "./i18n/i18n.svelte";

/** "Restore all" sends at most this many IDs per request. */
export const RESTORE_CHUNK = 500;

/** [ids] in requests of at most [size] IDs each. */
export function chunks<T>(ids: readonly T[], size = RESTORE_CHUNK): T[][] {
  const out: T[][] = [];
  for (let i = 0; i < ids.length; i += size) out.push(ids.slice(i, i + size));
  return out;
}

/**
 * The device that deleted [entry], in the interface language: this device, another one by its current name, a removed
 * one, or unknown when the frame lost its record.
 */
export function deletedByText(entry: TrashEntry, clients: readonly Client[]): string {
  if (!entry.deletedBy) return t("trash.byUnknown");
  const client = clients.find((c) => c.id === entry.deletedBy);
  if (!client) return t("trash.byRemoved");
  return client.current ? t("trash.byThisDevice") : t("trash.byDevice", { name: client.name });
}

function date(iso: string): string {
  return new Date(iso).toLocaleString(i18n.lang, { dateStyle: "medium", timeStyle: "short" });
}

/** Until when [entry] is kept, with the note on storage pressure; while the frame's clock is not set, without a date. */
export function keptUntilText(entry: TrashEntry): string {
  return entry.purgeAfter ? t("trash.keptUntil", { date: date(entry.purgeAfter) }) : t("trash.keptUnknown");
}

/** When [entry] was deleted. */
export function deletedAtText(entry: TrashEntry): string {
  return t("trash.deletedAt", { date: date(entry.deletedAt) });
}

/** The file name of [entry], or a placeholder for a damaged one, which has no path. */
export function entryName(entry: TrashEntry): string {
  return entry.path?.split("/").pop() ?? t("trash.damaged");
}

/** Bytes as megabytes with one decimal, in the interface language. */
export function megabytes(bytes: number): string {
  return (bytes / 1_000_000).toLocaleString(i18n.lang, {
    maximumFractionDigits: 1,
    minimumFractionDigits: 1,
  });
}

/** Count and size of the trash, for the folded panel. */
export function summaryText(count: number, totalBytes: number): string {
  const size = megabytes(totalBytes);
  return count === 1 ? t("trash.summaryOne", { size }) : t("trash.summary", { n: count, size });
}

/** What restoring several chunks did: counts of the lists, and the error that stopped it, if one did. */
export interface RestoreOutcome {
  restored: number;
  failed: number;
  missing: number;
  error: unknown;
}

/**
 * Restores [ids] in requests of at most [RESTORE_CHUNK] IDs, one after the other, and stops at the first request that
 * fails: a frame that answers 503 for one chunk would for the next as well. Never throws.
 */
export async function restoreInChunks(
  ids: readonly string[],
  restore: (chunk: string[]) => Promise<TrashRestoreResult>,
): Promise<RestoreOutcome> {
  const outcome: RestoreOutcome = { restored: 0, failed: 0, missing: 0, error: null };
  for (const chunk of chunks(ids)) {
    try {
      const result = await restore(chunk);
      outcome.restored += result.restored.length;
      outcome.failed += result.failed.length;
      outcome.missing += result.missing.length;
    } catch (e) {
      outcome.error = e;
      break;
    }
  }
  return outcome;
}

/** The message after a restore: the error that stopped it, entries that failed and entries already gone; `null` if none. */
export function restoreMessage(outcome: RestoreOutcome): string | null {
  const parts: string[] = [];
  if (outcome.error !== null) parts.push(errorText(outcome.error));
  if (outcome.failed > 0) parts.push(t("trash.restoreFailed", { count: outcome.failed }));
  if (outcome.missing > 0) parts.push(t("trash.restoreMissing", { count: outcome.missing }));
  return parts.length > 0 ? parts.join(" ") : null;
}
