/** What the safe mode banner shows (spec web-admin, "Safe mode notice"). */
export type SafeModeBanner = "hidden" | "offer" | "restarting";

/**
 * Hidden outside safe mode; otherwise it offers "Start normally", and after the request it says that the frame
 * restarts until the frame reports normal mode.
 */
export function safeModeBanner(safeMode: boolean, requested: boolean): SafeModeBanner {
  if (!safeMode) return "hidden";
  return requested ? "restarting" : "offer";
}

/** How often the interface asks the frame while it restarts. */
export const RESTART_POLL_MS = 2_000;
