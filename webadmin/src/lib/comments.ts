import type { Location } from "./api/client";

/** The longest comment the frame stores, in characters (spec `photo-library`, "Comment too long"). */
export const MAX_COMMENT_LENGTH = 500;

/**
 * The length of [text] as the frame counts it: code points of the trimmed text, so an emoji is one character. The
 * frame also turns line breaks into spaces, which does not change the count; it decides, this is for the counter.
 */
export function commentLength(text: string): number {
  return [...text.trim()].length;
}

/** What is sent for the text of the comment field: `null` removes the comment. */
export function commentPatch(text: string): string | null {
  return text.trim() === "" ? null : text;
}

/** Latitude and longitude with four decimals in the interface's language, e.g. `43.3188` or `43,3188`. */
export function coordinates(location: Location, lang: string): { lat: string; lon: string } {
  const format = (n: number) =>
    n.toLocaleString(lang, { minimumFractionDigits: 4, maximumFractionDigits: 4 });
  return { lat: format(location.lat), lon: format(location.lon) };
}
