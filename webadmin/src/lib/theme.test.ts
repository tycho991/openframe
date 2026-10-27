// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { applyTheme, ThemeStore } from "./theme.svelte";

describe("Theme choice", () => {
  beforeEach(() => {
    localStorage.clear();
    document.documentElement.removeAttribute("data-theme");
  });

  afterEach(() => vi.restoreAllMocks());

  it("starts as auto without data-theme", () => {
    const store = new ThemeStore();
    expect(store.choice).toBe("auto");
    applyTheme(store.choice);
    expect(document.documentElement.hasAttribute("data-theme")).toBe(false);
  });

  it("stores a choice and reads it back after a reload", () => {
    new ThemeStore().choose("light");
    expect(document.documentElement.getAttribute("data-theme")).toBe("light");
    expect(new ThemeStore().choice).toBe("light");
  });

  it("removes data-theme and the stored choice for auto", () => {
    const store = new ThemeStore();
    store.choose("dark");
    store.choose("auto");
    expect(document.documentElement.hasAttribute("data-theme")).toBe(false);
    expect(new ThemeStore().choice).toBe("auto");
  });

  it("keeps the choice in memory when storage throws", () => {
    vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
      throw new DOMException("quota", "QuotaExceededError");
    });
    vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => {
      throw new DOMException("denied", "SecurityError");
    });
    const store = new ThemeStore();
    expect(store.choice).toBe("auto");
    expect(() => store.choose("dark")).not.toThrow();
    expect(store.choice).toBe("dark");
    expect(document.documentElement.getAttribute("data-theme")).toBe("dark");
  });

  it("sets the browser's theme colour", () => {
    applyTheme("dark");
    expect(document.querySelectorAll('meta[name="theme-color"]')).toHaveLength(1);
  });
});
