/** Secret of a pairing link: 16 bytes in Base64url without padding. */
const SECRET = /^[A-Za-z0-9_-]{22}$/;

/** Validity of an invitation, as in the API. */
export const INVITE_VALIDITY_MS = 5 * 60_000;

/**
 * Reads the secret from a pairing link (`#pair=<secret>`) and removes it from
 * the address bar and the history right away, so that it neither stays visible
 * nor gets redeemed again on reload. Returns `null` without a valid secret.
 */
export function takePairingSecret(loc: Location = location, hist: History = history): string | null {
  const match = /^#pair=(.*)$/.exec(loc.hash);
  if (!match) return null;
  hist.replaceState(hist.state, "", loc.pathname + loc.search);
  const secret = match[1] ?? "";
  return SECRET.test(secret) ? secret : null;
}

/**
 * When an invitation expires, on this device's clock. The frame's clock may be
 * off, so `expiresAt` counts only if it lies within the validity from [now].
 */
export function inviteDeadline(expiresAt: string, now: number = Date.now()): number {
  const left = Date.parse(expiresAt) - now;
  return now + (left > 0 && left <= INVITE_VALIDITY_MS ? left : INVITE_VALIDITY_MS);
}

/** Remaining time as `m:ss`. */
export function formatRemaining(ms: number): string {
  const seconds = Math.max(0, Math.ceil(ms / 1000));
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, "0")}`;
}
