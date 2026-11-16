import type { Crop, FillMode, Orientation, Presentation } from "./api/client";

/**
 * Client copy of `Presentation.of` in `:core` (openspec change photo-crop, design decisions 1 to 3), so the section
 * editor can follow every finger movement without asking the frame. Both run the cases of
 * `api/fixtures/presentation-cases.json`, so they cannot drift apart. The API's `presentation` stays the authority.
 */

/** `auto` crops at most this share of the photo in the direction in which it does not fit the screen. */
export const AUTO_MAX_CROP = 0.25;
export const MAX_ZOOM = 4;
const EPSILON = 1e-9;

export interface Size {
  width: number;
  height: number;
}

/** The screen in the configured orientation, from the device's natural resolution (`GET /info`). */
export function screenFor(natural: Size, orientation: Orientation): Size {
  const long = Math.max(natural.width, natural.height);
  const short = Math.min(natural.width, natural.height);
  return orientation === "landscape" ? { width: long, height: short } : { width: short, height: long };
}

function cover(photo: Size, screen: Size): number {
  return Math.max(screen.width / photo.width, screen.height / photo.height);
}

/** At most 1, and exactly 1 where only rounding keeps it below. */
function fraction(value: number): number {
  return value >= 1 - EPSILON ? 1 : value;
}

function clamp(value: number, min: number, max: number): number {
  return Math.min(Math.max(value, min), max);
}

/** From fully visible to 4, whatever the photo's resolution (openspec change zoom-beyond-resolution, decision 1). */
export function zoomRange(photo: Size, screen: Size): { min: number; max: number } {
  const c = cover(photo, screen);
  const fit = Math.min(screen.width / photo.width, screen.height / photo.height);
  return { min: fit / c, max: MAX_ZOOM };
}

/** The zoom at which one original pixel covers one screen pixel; above it the frame enlarges the part. */
export function sharpZoom(photo: Size, screen: Size): number {
  return 1 / cover(photo, screen);
}

/**
 * Screen pixels per original pixel of what the frame shows: above 1 the frame enlarges the part, and `1 / enlargement`
 * is the share of the screen's resolution it has (openspec change zoom-beyond-resolution, decision 2).
 */
export function enlargement(photo: Size, screen: Size, presentation: Presentation): number {
  return cover(photo, screen) * presentation.zoom;
}

/** What the frame shows of [photo] on [screen]: with [crop] its section, else the preset of [fillMode]. */
export function presentationOf(
  photo: Size,
  screen: Size,
  crop: Crop | null,
  fillMode: FillMode,
): Presentation {
  const range = zoomRange(photo, screen);
  const zoom = crop
    ? clamp(crop.zoom, range.min, range.max)
    : fillMode === "fill"
      ? 1
      : fillMode === "fit"
        ? range.min
        : Math.min(1, range.min / (1 - AUTO_MAX_CROP));
  const scale = cover(photo, screen) * zoom;
  const w = fraction(screen.width / (scale * photo.width));
  const h = fraction(screen.height / (scale * photo.height));
  const cx = clamp(crop?.x ?? 0.5, w / 2, 1 - w / 2);
  const cy = clamp(crop?.y ?? 0.5, h / 2, 1 - h / 2);
  return { x: cx - w / 2, y: cy - h / 2, width: w, height: h, zoom };
}

/**
 * Where the shown part of the photo stands on the screen, as fractions of the screen: the rest is black borders.
 * Used to place the display image in a box of the screen's aspect ratio.
 */
export function shownArea(photo: Size, screen: Size, presentation: Presentation): Size {
  const scale = cover(photo, screen) * presentation.zoom;
  return {
    width: Math.min(1, (presentation.width * photo.width * scale) / screen.width),
    height: Math.min(1, (presentation.height * photo.height * scale) / screen.height),
  };
}

/**
 * The share of the screen's resolution, in whole percent, of a part the frame enlarges by [enlargementFactor], or `null`
 * when it is sharp. The tolerance keeps the warning off at the sharp zoom itself, whatever the rounding of a stored
 * section (openspec change zoom-beyond-resolution, decision 2).
 */
export function enlargedPercent(enlargementFactor: number): number | null {
  return enlargementFactor > 1.005 ? Math.round(100 / enlargementFactor) : null;
}

/**
 * The zoom after zooming [current] by [factor] within [range]. With [stopAtSharp] (the editor's buttons) a step that
 * would cross [sharp] stops on it, if it lies inside the range (openspec change zoom-beyond-resolution, decision 4).
 */
export function zoomStep(
  current: number,
  factor: number,
  range: { min: number; max: number },
  sharp: number,
  stopAtSharp: boolean,
): number {
  const next = clamp(current * factor, range.min, range.max);
  if (!stopAtSharp || sharp <= range.min || sharp >= range.max) return next;
  const crossesUp = current < sharp - 1e-6 && next > sharp;
  const crossesDown = current > sharp + 1e-6 && next < sharp;
  return crossesUp || crossesDown ? sharp : next;
}
