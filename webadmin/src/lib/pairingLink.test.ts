import { describe, expect, it, vi } from "vitest";
import { formatRemaining, INVITE_VALIDITY_MS, inviteDeadline, takePairingSecret } from "./pairingLink";

function fakeLocation(hash: string, pathname = "/", search = "") {
  return { hash, pathname, search } as Location;
}

function fakeHistory() {
  return { state: { x: 1 }, replaceState: vi.fn() } as unknown as History & {
    replaceState: ReturnType<typeof vi.fn>;
  };
}

describe("pairing link", () => {
  const secret = "AbCdEfGhIjKlMnOpQrSt_-";

  it("takes the secret and removes it from the address", () => {
    const hist = fakeHistory();
    expect(takePairingSecret(fakeLocation(`#pair=${secret}`, "/devices", "?a=1"), hist)).toBe(secret);
    expect(hist.replaceState).toHaveBeenCalledWith({ x: 1 }, "", "/devices?a=1");
  });

  it("removes a malformed secret too, but does not return it", () => {
    const hist = fakeHistory();
    expect(takePairingSecret(fakeLocation("#pair=short"), hist)).toBeNull();
    expect(hist.replaceState).toHaveBeenCalledOnce();
  });

  it("leaves other fragments alone", () => {
    const hist = fakeHistory();
    expect(takePairingSecret(fakeLocation("#top"), hist)).toBeNull();
    expect(takePairingSecret(fakeLocation(""), hist)).toBeNull();
    expect(hist.replaceState).not.toHaveBeenCalled();
  });
});

describe("invitation deadline", () => {
  const now = Date.parse("2026-10-19T10:00:00Z");

  it("follows expiresAt when the clocks agree", () => {
    expect(inviteDeadline("2026-10-19T10:04:30Z", now)).toBe(now + 270_000);
  });

  it("falls back to the validity when the frame's clock is off", () => {
    expect(inviteDeadline("2021-01-01T00:05:00Z", now)).toBe(now + INVITE_VALIDITY_MS);
    expect(inviteDeadline("2026-10-19T12:00:00Z", now)).toBe(now + INVITE_VALIDITY_MS);
  });

  it("formats the remaining time", () => {
    expect(formatRemaining(300_000)).toBe("5:00");
    expect(formatRemaining(61_001)).toBe("1:02");
    expect(formatRemaining(-5)).toBe("0:00");
  });
});
