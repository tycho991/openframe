import { describe, expect, it } from "vitest";
import type { Client } from "./api/client";
import { originText } from "./devices";
import { i18n } from "./i18n/i18n.svelte";

function client(id: string, name: string, extra: Partial<Client> = {}): Client {
  return {
    id,
    name,
    pairedAt: "2026-11-01T10:00:00Z",
    lastSeenAt: "2026-11-01T10:00:00Z",
    current: false,
    pairedVia: null,
    invitedBy: null,
    ...extra,
  };
}

describe("Origin of a paired device", () => {
  const phone = client("aaaaaaaaaaaaaaaa", "Anna's phone", { pairedVia: "code" });
  const qr = client("bbbbbbbbbbbbbbbb", "Laptop", { pairedVia: "qr" });
  const tablet = client("cccccccccccccccc", "Tablet", { pairedVia: "invite", invitedBy: phone.id });
  const orphan = client("dddddddddddddddd", "Visitor", {
    pairedVia: "invite",
    invitedBy: "eeeeeeeeeeeeeeee",
  });
  const old = client("ffffffffffffffff", "Old phone");
  const all = [phone, qr, tablet, orphan, old];

  it("names each way of pairing in English", () => {
    i18n.lang = "en";
    expect(originText(phone, all)).toBe("Paired with the code");
    expect(originText(qr, all)).toBe("Paired with the QR code");
    expect(originText(tablet, all)).toBe("Invited by “Anna's phone”");
    expect(originText(orphan, all)).toBe("Invited by a removed device");
    expect(originText(old, all)).toBe("Origin unknown");
  });

  it("follows a rename of the inviting device", () => {
    i18n.lang = "en";
    const renamed = { ...phone, name: "Kitchen phone" };
    expect(originText(tablet, [renamed, tablet])).toBe("Invited by “Kitchen phone”");
  });

  it("names each way of pairing in German", () => {
    i18n.lang = "de";
    expect(originText(phone, all)).toBe("Gekoppelt mit dem Code");
    expect(originText(qr, all)).toBe("Gekoppelt mit dem QR-Code");
    expect(originText(tablet, all)).toBe("Eingeladen von „Anna's phone“");
    expect(originText(orphan, all)).toBe("Eingeladen von einem entfernten Gerät");
    expect(originText(old, all)).toBe("Herkunft unbekannt");
  });
});
