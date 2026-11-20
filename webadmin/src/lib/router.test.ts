// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from "vitest";

/** The router as a fresh page load at [path] creates it. */
async function loadAt(path: string) {
  history.replaceState(null, "", path);
  vi.resetModules();
  return (await import("./router.svelte")).router;
}

describe("Router", () => {
  beforeEach(() => history.replaceState(null, "", "/"));

  it("Old bookmark: the former albums section shows the photo overview at its address", async () => {
    const router = await loadAt("/albums");
    expect(router.route).toBe("photos");
    expect(location.pathname).toBe("/photos");
  });

  it("has no albums section any more", async () => {
    const { ROUTES } = await import("./router.svelte");
    expect(ROUTES).toEqual(["photos", "sources", "settings", "devices"]);
  });

  it("keeps the address of a section and of an unknown path", async () => {
    expect((await loadAt("/settings")).route).toBe("settings");
    expect(location.pathname).toBe("/settings");
    expect((await loadAt("/nowhere")).route).toBe("photos");
    expect(location.pathname).toBe("/nowhere");
  });
});
