import { describe, expect, it, vi } from "vitest";
import type { SourceErrorCode } from "./api/client";
import de from "./i18n/de.json";
import en from "./i18n/en.json";
import { sourceErrorKeys } from "./i18n/errorCodes";
import {
  formatTime,
  isPlainHttp,
  isShareLink,
  isWebAddress,
  type LoginOutcome,
  pollLogin,
  settingsPatch,
  sourceError,
  statusLine,
} from "./sources";

const base = {
  name: "cloud.example.org",
  enabled: true,
  folder: "/",
  includeSubfolders: true,
  removeVanished: true,
  intervalMinutes: 20,
  originalsBudgetMb: 2000,
};

const status = {
  syncing: false,
  waiting: false,
  lastSyncAt: "2026-10-22T10:00:00Z",
  ok: true as boolean | null,
  error: null as string | null,
  errorCode: null as SourceErrorCode | null,
  rejected: false,
  added: 2,
  updated: 1,
  removed: 0,
};

describe("Source settings", () => {
  it("sends only what changed", () => {
    expect(settingsPatch(base, { ...base })).toEqual({});
    expect(settingsPatch(base, { ...base, folder: "/Photos", intervalMinutes: 45 })).toEqual({
      folder: "/Photos",
      intervalMinutes: 45,
    });
    expect(settingsPatch(base, { ...base, name: "Family" })).toEqual({ name: "Family" });
    expect(settingsPatch(base, { ...base, removeVanished: false })).toEqual({ removeVanished: false });
  });

  it("recognizes an address without encryption", () => {
    expect(isPlainHttp("http://nas.local/nextcloud")).toBe(true);
    expect(isPlainHttp("  HTTP://x.org")).toBe(true);
    expect(isPlainHttp("https://cloud.example.org")).toBe(false);
    expect(isPlainHttp("")).toBe(false);
  });

  it("formats a time and survives a wrong one", () => {
    expect(formatTime(null, "en")).toBe("");
    expect(formatTime("not a date", "en")).toBe("not a date");
    expect(formatTime("2026-10-22T10:00:00Z", "en")).toMatch(/2026/);
  });
});

describe("Share links", () => {
  it("accepts the forms Nextcloud hands out", () => {
    for (const link of [
      "https://cloud.example.org/s/AbC123",
      "https://cloud.example.org/index.php/s/AbC123",
      "https://nas.local:8443/nextcloud/s/AbC123/",
      "http://192.168.100.20/s/AbC123",
      "  https://cloud.example.org/s/AbC123?dir=/  ",
    ]) {
      expect(isShareLink(link), link).toBe(true);
    }
  });

  it("refuses what is no share link", () => {
    for (const link of [
      "",
      "cloud.example.org/s/AbC123",
      "ftp://cloud.example.org/s/AbC123",
      "https://cloud.example.org/apps/files/",
      "https://cloud.example.org/s/",
      "https://cloud.example.org/s/AbC123/extra",
      "https://cloud.example.org/s/Ab-C",
      "https://user:pw@cloud.example.org/s/AbC123",
    ]) {
      expect(isShareLink(link), link).toBe(false);
    }
  });
});

describe("Status line", () => {
  it("says whether a sync runs, waits for another source, never ran, worked or failed", () => {
    expect(statusLine({ ...status, syncing: true }, "en").key).toBe("sources.syncing");
    expect(statusLine({ ...status, waiting: true }, "en").key).toBe("sources.waitingForOther");
    expect(statusLine({ ...status, ok: null, lastSyncAt: null }, "en").key).toBe("sources.never");
    const ok = statusLine(status, "en");
    expect(ok.key).toBe("sources.lastOk");
    expect(ok.failed).toBe(false);
    expect(ok.params).toMatchObject({ added: 2, updated: 1, removed: 0 });
    expect(String(ok.params["time"])).toMatch(/2026/);
    const failed = statusLine(
      { ...status, ok: false, error: "The share refused the password (HTTP 401)" },
      "en",
    );
    expect(failed.key).toBe("sources.lastFailed");
    expect(failed.failed).toBe(true);
    expect(failed.params["error"]).toBe("The share refused the password (HTTP 401)");
  });
});

