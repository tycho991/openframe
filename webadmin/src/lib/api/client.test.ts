import { describe, expect, it, vi } from "vitest";
import { ApiError, createApi, type TokenStore } from "./client";

function memoryTokens(initial: string | null = null): TokenStore {
  let t = initial;
  return { get: () => t, set: (v) => (t = v) };
}

function respond(status: number, body?: unknown): Response {
  return new Response(body === undefined ? null : JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

describe("API-Client", () => {
  it("schickt das Token als Bearer", async () => {
    const fetch = vi.fn(async () => respond(200, { libraryVersion: 1, settingsVersion: 2 }));
    const api = createApi({ tokens: memoryTokens("abc"), onUnauthorized: () => {}, fetch });
    await api.state();
    expect(fetch).toHaveBeenCalledWith(
      "/api/v1/state",
      expect.objectContaining({ headers: expect.objectContaining({ Authorization: "Bearer abc" }) }),
    );
  });

  it("verwirft bei 401 das Token und öffnet den Kopplungsdialog", async () => {
    const tokens = memoryTokens("alt");
    const onUnauthorized = vi.fn();
    const api = createApi({
      tokens,
      onUnauthorized,
      fetch: async () => respond(401, { error: { code: "unauthorized", message: "x" } }),
    });
    await expect(api.settings()).rejects.toMatchObject({ status: 401 });
    expect(tokens.get()).toBeNull();
    expect(onUnauthorized).toHaveBeenCalledOnce();
  });

  it("öffnet bei falschem Code nicht erneut den Dialog", async () => {
    const onUnauthorized = vi.fn();
    const api = createApi({
      tokens: memoryTokens(),
      onUnauthorized,
      fetch: async () => respond(401, { error: { code: "invalid_code", message: "falsch" } }),
    });
    const error = await api.pair("123456").catch((e: unknown) => e);
    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).code).toBe("invalid_code");
    expect(onUnauthorized).not.toHaveBeenCalled();
  });

  it("speichert das Token nach erfolgreicher Kopplung", async () => {
    const tokens = memoryTokens();
    const fetch = vi.fn(async () =>
      respond(201, {
        token: "neu",
        client: { id: "c", name: "Firefox", pairedAt: "", lastSeenAt: "", current: true },
      }),
    );
    const api = createApi({ tokens, onUnauthorized: () => {}, fetch });
    await api.pair("123456", "Laptop");
    expect(tokens.get()).toBe("neu");
    expect(JSON.parse((fetch.mock.calls[0] as unknown as [string, RequestInit])[1].body as string)).toEqual({
      code: "123456",
      deviceName: "Laptop",
    });
  });

  it("liefert Fehler pro Feld", async () => {
    const api = createApi({
      tokens: memoryTokens("t"),
      onUnauthorized: () => {},
      fetch: async () =>
        respond(400, {
          error: { code: "validation_failed", message: "x", fields: { intervalSeconds: "5 bis 86400" } },
        }),
    });
    const error = (await api.patchSettings({ intervalSeconds: 1 }).catch((e: unknown) => e)) as ApiError;
    expect(error.fields).toEqual({ intervalSeconds: "5 bis 86400" });
  });

  it("baut die Abfrage der Fotoliste", async () => {
    const fetch = vi.fn(async () => respond(200, { items: [], total: 0, nextCursor: null }));
    const api = createApi({ tokens: memoryTokens("t"), onUnauthorized: () => {}, fetch });
    await api.photos({ album: "urlaub & co", visible: false, cursor: null, limit: 50 });
    expect((fetch.mock.calls[0] as unknown as [string])[0]).toBe(
      "/api/v1/photos?album=urlaub+%26+co&visible=false&limit=50",
    );
  });
});
