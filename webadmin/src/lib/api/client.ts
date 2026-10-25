import type { components } from "./schema";

type S = components["schemas"];
export type Info = S["Info"];
export type Settings = S["Settings"];
export type SettingsPatch = S["SettingsPatch"];
export type Photo = S["Photo"];
export type PhotoPage = S["PhotoPage"];
export type Album = S["Album"];
export type Client = S["Client"];
export type Invite = S["Invite"];
export type SkippedFile = S["SkippedFile"];
export type State = S["State"];
export type BatchResult = S["BatchResult"];
export type UploadResult = S["UploadResult"];
export type Source = S["Source"];
export type SourceKind = S["SourceKind"];
export type SourceSettings = S["SourceSettings"];
export type SourceSettingsPatch = S["SourceSettingsPatch"];
export type SourceStatus = S["SourceStatus"];
export type ShareRequest = S["ShareRequest"];
export type SourceLogin = S["SourceLogin"];
export type SourceLoginState = S["SourceLoginState"];
export type PhotoSort = "date_desc" | "date_asc" | "name_asc" | "name_desc";

/** Error response of the API with code and optional errors per field. */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
    readonly fields: Record<string, string> = {},
  ) {
    super(message);
    this.name = "ApiError";
  }
}

export interface TokenStore {
  get(): string | null;
  set(token: string | null): void;
}

export interface PhotoQuery {
  album?: string;
  visible?: boolean;
  sort?: PhotoSort;
  cursor?: string | null;
  limit?: number;
}

export interface ApiOptions {
  tokens: TokenStore;
  /** Called on 401 on a protected route after the token has been discarded. */
  onUnauthorized: () => void;
  base?: string;
  fetch?: typeof fetch;
}

export type Api = ReturnType<typeof createApi>;