describe("Source errors in the interface language", () => {
  it("every code of the contract has a text in both languages", () => {
    const codes = Object.keys(sourceErrorKeys);
    expect(codes).toContain("unreachable");
    expect(codes).toContain("too_many_sources");
    for (const key of Object.values(sourceErrorKeys)) {
      expect(key in en, `en: ${key}`).toBe(true);
      expect(key in de, `de: ${key}`).toBe(true);
    }
  });

  it("shows the text of the code, with the frame's text as a detail", () => {
    const line = statusLine(
      {
        ...status,
        ok: false,
        error: "The server cloud.example.org did not answer in time",
        errorCode: "unreachable",
      },
      "de",
    );
    expect(line.key).toBe("sources.lastFailedReason");
    expect(line.reason).toBe("sources.error.unreachable");
    expect(line.detail).toBe("The server cloud.example.org did not answer in time");
    expect(line.failed).toBe(true);
    expect(line.daily).toBe(false);
  });

  it("says that the frame tries once a day only in the state of rejected access", () => {
    const failed = { ...status, ok: false, error: "The server refused the credentials (HTTP 401)" };
    const rejected = statusLine({ ...failed, errorCode: "unauthorized", rejected: true }, "en");
    expect(rejected.daily).toBe(true);
    expect(rejected.reason).toBe("sources.error.unauthorized");
    // One refused file, or a refusal of credentials that were replaced since: retried as usual.
    expect(statusLine({ ...failed, errorCode: "unauthorized", rejected: false }, "en").daily).toBe(false);
    for (const code of ["unauthorized", "gone", "not_readable"] as const) {
      expect(en[sourceErrorKeys[code]]).not.toMatch(/once a day/);
      expect(de[sourceErrorKeys[code]]).not.toMatch(/einmal am Tag/);
    }
  });

  it("falls back to the frame's text without a code, and shows an unknown code as it is", () => {
    expect(sourceError(null, "The server was not found")).toEqual({
      text: "The server was not found",
      detail: null,
    });
    const old = statusLine(
      { ...status, ok: false, error: "The server was not found", errorCode: null },
      "en",
    );
    expect(old.key).toBe("sources.lastFailed");
    expect(old.params["error"]).toBe("The server was not found");
    const unknown = sourceError("renamed_later", "Some text");
    expect(unknown.reason).toBeUndefined();
    expect(unknown.text).toBe("renamed_later: Some text");
    expect(sourceError("renamed_later", null).text).toBe("renamed_later");
    expect(sourceError("too_many_sources", "The frame has 8 sources already").reason).toBe(
      "error.too_many_sources",
    );
  });

  it("opens only http and https addresses as the login page", () => {
    expect(isWebAddress("https://cloud.example.org/index.php/login/v2/flow/abc")).toBe(true);
    expect(isWebAddress("http://192.168.100.215/index.php/login/v2/flow/abc")).toBe(true);
    for (const bad of [
      "javascript:alert(1)",
      "data:text/html,hi",
      "file:///etc/passwd",
      "",
      "cloud.example.org",
    ]) {
      expect(isWebAddress(bad), bad).toBe(false);
    }
  });
});

describe("Polling a login", () => {
  type State = Awaited<ReturnType<Parameters<typeof pollLogin>[0]>>;
  const pending: State = { state: "pending", user: null, sourceId: null, error: null, errorCode: null };

  /** Runs a poll whose frame answers [answers] one after the other; returns the outcome and the number of questions. */
  async function run(answers: (State | Error)[]) {
    vi.useFakeTimers();
    try {
      let asked = 0;
      let outcome: LoginOutcome | null = null;
      let failure: unknown = null;
      pollLogin(
        async () => {
          const answer = answers[Math.min(asked++, answers.length - 1)] ?? pending;
          if (answer instanceof Error) throw answer;
          return answer;
        },
        "flow-1",
        (o) => (outcome = o),
        (e) => (failure = e),
        2000,
      );
      for (let i = 0; i < answers.length; i++) await vi.advanceTimersByTimeAsync(2000);
      return { outcome: outcome as LoginOutcome | null, asked, failure };
    } finally {
      vi.useRealTimers();
    }
  }

  it("asks again while pending and ends with done", async () => {
    const result = await run([pending, pending, { ...pending, state: "done", user: "Anna", sourceId: "s1" }]);
    expect(result.asked).toBe(3);
    expect(result.outcome).toEqual({ state: "done", sourceId: "s1" });
  });

  it("ends with a failure in the interface language, other_account included", async () => {
    const result = await run([
      {
        ...pending,
        state: "failed",
        error: "The login was approved by another account",
        errorCode: "other_account",
      },
    ]);
    expect(result.outcome?.state).toBe("failed");
    const error = result.outcome?.state === "failed" ? result.outcome.error : null;
    expect(error?.reason).toBe("sources.error.other_account");
    expect(error?.detail).toBe("The login was approved by another account");
  });

  it("ends with expired and stops asking", async () => {
    const result = await run([pending, { ...pending, state: "expired" }, pending, pending]);
    expect(result.outcome).toEqual({ state: "expired" });
    expect(result.asked).toBe(2);
  });

  it("hands over the error of a question that failed", async () => {
    const result = await run([new Error("offline")]);
    expect(result.outcome).toBeNull();
    expect(String(result.failure)).toContain("offline");
  });

  it("drops an answer that comes after it was stopped", async () => {
    vi.useFakeTimers();
    try {
      let outcome: LoginOutcome | null = null;
      const stop = pollLogin(
        async () => ({ ...pending, state: "done" as const, sourceId: "s1" }),
        "flow-1",
        (o) => (outcome = o),
        () => {},
      );
      stop();
      await vi.advanceTimersByTimeAsync(5000);
      expect(outcome).toBeNull();
    } finally {
      vi.useRealTimers();
    }
  });
});
