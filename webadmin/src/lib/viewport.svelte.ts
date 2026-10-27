/** The three width classes of the interface (spec `web-admin`, "Navigation by width"; design-system, design.md,
 *  decision 7). The layout itself switches by CSS media queries at the same widths; script asks this store only where
 *  the DOM has to differ, which is the photo detail (a modal dialog below 1200 px, a panel from 1200 px). */

export type WidthClass = "compact" | "medium" | "expanded";

export const MEDIUM_MIN = 600;
export const EXPANDED_MIN = 1200;

export function widthClass(width: number): WidthClass {
  return width >= EXPANDED_MIN ? "expanded" : width >= MEDIUM_MIN ? "medium" : "compact";
}

interface Query {
  readonly matches: boolean;
  addEventListener(type: "change", listener: () => void): void;
}

type MatchMedia = (query: string) => Query;

export class Viewport {
  widthClass = $state<WidthClass>("compact");

  constructor(match: MatchMedia | undefined = globalThis.matchMedia?.bind(globalThis)) {
    if (!match) return;
    const medium = match(`(min-width: ${MEDIUM_MIN}px)`);
    const expanded = match(`(min-width: ${EXPANDED_MIN}px)`);
    const update = () =>
      (this.widthClass = expanded.matches ? "expanded" : medium.matches ? "medium" : "compact");
    medium.addEventListener("change", update);
    expanded.addEventListener("change", update);
    update();
  }
}

export const viewport = new Viewport();
