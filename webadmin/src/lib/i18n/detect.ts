export type Lang = "de" | "en";

export const LANGS: Lang[] = ["de", "en"];

/** The first supported language from the browser languages, otherwise English. */
export function detectLanguage(preferred: readonly string[]): Lang {
  for (const tag of preferred) {
    const base = tag.toLowerCase().split("-")[0];
    if (base === "de" || base === "en") return base;
  }
  return "en";
}

/** Replaces `{name}` in [template] with the values from [params]. */
export function format(template: string, params: Record<string, string | number> = {}): string {
  return template.replace(/\{(\w+)\}/g, (match, key: string) =>
    key in params ? String(params[key]) : match,
  );
}
