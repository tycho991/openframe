import type de from "./de.json";

type Key = keyof typeof de;

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
  delete_failed: "error.delete_failed",
  move_failed: "error.move_failed",
  render_failed: "error.render_failed",
  internal_error: "error.internal_error",
  method_not_allowed: "error.method_not_allowed",
  network: "error.network",
};
