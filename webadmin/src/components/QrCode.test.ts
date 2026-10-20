import { render } from "svelte/server";
import { describe, expect, it } from "vitest";
import QrCode from "./QrCode.svelte";

describe("QR code", () => {
  const rows = ["1110", "0101", "1111", "0000"];

  it("draws every dark module, on white with a quiet zone of 4", () => {
    const { body } = render(QrCode, { props: { rows, label: "QR" } });
    expect(body).toContain('viewBox="0 0 12 12"');
    const rects = [...body.matchAll(/<rect x="(\d+)" y="(\d+)" width="(\d+)" height="1" fill="#000"/g)];
    const dark = rows
      .join("")
      .split("")
      .filter((c) => c === "1").length;
    expect(rects.reduce((sum, r) => sum + Number(r[3]), 0)).toBe(dark);
    // Runs: 3 + 1 + 1 + 4 modules, starting after the quiet zone.
    expect(rects.map((r) => [Number(r[1]), Number(r[2]), Number(r[3])])).toEqual([
      [4, 4, 3],
      [5, 5, 1],
      [7, 5, 1],
      [4, 6, 4],
    ]);
    expect(body).toContain('<rect width="12" height="12" fill="#fff"');
    expect(body).toContain('aria-label="QR"');
  });
});
