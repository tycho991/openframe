<script lang="ts">
  import type { Photo } from "../lib/api/client";
  import { gridLayout } from "../lib/grid";
  import { t } from "../lib/i18n/i18n.svelte";
  import Icon from "./Icon.svelte";

  interface Props {
    items: Photo[];
    hasMore: boolean;
    selecting: boolean;
    selected: Set<string>;
    onloadmore: () => void;
    ontoggle: (photo: Photo) => void;
    onopen: (photo: Photo) => void;
    onimageerror: (photo: Photo) => void;
  }

  let { items, hasMore, selecting, selected, onloadmore, ontoggle, onopen, onimageerror }: Props = $props();

  let container: HTMLDivElement;
  let width = $state(0);
  let scrollY = $state(0);
  let viewport = $state(800);
  let top = $state(0);

  const layout = $derived(gridLayout({ width, count: items.length, scrollY: scrollY - top, viewport }));
  const visible = $derived(items.slice(layout.first, layout.last));

  $effect(() => {
    const update = () => {
      scrollY = window.scrollY;
      viewport = window.innerHeight;
      top = container.getBoundingClientRect().top + window.scrollY;
    };
    const observer = new ResizeObserver(() => {
      width = container.clientWidth;
      update();
    });
    observer.observe(container);
    window.addEventListener("scroll", update, { passive: true });
    window.addEventListener("resize", update);
    update();
    return () => {
      observer.disconnect();
      window.removeEventListener("scroll", update);
      window.removeEventListener("resize", update);
    };
  });

  $effect(() => {
    if (hasMore && layout.nearEnd) onloadmore();
  });
</script>

<div class="grid" bind:this={container} style:height="{layout.height}px">
  {#each visible as photo, i (photo.id)}
    {@const pos = layout.position(layout.first + i)}
    <button
      type="button"
      class="tile"
      class:dimmed={!photo.visible}
      class:selected={selected.has(photo.id)}
      style:transform="translate({pos.x}px, {pos.y}px)"
      style:width="{layout.tile}px"
      style:height="{layout.tile}px"
      aria-pressed={selecting ? selected.has(photo.id) : undefined}
      onclick={() => (selecting ? ontoggle(photo) : onopen(photo))}
    >
      <img
        src={photo.urls.thumb}
        alt={photo.path}
        loading="lazy"
        decoding="async"
        onerror={() => onimageerror(photo)}
      />
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
