import { describe, expect, it, vi } from "vitest";
import { createApi, type TokenStore } from "./api/client";
import de from "./i18n/de.json";
import en from "./i18n/en.json";
import { errorMessage } from "./i18n/errors";
import { installRefusalKeys, updateErrorKeys } from "./i18n/errorCodes";
import { i18n, t, tOr } from "./i18n/i18n.svelte";
import { ApiError } from "./api/client";
import { hasUnsavedInput, markUnsaved, reloadAction, updatePollMs } from "./update";

const tokens: TokenStore = { get: () => "abc", set: () => {} };

function respond(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

const ready = {
  installedVersion: "0.3.0",
  phase: "ready",
  latest: { version: "0.4.0", notes: "Fixes", publishedAt: "2026-11-01T10:00:00Z" },
  lastCheck: { at: "2026-11-04T10:00:00Z", result: "update_found" },
  error: null,
  installAllowed: true,
};

// Spec web-admin: Software update in the interface, Unknown values from the frame (design decision 12).
describe("Update in the API client", () => {
  it("reads the state, asks for a check and for the installation", async () => {
    const fetch = vi.fn(async (_: RequestInfo | URL, init?: RequestInit) =>
      respond(init?.method === "GET" ? 200 : 202, ready),
    );
    const api = createApi({ tokens, onUnauthorized: () => {}, fetch });
    expect((await api.update()).phase).toBe("ready");
    await api.checkUpdate();
    await api.installUpdate();
    expect(fetch.mock.calls.map(([url, init]) => `${init?.method} ${String(url)}`)).toEqual([
      "GET /api/v1/update",
      "POST /api/v1/update/check",
      "POST /api/v1/update/install",
    ]);
  });

  it("a refused installation is an ApiError with its code and a text of its own", async () => {
    const api = createApi({
      tokens,
      onUnauthorized: () => {},
      fetch: async () => respond(409, { error: { code: "install_cooldown", message: "declined" } }),
    });
    const e = await api.installUpdate().catch((x: unknown) => x);
    expect(e).toBeInstanceOf(ApiError);
    expect((e as ApiError).code).toBe("install_cooldown");
    i18n.lang = "en";
    expect(errorMessage(e)).toBe(en["error.install_cooldown"]);
  });
});

describe("Texts of the update", () => {
  it("t() is total: undefined gives an empty text, an unknown key itself", () => {
    expect(() => t(undefined)).not.toThrow();
    expect(t(undefined)).toBe("");
    expect(tOr("update.phase.resting", "resting")).toBe("resting");
    i18n.lang = "de";
    expect(tOr("update.phase.checking", "checking")).toBe(de["update.phase.checking"]);
  });

  it("every code has a text in both languages, and the texts differ", () => {
    for (const keys of [updateErrorKeys, installRefusalKeys]) {
      const values = Object.values(keys);
      expect(new Set(values).size).toBe(values.length);
      for (const key of values) {
        expect(key in en, `en: ${key}`).toBe(true);
        expect(key in de, `de: ${key}`).toBe(true);
      }
    }
    // The section looks the texts up by the code; the maps keep them complete for the type check.
    for (const [code, key] of Object.entries(updateErrorKeys)) expect(key).toBe(`update.error.${code}`);
  });

  it("the help text of the switch names GitHub in both languages", () => {
    for (const catalog of [en, de]) {
      expect(catalog["settings.autoUpdateCheckHint"]).toContain("GitHub");
      expect(catalog["settings.autoUpdateCheckHint"]).toContain("api.github.com");
    }
  });
});

describe("Reload after a frame update", () => {
  const options = { dev: false, unsaved: false, reloadedFor: null };

  it("reloads when the frame runs another version", () => {
    expect(reloadAction("0.3.0", "0.4.0", options)).toBe("reload");
    expect(reloadAction("0.3.0", "0.3.0", options)).toBe("none");
  });

  it("offers a notice with unsaved input, or when a reload for that version did not help", () => {
    expect(reloadAction("0.3.0", "0.4.0", { ...options, unsaved: true })).toBe("notice");
    expect(reloadAction("0.3.0", "0.4.0", { ...options, reloadedFor: "0.4.0" })).toBe("notice");
    expect(reloadAction("0.3.0", "0.5.0", { ...options, reloadedFor: "0.4.0" })).toBe("reload");
  });

  it("does nothing in dev mode or for a version that is not X.Y.Z", () => {
    expect(reloadAction("0.3.0", "0.4.0", { ...options, dev: true })).toBe("none");
    expect(reloadAction("0.3.0", "0.1.0-test", options)).toBe("none");
    expect(reloadAction("0.0.0-dev", "0.4.0", options)).toBe("none");
  });

  it("knows unsaved input by view", () => {
    expect(hasUnsavedInput()).toBe(false);
    markUnsaved("comment", true);
    markUnsaved("settings", false);
    expect(hasUnsavedInput()).toBe(true);
    markUnsaved("comment", false);
    expect(hasUnsavedInput()).toBe(false);
  });

  it("polls often while something runs", () => {
    expect(updatePollMs("checking")).toBe(2_000);
    expect(updatePollMs("installing")).toBe(2_000);
    expect(updatePollMs("ready")).toBe(30_000);
    expect(updatePollMs(undefined)).toBe(30_000);
  });
});
