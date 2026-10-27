import { describe, expect, it } from "vitest";
import { Viewport, widthClass } from "./viewport.svelte";

/** A window of the given width whose media queries can be resized. */
function fakeWindow(width: number) {
  const queries: { min: number; listeners: (() => void)[]; matches: boolean }[] = [];
  const match = (query: string) => {
    const min = Number(/min-width: (\d+)px/.exec(query)?.[1]);
    const q = {
      min,
      listeners: [] as (() => void)[],
      get matches() {
        return width >= min;
      },
      addEventListener: (_: string, l: () => void) => q.listeners.push(l),
    };
    queries.push(q);
    return q;
  };
  const resize = (to: number) => {
    width = to;
    for (const q of queries) for (const l of q.listeners) l();
  };
  return { match, resize };
}

describe("Width classes", () => {
  it.each([
    [360, "compact"],
    [599, "compact"],
    [600, "medium"],
    [1199, "medium"],
    [1200, "expanded"],
    [1920, "expanded"],
  ])("%i px is %s", (width, expected) => {
    expect(widthClass(width)).toBe(expected);
    expect(new Viewport(fakeWindow(width).match).widthClass).toBe(expected);
  });

  it("follows a change of width", () => {
    const window = fakeWindow(1280);
    const viewport = new Viewport(window.match);
    window.resize(800);
    expect(viewport.widthClass).toBe("medium");
    window.resize(390);
    expect(viewport.widthClass).toBe("compact");
    window.resize(1200);
    expect(viewport.widthClass).toBe("expanded");
  });

  it("is compact without matchMedia", () => {
    expect(new Viewport(undefined).widthClass).toBe("compact");
  });
});
