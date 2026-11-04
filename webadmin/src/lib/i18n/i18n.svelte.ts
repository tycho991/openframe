import de from "./de.json";
import { detectLanguage, format, type Lang } from "./detect";
import en from "./en.json";

export type Key = keyof typeof de;

const catalogs: Record<Lang, Record<Key, string>> = { de, en };
const STORAGE_KEY = "openframe.lang";

function stored(): Lang | null {
  try {
    const v = localStorage.getItem(STORAGE_KEY);
    return v === "de" || v === "en" ? v : null;
  } catch {
    return null;
  }
}

/** Language of the interface: from the browser as long as the user does not choose another. Independent of the language on the frame. */
class I18n {
  lang = $state<Lang>(
    stored() ?? detectLanguage(typeof navigator === "undefined" ? [] : navigator.languages),
  );

  /** Chooses a language for this browser. `null` follows the browser again. */
  choose(lang: Lang | null): void {
    try {
      if (lang) localStorage.setItem(STORAGE_KEY, lang);
      else localStorage.removeItem(STORAGE_KEY);
    } catch {
      // Without storage the choice applies only until reload.
    }
    this.lang = lang ?? detectLanguage(navigator.languages);
    document.documentElement.lang = this.lang;
  }

  get chosen(): Lang | null {
    return stored();
  }
}

export const i18n = new I18n();

/** The text of [key]; an unknown key gives the key itself, `undefined` an empty text, so a view never breaks. */
export function t(key: Key | undefined, params?: Record<string, string | number>): string {
  if (key === undefined) return "";
  return format(catalogs[i18n.lang][key] ?? key, params);
}

/** Whether the catalogs have a text for [key]. */
export function hasText(key: string): key is Key {
  return key in catalogs[i18n.lang];
}

/**
 * The text of [key], or [raw] itself when there is none: a phase, code or enum value from a newer frame shows as
 * its raw value (spec web-admin, "Unknown values from the frame").
 */
export function tOr(key: string, raw: string, params?: Record<string, string | number>): string {
  return hasText(key) ? t(key, params) : raw;
}

/** Singular for exactly one, otherwise plural with `{n}`. */
export function tn(one: Key, many: Key, n: number): string {
  return n === 1 ? t(one, { n }) : t(many, { n });
}
