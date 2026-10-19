import { createApi, localTokenStore, type Info } from "./api/client";

export const tokens = localTokenStore();

/** State of the session: paired or not, and the versions by which changes are detectable. */
class Session {
  paired = $state(tokens.get() !== null);
  info = $state<Info | null>(null);
  libraryVersion = $state(-1);
  settingsVersion = $state(-1);
}

export const session = new Session();

export const api = createApi({
  tokens,
  onUnauthorized: () => {
    session.paired = false;
  },
});

export const STATE_POLL_MS = 10_000;

/**
 * Polls the versions every 10 seconds while the tab is visible, and
 * immediately when it becomes visible again. Changes over USB or from a
 * second browser thus appear without reloading.
 */
export function startStatePolling(): () => void {
  let stopped = false;
  const poll = async () => {
    if (stopped || !session.paired || document.visibilityState !== "visible") return;
    try {
      const s = await api.state();
      session.libraryVersion = s.libraryVersion;
      session.settingsVersion = s.settingsVersion;
    } catch {
      // Frame briefly unreachable: try again next time.
    }
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
