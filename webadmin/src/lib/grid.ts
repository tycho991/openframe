import { FIRST_PAGE_LIMIT } from "./photos";

/**
 * Rows rendered above and below the visible area. The next page is asked for only when the visible area, extended
 * by these rows, overlaps the grid and reaches its last loaded rows.
 */
export const OVERSCAN_ROWS = 2;

/** A grid's first page is asked for when its reserved box comes within this many viewport heights of the screen. */
export const FIRST_PAGE_MARGIN_VIEWPORTS = 1;

export interface GridInput {
  width: number;
  count: number;
  /** Scroll position relative to the start of the grid. */
  scrollY: number;
  viewport: number;
  minTile?: number;
  gap?: number;
  overscanRows?: number;
}

function columnsAndTile(width: number, gap: number, minTile: number) {
  const w = Math.max(0, width);
  const columns = Math.max(2, Math.floor((w + gap) / (minTile + gap)));
  const tile = w > 0 ? (w - gap * (columns - 1)) / columns : 0;
  return { columns, tile };
}

/**
 * Computes which tiles are in the visible area. Only these are
 * rendered so that even with 10,000 photos only the visible thumbnails load.
 */
export function gridLayout(input: GridInput) {
  const gap = input.gap ?? 6;
  const minTile = input.minTile ?? (input.width < 600 ? 110 : 150);
  const overscan = input.overscanRows ?? OVERSCAN_ROWS;
  const { columns, tile } = columnsAndTile(input.width, gap, minTile);
  const rowHeight = tile + gap;
  const rows = Math.ceil(input.count / columns);
  const height = Math.max(0, rows * rowHeight - gap);
  const firstRow = rowHeight > 0 ? Math.max(0, Math.floor(input.scrollY / rowHeight) - overscan) : 0;
  const lastRow =
    rowHeight > 0 ? Math.min(rows, Math.ceil((input.scrollY + input.viewport) / rowHeight) + overscan) : 0;
  const last = Math.max(0, Math.min(input.count, lastRow * columns));
  // The visible area with the overscan rows, relative to the grid: a grid entirely above or below it never asks for
  // more (design decision 3 of album-groups-in-photos).
  const margin = overscan * rowHeight;
  const overlaps = input.scrollY - margin < height && input.scrollY + input.viewport + margin > 0;
  return {
    columns,
    tile,
    height,
    first: Math.min(firstRow * columns, last),
    last,
    /** Whether the visible area is near the end of the loaded photos. */
    nearEnd: overlaps && lastRow >= rows - overscan - 1,
    position(index: number) {
      return { x: (index % columns) * (tile + gap), y: Math.floor(index / columns) * rowHeight };
    },
  };
}

export interface ReserveInput {
  width: number;
  /** The photos the grid will show, from the album list. */
  count: number;
  /** Scroll position relative to the start of the grid. */
  scrollY: number;
  viewport: number;
  minTile?: number;
  gap?: number;
}

/**
 * The box of a grid before its first page: the height of the first page's tiles, at most [FIRST_PAGE_LIMIT], so
 * that unloaded grids do not stack at height 0. `wanted` tells whether the first page should be requested now: the
 * box is within [FIRST_PAGE_MARGIN_VIEWPORTS] viewport heights above or below the screen.
 */
export function reservedLayout(input: ReserveInput) {
  const { height } = gridLayout({
    ...input,
    count: Math.min(Math.max(0, input.count), FIRST_PAGE_LIMIT),
    overscanRows: 0,
  });
  const margin = FIRST_PAGE_MARGIN_VIEWPORTS * input.viewport;
  const wanted =
    input.width > 0 && input.scrollY - margin < height && input.scrollY + input.viewport + margin > 0;
  return { height, wanted };
}
