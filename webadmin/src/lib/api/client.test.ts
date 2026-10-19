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

describe("API client", () => {
  it("sends the token as Bearer", async () => {
    const fetch = vi.fn(async () => respond(200, { libraryVersion: 1, settingsVersion: 2 }));
    const api = createApi({ tokens: memoryTokens("abc"), onUnauthorized: () => {}, fetch });
    await api.state();
    expect(fetch).toHaveBeenCalledWith(
      "/api/v1/state",
      expect.objectContaining({ headers: expect.objectContaining({ Authorization: "Bearer abc" }) }),
    );
  });

  it("discards the token on 401 and opens the pairing dialog", async () => {
    const tokens = memoryTokens("old");
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

  it("pairs with a secret from a pairing link and keeps the token", async () => {
    const tokens = memoryTokens();
    let body: unknown;
    const fetch = vi.fn(async (_: RequestInfo | URL, init?: RequestInit) => {
      body = init?.body;
      return respond(201, { token: "t1", client: { id: "c", name: "Firefox on Android" } });
    });
    const api = createApi({ tokens, onUnauthorized: () => {}, fetch });
    await api.pairWithSecret("AbCdEfGhIjKlMnOpQrSt_-");
    expect(body).toBe('{"secret":"AbCdEfGhIjKlMnOpQrSt_-"}');
    expect(tokens.get()).toBe("t1");
  });

  it("does not open the dialog again on a wrong code", async () => {
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

  it("stores the token after successful pairing", async () => {
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

  it("returns errors per field", async () => {
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

  it("builds the photo list query", async () => {
    const fetch = vi.fn(async () => respond(200, { items: [], total: 0, nextCursor: null }));
    const api = createApi({ tokens: memoryTokens("t"), onUnauthorized: () => {}, fetch });
    await api.photos({ album: "holiday & co", visible: false, cursor: null, limit: 50 });
    expect((fetch.mock.calls[0] as unknown as [string])[0]).toBe(
      "/api/v1/photos?album=holiday+%26+co&visible=false&limit=50",
    );
  });
});