export function createApi(options: ApiOptions) {
  const base = options.base ?? "/api/v1";
  const doFetch = options.fetch ?? ((input, init) => fetch(input, init));

  async function call<T>(method: string, path: string, body?: unknown, auth = true): Promise<T> {
    const headers: Record<string, string> = { Accept: "application/json" };
    const token = options.tokens.get();
    if (auth && token) headers["Authorization"] = `Bearer ${token}`;
    if (body !== undefined) headers["Content-Type"] = "application/json";
    const res = await doFetch(base + path, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    if (res.status === 204) return undefined as T;
    const text = await res.text();
    const json: unknown = text ? JSON.parse(text) : undefined;
    if (!res.ok) throw toError(res.status, json, auth);
    return json as T;
  }

  function toError(status: number, json: unknown, auth: boolean): ApiError {
    const error = (json as { error?: { code?: string; message?: string; fields?: Record<string, string> } })
      ?.error;
    if (status === 401 && auth) {
      options.tokens.set(null);
      options.onUnauthorized();
    }
    return new ApiError(
      status,
      error?.code ?? "http_" + status,
      error?.message ?? `HTTP ${status}`,
      error?.fields ?? {},
    );
  }

  function query(params: Record<string, string | number | boolean | null | undefined>): string {
    const q = new URLSearchParams();
    for (const [k, v] of Object.entries(params))
      if (v !== undefined && v !== null && v !== "") q.set(k, String(v));
    const s = q.toString();
    return s ? "?" + s : "";
  }

  return {
    info: () => call<Info>("GET", "/info", undefined, false),
    pair: async (code: string, deviceName?: string) => {
      const res = await call<S["PairingResponse"]>(
        "POST",
        "/pairing",
        { code, ...(deviceName ? { deviceName } : {}) },
        false,
      );
      options.tokens.set(res.token);
      return res;
    },
    /** Pairs with the secret from a pairing link (QR code on the frame or invitation). The name comes from the user agent. */
    pairWithSecret: async (secret: string) => {
      const res = await call<S["PairingResponse"]>("POST", "/pairing", { secret }, false);
      options.tokens.set(res.token);
      return res;
    },
    showCodeOnFrame: () => call<void>("POST", "/pairing/code", undefined, false),
    clients: () => call<S["ClientList"]>("GET", "/clients").then((r) => r.clients),
    deleteClient: (id: string) => call<void>("DELETE", `/clients/${encodeURIComponent(id)}`),
    renameClient: (id: string, name: string) =>
      call<Client>("PATCH", `/clients/${encodeURIComponent(id)}`, { name }),
    createInvite: () => call<Invite>("POST", "/pairing/invites"),
    settings: () => call<Settings>("GET", "/settings"),
    patchSettings: (patch: SettingsPatch) => call<Settings>("PATCH", "/settings", patch),
    photos: (q: PhotoQuery = {}) => call<PhotoPage>("GET", "/photos" + query({ ...q })),
    setPhotoHidden: (id: string, hidden: boolean) => call<Photo>("PATCH", `/photos/${id}`, { hidden }),
    deletePhoto: (id: string) => call<void>("DELETE", `/photos/${id}`),
    batch: (action: "hide" | "show" | "delete", ids: string[]) =>
      call<BatchResult>("POST", "/photos:batch", { action, ids }),
    skippedFiles: () => call<S["SkippedFileList"]>("GET", "/skipped-files").then((r) => r.items),
    albums: () => call<S["AlbumList"]>("GET", "/albums").then((r) => r.albums),
    setAlbumHidden: (name: string, hidden: boolean) =>
      call<Album>("PATCH", `/albums/${encodeURIComponent(name)}`, { hidden }),
    state: () => call<State>("GET", "/state"),
    sources: () => call<S["SourceList"]>("GET", "/sources").then((r) => r.sources),
    addShare: (share: Omit<ShareRequest, "kind">) =>
      call<Source>("POST", "/sources", { kind: "nextcloud-share", ...share } satisfies ShareRequest),
    patchSource: (id: string, patch: SourceSettingsPatch) =>
      call<Source>("PATCH", `/sources/${encodeURIComponent(id)}`, patch),
    removeSource: (id: string) => call<void>("DELETE", `/sources/${encodeURIComponent(id)}`),
    syncSource: (id: string) => call<S["SyncRequest"]>("POST", `/sources/${encodeURIComponent(id)}/sync`),
    startSourceLogin: (serverUrl: string) => call<SourceLogin>("POST", "/sources/login", { serverUrl }),
    pollSourceLogin: (flowId: string) =>
      call<SourceLoginState>("GET", `/sources/login/${encodeURIComponent(flowId)}`),

    /** Uploads a file, with progress from 0 to 1. Through XHR, because fetch knows no upload progress. */
    upload(file: File, album: string | null, onProgress: (fraction: number) => void): Promise<UploadResult> {
      return new Promise((resolve, reject) => {
        const form = new FormData();
        if (album) form.append("album", album);
        form.append("file", file, file.name);
        const xhr = new XMLHttpRequest();
        xhr.open("POST", base + "/photos");
        const token = options.tokens.get();
        if (token) xhr.setRequestHeader("Authorization", `Bearer ${token}`);
        xhr.upload.onprogress = (e) => {
          if (e.lengthComputable) onProgress(e.loaded / e.total);
        };
        xhr.onload = () => {
          let json: unknown;
          try {
            json = xhr.responseText ? JSON.parse(xhr.responseText) : undefined;
          } catch {
            json = undefined;
          }
          if (xhr.status === 201) {
            onProgress(1);
            resolve(json as UploadResult);
          } else {
            reject(toError(xhr.status, json, true));
          }
        };
        xhr.onerror = () => reject(new ApiError(0, "network", "Network error"));
        xhr.send(form);
      });
    },
  };
}

/** Token in `localStorage`, per browser. Falls back to memory if access fails. */
export function localTokenStore(key = "openframe.token"): TokenStore {
  let memory: string | null = null;
  return {
    get() {
      try {
        return localStorage.getItem(key) ?? memory;
      } catch {
        return memory;
      }
    },
    set(token) {
      memory = token;
      try {
        if (token) localStorage.setItem(key, token);
        else localStorage.removeItem(key);
      } catch {
        // Private mode or similar: memory only.
      }
    },
  };
}
