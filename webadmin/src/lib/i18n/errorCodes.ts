import type { InstallRefusal, SkippedFile, UpdateError } from "../api/client";
import type de from "./de.json";

type Key = keyof typeof de;

/** The text of every skip reason; a reason the contract gains without a text fails the type check. */
export const skipReasonKeys: Record<SkippedFile["reason"], Key> = {
  unsupported: "skipped.unsupported",
  corrupt: "skipped.corrupt",
  too_large: "skipped.too_large",
  crashed: "skipped.crashed",
};

/** The text of every error code of the update state; a code the contract gains without a text fails the type check. */
export const updateErrorKeys: Record<UpdateError["code"], Key> = {
  update_unreachable: "update.error.update_unreachable",
  update_rate_limited: "update.error.update_rate_limited",
  update_not_found: "update.error.update_not_found",
  update_no_apk: "update.error.update_no_apk",
  update_no_checksum: "update.error.update_no_checksum",
  update_checksum: "update.error.update_checksum",
  update_invalid_apk: "update.error.update_invalid_apk",
  update_signature: "update.error.update_signature",
  update_storage: "update.error.update_storage",
  update_tls: "update.error.update_tls",
  update_failed: "update.error.update_failed",
  install_cancelled: "update.error.install_cancelled",
  install_unanswered: "update.error.install_unanswered",
  install_failed: "update.error.install_failed",
};

/** The text of every refusal of an installation request, as for [updateErrorKeys]. */
export const installRefusalKeys: Record<InstallRefusal["error"]["code"], Key> = {
  update_not_ready: "error.update_not_ready",
  install_not_allowed: "error.install_not_allowed",
  install_in_progress: "error.install_in_progress",
  install_cooldown: "error.install_cooldown",
};

/**
 * Error codes of the API with a text in the interface language. `invalid_code`, `expired_code` and
 * `locked` are not here: the pairing dialog has texts of its own for them.
 */
export const errorKeys: Record<string, Key> = {
  unauthorized: "error.unauthorized",
  forbidden_origin: "error.forbidden_origin",
  not_found: "error.not_found",
  too_large: "error.too_large",
  unsupported_media_type: "error.unsupported_media_type",
  unsupported_format: "error.unsupported_format",
  malformed_multipart: "error.malformed_multipart",
  invalid_json: "error.invalid_json",
  bad_request: "error.bad_request",
  validation_failed: "error.validation_failed",
  insufficient_storage: "error.insufficient_storage",
  image_too_large: "error.image_too_large",
  storage_unavailable: "error.storage_unavailable",
  decoder_unavailable: "error.decoder_unavailable",
  safe_mode: "error.safe_mode",
  not_in_safe_mode: "error.not_in_safe_mode",
  delete_failed: "error.delete_failed",
  move_failed: "error.move_failed",
  render_failed: "error.render_failed",
  internal_error: "error.internal_error",
  method_not_allowed: "error.method_not_allowed",
  network: "error.network",
  read_only_source: "error.read_only_source",
  invalid_album: "error.invalid_album",
  nextcloud_unreachable: "error.nextcloud_unreachable",
  source_no_folder: "error.source_no_folder",
  too_many_sources: "error.too_many_sources",
  source_exists: "error.source_exists",
  share_not_found: "error.share_not_found",
  share_not_a_folder: "error.share_not_a_folder",
  share_not_readable: "error.share_not_readable",
  share_password_required: "error.share_password_required",
  share_password_wrong: "error.share_password_wrong",
  display_unavailable: "error.display_unavailable",
  display_off: "error.display_off",
  ...installRefusalKeys,
};
