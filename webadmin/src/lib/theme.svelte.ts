/** Light or dark theme for this browser (spec `web-admin`, "Theme choice"; design-system, design.md, decision 9).
 *  Like the language: `auto` follows the system's colour scheme until the user chooses one. */

export type ThemeChoice = "auto" | "light" | "dark";

const STORAGE_KEY = "openframe.theme";

function stored(): ThemeChoice | null {
  try {
    const v = localStorage.getItem(STORAGE_KEY);
    return v === "light" || v === "dark" ? v : null;
  } catch {
    return null;
  }
}

/** Sets `data-theme` on `<html>` (none for `auto`, so that `color-scheme: light dark` applies) and the browser's
 *  `theme-color` to the page's background. */
export function applyTheme(choice: ThemeChoice, doc: Document = document): void {
  const root = doc.documentElement;
  if (choice === "auto") root.removeAttribute("data-theme");
  else root.setAttribute("data-theme", choice);
  let meta = doc.querySelector<HTMLMetaElement>('meta[name="theme-color"]');
  if (!meta) {
    meta = doc.createElement("meta");
    meta.name = "theme-color";
    doc.head.append(meta);
  }
  const background = doc.body ? doc.defaultView?.getComputedStyle(doc.body).backgroundColor : undefined;
  if (background) meta.content = background;
}

export class ThemeStore {
  choice = $state<ThemeChoice>(stored() ?? "auto");

  /** Chooses a theme for this browser; without storage it applies until the page is reloaded. */
  choose(choice: ThemeChoice): void {
    try {
      if (choice === "auto") localStorage.removeItem(STORAGE_KEY);
      else localStorage.setItem(STORAGE_KEY, choice);
    } catch {
      // The choice stays in memory.
    }
    this.choice = choice;
    applyTheme(choice);
  }
}

export const theme = new ThemeStore();

// With `auto` the background follows the system's scheme, and so does the browser's bar.
globalThis.matchMedia?.("(prefers-color-scheme: dark)").addEventListener("change", () => {
  if (theme.choice === "auto") applyTheme("auto");
});
