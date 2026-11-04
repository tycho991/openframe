import { render } from "svelte/server";
import { describe, expect, it } from "vitest";
import { i18n } from "../lib/i18n/i18n.svelte";
import { safeModeBanner } from "../lib/safeMode";
import { session } from "../lib/session.svelte";
import SafeModeBanner from "./SafeModeBanner.svelte";

/** Spec web-admin, "Safe mode notice". */
describe("Safe mode banner", () => {
  it("is hidden outside safe mode, offers to start normally in it, and says when the frame restarts", () => {
    expect(safeModeBanner(false, false)).toBe("hidden");
    expect(safeModeBanner(false, true)).toBe("hidden");
    expect(safeModeBanner(true, false)).toBe("offer");
    expect(safeModeBanner(true, true)).toBe("restarting");
  });

  it("follows the state of the frame", () => {
    i18n.lang = "en";
    session.safeMode = false;
    expect(render(SafeModeBanner).body).not.toContain("Safe mode");
    session.safeMode = true;
    const { body } = render(SafeModeBanner);
    expect(body).toContain("Safe mode");
    expect(body).toContain("Start normally");
    session.safeMode = false;
  });
});
