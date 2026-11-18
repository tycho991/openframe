import { describe, expect, it } from "vitest";
import { ApiError } from "./api/client";
import de from "./i18n/de.json";
import en from "./i18n/en.json";
import { i18n } from "./i18n/i18n.svelte";
import { pairingErrorText } from "./pairingError";

describe("pairing dialog errors", () => {
  it("counts down the lockout with the seconds from the server", () => {
    i18n.lang = "en";
    const text = pairingErrorText(
      new ApiError(429, "locked", "Too many failed attempts. Try again in 120 s."),
    );
    expect(text).toBe(en["pair.locked"].replace("{seconds}", "120"));
  });

  it("names the pause after the daily budget without a countdown", () => {
    i18n.lang = "en";
    const message = "Too many wrong codes within 24 hours. Pair by QR code, or try again in 86000 s.";
    const text = pairingErrorText(new ApiError(429, "code_paused", message));
    expect(text).toBe(en["error.code_paused"]);
    expect(text).not.toMatch(/[0-9]/);
    i18n.lang = "de";
    expect(pairingErrorText(new ApiError(429, "code_paused", message))).toBe(de["error.code_paused"]);
  });

  it("uses the general text for other codes", () => {
    i18n.lang = "en";
    expect(pairingErrorText(new ApiError(421, "misdirected_host", "x"))).toContain(
      en["error.misdirected_host"],
    );
    expect(pairingErrorText(new Error("offline"))).toBe(en["pair.network"]);
  });
});
