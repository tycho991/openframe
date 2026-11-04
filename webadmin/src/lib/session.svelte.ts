import { ApiError, createApi, localTokenStore, type Client, type Info } from "./api/client";
import { hasUnsavedInput, reloadAction } from "./update";

export const tokens = localTokenStore();

/** State of the session: paired or not, and the versions by which changes are detectable. */
class Session {
  paired = $state(tokens.get() !== null);
  info = $state<Info | null>(null);
  libraryVersion = $state(-1);
  settingsVersion = $state(-1);
  /** The frame changes photos instantly because its display could not keep up with the transition. */
  transitionFallback = $state(false);
  /** The frame runs in safe mode after repeated crashes: photos rest until it starts normally. */
  safeMode = $state(false);
  /** A pairing link is being redeemed; the interface waits instead of showing the pairing dialog. */
  linkPending = $state(false);
  /** Why the pairing link could not be redeemed, shown in the pairing dialog. */
  linkError = $state<"expired" | "invalid" | "network" | null>(null);
  /** The device that was just paired through a link, for "Paired as …" once. */
  pairedAs = $state<Client | null>(null);
  /** The version the frame runs, from the state. */
  appVersion = $state<string | null>(null);
  /** The frame updated itself to this version and the interface did not reload: a notice offers it. */
  reloadVersion = $state<string | null>(null);
}

export const session = new Session();

export const api = createApi({
  tokens,
  onUnauthorized: () => {
    session.paired = false;
  },
});

/**
 * Redeems the secret of a pairing link. If the browser already has a valid
 * token, the secret is discarded without redeeming it, so the link stays valid.
 */
export async function redeemPairingLink(secret: string): Promise<void> {
  if (tokens.get() !== null) {
    try {
      await api.state();
      return;
    } catch (e) {
      // Only an invalid token (401, already discarded) lets the link pair this browser.
      if (!(e instanceof ApiError && e.status === 401)) return;
    }
  }
  session.linkPending = true;
  try {
    const res = await api.pairWithSecret(secret);
    session.linkError = null;
    session.pairedAs = res.client;
    session.paired = true;
  } catch (e) {
    session.linkError = !(e instanceof ApiError)
      ? "network"
      : e.code === "expired_code"
        ? "expired"
        : "invalid";
  } finally {
    session.linkPending = false;
  }
}

export const STATE_POLL_MS = 10_000;

const RELOADED_KEY = "openframe.reloadedFor";

/** Reloads, or offers to, when the frame runs another version than the interface was built as. */
function checkVersion(reported: string): void {
  let reloadedFor: string | null;
  try {
    reloadedFor = sessionStorage.getItem(RELOADED_KEY);
  } catch {
    // Without storage a reload could repeat; the notice is offered instead.
    reloadedFor = reported;
  }
  const action = reloadAction(__APP_VERSION__, reported, {
    dev: import.meta.env.DEV,
    unsaved: hasUnsavedInput(),
    reloadedFor,
  });
  if (action === "none") {
    session.reloadVersion = null;
  } else if (action === "notice") {
    session.reloadVersion = reported;
  } else {
    try {
      sessionStorage.setItem(RELOADED_KEY, reported);
    } catch {
      // See above.
    }
    location.reload();
  }
}

/** Reads the versions and the transition fallback from the frame now. */
export async function refreshState(): Promise<void> {
  try {
    const s = await api.state();
    session.libraryVersion = s.libraryVersion;
    session.settingsVersion = s.settingsVersion;
    session.transitionFallback = s.transitionFallback;
    session.safeMode = s.safeMode;
    session.appVersion = s.appVersion;
    checkVersion(s.appVersion);
  } catch {
    // Frame briefly unreachable: try again next time.
  }
}

/**
 * Polls the versions every 10 seconds while the tab is visible, and
 * immediately when it becomes visible again. Changes over USB or from a
 * second browser thus appear without reloading.
 */
export function startStatePolling(): () => void {
  let stopped = false;
  const poll = async () => {
    if (stopped || !session.paired || document.visibilityState !== "visible") return;
    await refreshState();
  };
  const timer = setInterval(poll, STATE_POLL_MS);
  const onVisible = () => void poll();
  document.addEventListener("visibilitychange", onVisible);
  void poll();
  return () => {
    stopped = true;
    clearInterval(timer);
    document.removeEventListener("visibilitychange", onVisible);
  };
}
