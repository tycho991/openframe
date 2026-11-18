import { ApiError } from "./api/client";
import { t } from "./i18n/i18n.svelte";
import { errorText } from "./i18n/errors";

/**
 * The text of the pairing dialog for a failed pairing by code. Only `locked` counts down: the seconds come from the
 * server's text. `code_paused` lasts up to a day and names the QR code instead.
 */
export function pairingErrorText(e: unknown): string {
  if (!(e instanceof ApiError)) return t("pair.network");
  switch (e.code) {
    case "invalid_code":
      return t("pair.invalid");
    case "expired_code":
      return t("pair.expired");
    case "locked":
      return t("pair.locked", { seconds: /[0-9]+/.exec(e.message)?.[0] ?? "60" });
    case "code_paused":
      return t("error.code_paused");
    case "network":
      return t("pair.network");
    default:
      return errorText(e);
  }
}
