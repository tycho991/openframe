import { ApiError } from "../api/client";
import { errorKeys } from "./errorCodes";
import { t } from "./i18n.svelte";

/** The text of an error: for an error code of the API in the interface language, otherwise the text of the server. */
export function errorMessage(e: unknown): string {
  if (e instanceof ApiError) {
    const key = errorKeys[e.code];
    return key ? t(key) : e.message;
  }
  return e instanceof Error ? e.message : String(e);
}

/** "Something went wrong: …" with [errorMessage]. */
export function errorText(e: unknown): string {
  return t("common.error", { message: errorMessage(e) });
}
