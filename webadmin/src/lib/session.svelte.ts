import { createApi, localTokenStore, type Info } from "./api/client";

export const tokens = localTokenStore();

/** Zustand der Sitzung: gekoppelt oder nicht, und die Versionen, an denen Änderungen erkennbar sind. */
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
 * Fragt alle 10 Sekunden die Versionen ab, solange der Tab sichtbar ist, und
 * sofort, wenn er wieder sichtbar wird. Änderungen per USB oder aus einem
 * zweiten Browser erscheinen so ohne Neuladen.
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
      // Rahmen kurz nicht erreichbar: beim nächsten Mal wieder.
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
