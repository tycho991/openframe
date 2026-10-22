import { readdirSync, readFileSync, statSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import de from "./de.json";
import en from "./en.json";
import { errorKeys } from "./errorCodes";

/** Codes the pairing dialog turns into texts of its own. */
const handledInPairingDialog = ["invalid_code", "expired_code", "locked"];

function kotlinFiles(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) return kotlinFiles(path);
    return path.endsWith(".kt") ? [path] : [];
  });
}

describe("Error texts", () => {
  it("every code of the API has a text in both languages", () => {
    const codes = new Set<string>();
    for (const file of kotlinFiles("../core/src/main")) {
      const source = readFileSync(file, "utf8");
      for (const m of source.matchAll(/HttpException\(\s*\d{3},\s*"([a-z_]+)"/g)) if (m[1]) codes.add(m[1]);
      for (const m of source.matchAll(
        /HttpException\(\s*\d{3},\s*if \([^)]*\) "([a-z_]+)" else "([a-z_]+)"/g,
      )) {
        if (m[1]) codes.add(m[1]);
        if (m[2]) codes.add(m[2]);
      }
    }
    // Codes of the helpers in Http.kt (`badRequest`, `notFound`) and of the pipeline.
    for (const code of ["bad_request", "validation_failed", "not_found", "unauthorized", "internal_error"])
      codes.add(code);
    expect(codes.size).toBeGreaterThan(10);
    const missing = [...codes].filter((c) => !(c in errorKeys) && !handledInPairingDialog.includes(c));
    expect(missing).toEqual([]);
  });

  it("the keys exist in both catalogs", () => {
    for (const key of Object.values(errorKeys)) {
      expect(key in en, `en: ${key}`).toBe(true);
      expect(key in de, `de: ${key}`).toBe(true);
    }
  });
});
