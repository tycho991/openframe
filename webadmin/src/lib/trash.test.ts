import { describe, expect, it } from "vitest";
import type { Client, TrashEntry } from "./api/client";
import { ApiError } from "./api/client";
import de from "./i18n/de.json";
import en from "./i18n/en.json";
import { errorMessage } from "./i18n/errors";
import { i18n } from "./i18n/i18n.svelte";
import { chunks, deletedByText, entryName, keptUntilText, summaryText } from "./trash";

function client(id: string, name: string, current = false): Client {
  return {
    id,
    name,
    current,
    pairedAt: "2026-11-01T10:00:00Z",
    lastSeenAt: "2026-11-01T10:00:00Z",
    pairedVia: "code",
    invitedBy: null,
  };
}

function entry(extra: Partial<TrashEntry> = {}): TrashEntry {
  return {
    id: "1795400000000-0000000a",
    photoId: "local-" + "a".repeat(40),
    path: "holiday/beach.jpg",
    album: "holiday",
    fileSize: 1_500_000,
    deletedAt: "2026-11-20T10:00:00Z",
    purgeAfter: "2026-11-27T10:00:00Z",
    deletedBy: "aaaaaaaaaaaaaaaa",
    quarantined: false,
    damaged: false,
    thumb: "/api/v1/trash/1795400000000-0000000a/thumb?t=x",
    ...extra,
  };
}

describe("Trash in the interface", () => {
  const me = client("aaaaaaaaaaaaaaaa", "Laptop", true);
  const phone = client("bbbbbbbbbbbbbbbb", "Anna's phone");

  it("names the deleting device", () => {
    i18n.lang = "en";
    expect(deletedByText(entry(), [me, phone])).toBe("by this device");
    expect(deletedByText(entry({ deletedBy: phone.id }), [me, phone])).toBe("by “Anna's phone”");
    expect(deletedByText(entry({ deletedBy: "cccccccccccccccc" }), [me, phone])).toBe("by a removed device");
    expect(deletedByText(entry({ deletedBy: null }), [me, phone])).toBe("by an unknown device");
    i18n.lang = "de";
    expect(deletedByText(entry({ deletedBy: phone.id }), [me, phone])).toBe("von „Anna's phone“");
    expect(deletedByText(entry({ deletedBy: "cccccccccccccccc" }), [me, phone])).toBe(
      "von einem entfernten Gerät",
    );
  });

  it("says until when an entry is kept and that storage pressure may end it a day after deleting", () => {
    i18n.lang = "en";
    const kept = keptUntilText(entry());
    expect(kept).toMatch(/^Kept until .*2026/);
    expect(kept).toContain("at the earliest a day after deleting");
    expect(keptUntilText(entry({ purgeAfter: null }))).toBe(en["trash.keptUnknown"]);
    i18n.lang = "de";
    expect(keptUntilText(entry())).toContain("frühestens einen Tag nach dem Löschen");
  });

  it("names a damaged entry and sums up the trash", () => {
    i18n.lang = "en";
    expect(entryName(entry())).toBe("beach.jpg");
    expect(entryName(entry({ path: null, damaged: true }))).toBe("Damaged entry");
    expect(summaryText(1, 1_500_000)).toBe("1 deleted photo in the trash (1.5 MB)");
    expect(summaryText(3, 4_000_000)).toBe("3 deleted photos in the trash (4.0 MB)");
    i18n.lang = "de";
    expect(summaryText(3, 4_000_000)).toBe("3 gelöschte Fotos im Papierkorb (4,0 MB)");
  });

  it("restores all in requests of at most 500 IDs", () => {
    const ids = Array.from({ length: 1203 }, (_, i) => String(i));
    expect(chunks(ids).map((c) => c.length)).toEqual([500, 500, 203]);
    expect(chunks([])).toEqual([]);
  });

  it("explains a full storage with the trash and without a fixed size, in both languages", () => {
    const refused = new ApiError(507, "insufficient_storage", "Not enough storage");
    i18n.lang = "en";
    const english = errorMessage(refused);
    expect(english).toBe(en["error.insufficient_storage"]);
    expect(english).toContain("trash");
    expect(english).toContain("last day");
    i18n.lang = "de";
    const german = errorMessage(refused);
    expect(german).toBe(de["error.insufficient_storage"]);
    expect(german).toContain("Papierkorb");
    for (const text of [english, german]) expect(text).not.toMatch(/100\s*MB/);
  });
});
