<script lang="ts">
  import QuarantinedPlaceholder from "./QuarantinedPlaceholder.svelte";
  import { untrack } from "svelte";
  import type { Photo } from "../lib/api/client";
  import { gridLayout, reservedLayout } from "../lib/grid";
  import { t } from "../lib/i18n/i18n.svelte";
  import Icon from "./Icon.svelte";

  // Controlled by the photo overview, which owns the one measurement of the page (design decision 2 of
  // album-groups-in-photos): the grid has no observer and no window listener of its own.
  interface Props {
    items: Photo[];
    hasMore: boolean;
    /** Whether the first page has arrived; before it, the grid reserves the height of [reserve] tiles. */
    loaded: boolean;
    /** The photos the album list counts for the grid. */
    reserve: number;
    /** A page is on its way or the last one failed: the grid asks for no more. */
    paused: boolean;
    /** The grid's offset in the page. */
    top: number;
    /** The grid's height when [top] was measured; until it matches the rendered height, [top] may be stale. */
    measured: number;
    width: number;
    scrollY: number;
    viewport: number;
    selecting: boolean;
    selected: { has(id: string): boolean };
    onloadmore: () => void;
    ontoggle: (photo: Photo) => void;
    onopen: (photo: Photo) => void;
    onimageerror: (photo: Photo) => void;
  }

  let {
    items,
    hasMore,
    loaded,
    reserve,
    paused,
    top,
    measured,
    width,
    scrollY,
    viewport,
    selecting,
    selected,
    onloadmore,
    ontoggle,
    onopen,
    onimageerror,
  }: Props = $props();

  let container: HTMLDivElement;

  const layout = $derived(gridLayout({ width, count: items.length, scrollY: scrollY - top, viewport }));
  const reserved = $derived(reservedLayout({ width, count: reserve, scrollY: scrollY - top, viewport }));
  const visible = $derived(loaded ? items.slice(layout.first, layout.last) : []);
  const height = $derived(loaded ? layout.height : reserved.height);

  $effect(() => {
    // A grid whose height changed since the measurement has not been measured at its new place yet, nor have the
    // grids below it: they wait for the overview's next measurement, which the resize brings before the next paint.
    if (paused || width <= 0 || Math.abs(measured - height) > 1) return;
    // Untracked: what the callback reads (the overview's grids) is not a reason to ask again.
    if (loaded ? hasMore && layout.nearEnd : reserved.wanted) untrack(onloadmore);
  });

  // The photo whose tile has the focus. A tile that leaves the visible range is removed without a focus event, and
  // the range can jump for a moment: Firefox reports scrollY 0 once when a dialog's scroll lock ends. When the tile
  // is rendered again and nothing else took the focus, it gets it back.
  let focusedId: string | null = null;

  function onfocusin(event: FocusEvent) {
    focusedId = (event.target as HTMLElement).closest<HTMLElement>(".tile")?.dataset.photo ?? null;
  }

  function onfocusout(event: FocusEvent) {
    // A tile still on the page lost the focus to the user, not to the grid.
    if ((event.target as HTMLElement).isConnected) focusedId = null;
  }

  $effect(() => {
    void visible;
    const id = focusedId;
    if (id === null || (document.activeElement !== null && document.activeElement !== document.body)) return;
    container.querySelector<HTMLElement>(`[data-photo="${CSS.escape(id)}"]`)?.focus({ preventScroll: true });
  });

  /** Where the tile of photo [id] is, relative to the grid; `null` if the grid does not hold it. */
  export function tileBox(id: string): { y: number; size: number } | null {
    const index = items.findIndex((p) => p.id === id);
    if (index < 0) return null;
    return { y: layout.position(index).y, size: layout.tile };
  }

  /** Focuses the tile of photo [id], once the overview has scrolled it into the rendered range (spec web-admin,
   *  "Keyboard operation"): the element that opened a detail may have been recycled for another photo meanwhile. */
  export function focusTile(id: string): void {
    focusedId = id;
    container.querySelector<HTMLElement>(`[data-photo="${CSS.escape(id)}"]`)?.focus({ preventScroll: true });
  }
</script>

<div class="grid" bind:this={container} style:height="{height}px" {onfocusin} {onfocusout}>
  {#each visible as photo, i (photo.id)}
    {@const pos = layout.position(layout.first + i)}
    <button
      type="button"
      class="tile"
      data-photo={photo.id}
      class:dimmed={!photo.visible}
      class:selected={selected.has(photo.id)}
      style:transform="translate({pos.x}px, {pos.y}px)"
      style:width="{layout.tile}px"
      style:height="{layout.tile}px"
      aria-pressed={selecting ? selected.has(photo.id) : undefined}
      onclick={() => (selecting ? ontoggle(photo) : onopen(photo))}
    >
      {#if photo.quarantined}
        <QuarantinedPlaceholder name={photo.path} />
      {:else}
        <img
          src={photo.urls.thumb}
          alt={photo.path}
          loading="lazy"
          decoding="async"
          onerror={() => onimageerror(photo)}
        />
      {/if}
      {#if !photo.visible}<span class="badge">{t("photos.hiddenBadge")}</span>{/if}
      {#if selecting}<span class="check" aria-hidden="true"
          >{#if selected.has(photo.id)}<Icon name="check" size={18} />{/if}</span
        >{/if}
    </button>
  {/each}
</div>

<style>
  .grid {
    position: relative;
    width: 100%;
  }
  .tile {
    position: absolute;
    left: 0;
    top: 0;
    padding: 0;
    border: 0;
    border-radius: var(--radius-s);
    overflow: hidden;
    background: var(--surface-2);
    min-height: 0;
  }
  .tile img {
    width: 100%;
    height: 100%;
    object-fit: cover;
    display: block;
  }
  .tile.dimmed img {
    opacity: 0.4;
    filter: grayscale(0.8);
  }
  .tile.selected {
    outline: 3px solid var(--accent);
    outline-offset: -3px;
  }
  .badge {
    position: absolute;
    left: 6px;
    bottom: 6px;
    font-size: var(--text-caption);
    padding: 2px 6px;
    border-radius: 999px;
    background: var(--photo-badge);
    color: var(--on-photo);
  }
  .check {
    position: absolute;
    right: 6px;
    top: 6px;
    width: 26px;
    height: 26px;
    border-radius: 50%;
    border: 2px solid var(--on-photo);
    background: var(--photo-badge);
    color: var(--on-photo);
    display: grid;
    place-items: center;
    font-weight: 700;
  }
  .tile.selected .check {
    background: var(--accent);
    color: var(--on-accent);
  }
</style>
