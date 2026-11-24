import type { UpdateState } from "./api/client";

/** How often the software section asks for the update state: often while something runs, as the display otherwise. */
export function updatePollMs(phase: UpdateState["phase"] | undefined): number {
  return phase === "checking" || phase === "downloading" || phase === "installing" ? 2_000 : 30_000;
}

/**
 * Polls the update state with [load] until the returned function is called: the next poll is scheduled after every
 * attempt, failed or not, at [updatePollMs] of the phase [phase] reports then, so that a failed request does not end
 * the polling (spec web-admin, "Software update in the interface"; design decision 9 of core-cleanup).
 */
export function pollUpdate(
  load: () => Promise<void>,
  phase: () => UpdateState["phase"] | undefined,
): () => void {
  let timer: ReturnType<typeof setTimeout> | undefined;
  let stopped = false;
  const schedule = () => {
    if (stopped) return;
    timer = setTimeout(() => {
      void load()
        .catch(() => {})
        .finally(schedule);
    }, updatePollMs(phase()));
  };
  schedule();
  return () => {
    stopped = true;
    clearTimeout(timer);
  };
}

const VERSION = /^\d+\.\d+\.\d+$/;

/**
 * What the interface does when the frame reports [reported] while it was built as [embedded] (spec web-admin, "Reload
 * after a frame update"): nothing unless both are versions and differ, or in Vite's dev mode; a notice while input is
 * unsaved, or when a reload for this version already happened and did not bring a matching interface.
 */
export function reloadAction(
  embedded: string,
  reported: string,
  options: { dev: boolean; unsaved: boolean; reloadedFor: string | null },
): "none" | "reload" | "notice" {
  if (options.dev || !VERSION.test(embedded) || !VERSION.test(reported) || embedded === reported)
    return "none";
  if (options.unsaved || options.reloadedFor === reported) return "notice";
  return "reload";
}

/** Views with input that a reload would lose register here, by a name of their own. */
const unsavedInput = new Set<string>();

export function markUnsaved(view: string, unsaved: boolean): void {
  if (unsaved) unsavedInput.add(view);
  else unsavedInput.delete(view);
}

export function hasUnsavedInput(): boolean {
  return unsavedInput.size > 0;
}
