export interface GridInput {
  width: number;
  count: number;
  /** Scrollposition relativ zum Anfang des Rasters. */
  scrollY: number;
  viewport: number;
  minTile?: number;
  gap?: number;
  overscanRows?: number;
}

/**
 * Berechnet, welche Kacheln im sichtbaren Bereich liegen. Nur diese werden
 * gerendert, damit auch bei 10.000 Fotos nur die sichtbaren Vorschaubilder laden.
 */
export function gridLayout(input: GridInput) {
  const gap = input.gap ?? 6;
  const minTile = input.minTile ?? (input.width < 600 ? 110 : 150);
  const overscan = input.overscanRows ?? 2;
  const width = Math.max(0, input.width);
  const columns = Math.max(2, Math.floor((width + gap) / (minTile + gap)));
  const tile = width > 0 ? (width - gap * (columns - 1)) / columns : 0;
  const rowHeight = tile + gap;
  const rows = Math.ceil(input.count / columns);
  const firstRow = rowHeight > 0 ? Math.max(0, Math.floor(input.scrollY / rowHeight) - overscan) : 0;
  const lastRow =
    rowHeight > 0 ? Math.min(rows, Math.ceil((input.scrollY + input.viewport) / rowHeight) + overscan) : 0;
  return {
    columns,
    tile,
    height: Math.max(0, rows * rowHeight - gap),
    first: firstRow * columns,
    last: Math.min(input.count, lastRow * columns),
    /** Ob der sichtbare Bereich nahe am Ende der geladenen Fotos liegt. */
    nearEnd: lastRow >= rows - overscan - 1,
    position(index: number) {
      return { x: (index % columns) * (tile + gap), y: Math.floor(index / columns) * rowHeight };
    },
  };
}
