<script lang="ts">
  import { SvelteMap } from "svelte/reactivity";
  import type { Crop, FillMode, Photo } from "../lib/api/client";
  import { errorText } from "../lib/i18n/errors";
  import { t } from "../lib/i18n/i18n.svelte";
  import { presentationOf, shownArea, zoomRange, type Size } from "../lib/presentation";
  import Icon from "./Icon.svelte";

  interface Props {
    photo: Photo;
    /** The frame's screen in its orientation. */
    screen: Size;
    fillMode: FillMode;
    /** Stores the section (`null`: automatic). Throws to keep the editor open with the error. */
    onsave: (crop: Crop | null) => Promise<void>;
    oncancel: () => void;
  }

  let { photo, screen, fillMode, onsave, oncancel }: Props = $props();
  let dialog: HTMLDialogElement;
  let viewport: HTMLDivElement;
  let viewportWidth = $state(0);

  /** The section being edited, `null` for automatic. Nothing is stored before "Save". */
  // svelte-ignore state_referenced_locally
  let crop = $state<Crop | null>(photo.crop);
  let saving = $state(false);
  let error = $state<string | null>(null);

  const size = $derived({ width: photo.width, height: photo.height });
  const range = $derived(zoomRange(size, screen));
  const shown = $derived(presentationOf(size, screen, crop, fillMode));
  const area = $derived(shownArea(size, screen, shown));
  /** Pixels of the photo to pixels of the viewport. */
  const scale = $derived(
    (Math.max(screen.width / photo.width, screen.height / photo.height) * shown.zoom * viewportWidth) /
      screen.width,
  );
  const viewportHeight = $derived((viewportWidth * screen.height) / screen.width);
  const left = $derived(((1 - area.width) / 2) * viewportWidth - shown.x * photo.width * scale);
  const top = $derived(((1 - area.height) / 2) * viewportHeight - shown.y * photo.height * scale);
  const ZOOM_STEP = 1.25;
  const atMin = $derived(shown.zoom <= range.min + 1e-6);
  const atMax = $derived(shown.zoom >= range.max - 1e-6);

  $effect(() => {
    dialog.showModal();
  });

  $effect(() => {
    const observer = new ResizeObserver(() => (viewportWidth = viewport.clientWidth));
    observer.observe(viewport);
    viewportWidth = viewport.clientWidth;
    return () => observer.disconnect();
  });

  /**
   * The section after moving the shown part by [dx], [dy] viewport pixels and zooming by [factor]. It is stored as
   * what is shown, so a focus point beyond the edge does not pile up and the photo follows the finger back at once.
   */
  function change(dx: number, dy: number, factor: number) {
    const current = shown;
    const zoom = Math.min(Math.max(current.zoom * factor, range.min), range.max);
    const next = presentationOf(
      size,
      screen,
      {
        x: current.x + current.width / 2 - (scale > 0 ? dx / (photo.width * scale) : 0),
        y: current.y + current.height / 2 - (scale > 0 ? dy / (photo.height * scale) : 0),
        zoom,
      },
      fillMode,
    );
    // The zoom stays exact, so its limits (the whole photo) are reached exactly.
    crop = { x: round(next.x + next.width / 2), y: round(next.y + next.height / 2), zoom: next.zoom };
  }

  function round(v: number) {
    return Math.round(v * 10_000) / 10_000;
  }

  // Pointer Events: one pointer drags, two pinch. Positions per pointer since the last event.
  const pointers = new SvelteMap<number, { x: number; y: number }>();

  function onpointerdown(e: PointerEvent) {
    try {
      // Keeps the drag when the finger leaves the viewport.
      viewport.setPointerCapture(e.pointerId);
    } catch {
      // A pointer the browser no longer knows as active: the drag works within the viewport.
    }
    pointers.set(e.pointerId, { x: e.clientX, y: e.clientY });
  }

  function onpointermove(e: PointerEvent) {
    const last = pointers.get(e.pointerId);
    if (!last) return;
    const others = [...pointers.entries()].filter(([id]) => id !== e.pointerId).map(([, p]) => p);
    const now = { x: e.clientX, y: e.clientY };
    if (others.length === 0) {
      change(now.x - last.x, now.y - last.y, 1);
    } else {
      const other = others[0]!;
      const before = Math.hypot(last.x - other.x, last.y - other.y);
      const after = Math.hypot(now.x - other.x, now.y - other.y);
      // The midpoint of the two fingers moves by half of this finger's movement.
      change((now.x - last.x) / 2, (now.y - last.y) / 2, before > 0 ? after / before : 1);
    }
    pointers.set(e.pointerId, now);
  }

  function onpointerup(e: PointerEvent) {
    pointers.delete(e.pointerId);
  }

  // Not passive, so the page does not scroll while the wheel zooms.
  $effect(() => {
    const onwheel = (e: WheelEvent) => {
      e.preventDefault();
      change(0, 0, Math.exp(-e.deltaY * 0.002));
    };
    viewport.addEventListener("wheel", onwheel, { passive: false });
    return () => viewport.removeEventListener("wheel", onwheel);
  });

  async function save() {
    saving = true;
    error = null;
    try {
      await onsave(crop);
    } catch (e) {
      error = errorText(e);
    } finally {
      saving = false;
    }
  }
</script>

<dialog bind:this={dialog} {oncancel} aria-label={t("section.title")}>
  <h2>{t("section.title")}</h2>
  <p class="muted hint">{t("section.hint")}</p>
  <div
    class="viewport"
    bind:this={viewport}
    style:aspect-ratio="{screen.width} / {screen.height}"
    style:max-width="calc(60dvh * {screen.width} / {screen.height})"
    role="application"
    aria-label={t("section.viewport")}
    data-zoom={shown.zoom}
    data-x={shown.x}
    data-y={shown.y}
    data-width={shown.width}
    data-height={shown.height}
    {onpointerdown}
    {onpointermove}
    {onpointerup}
    onpointercancel={onpointerup}
  >
    <img
      src={photo.urls.full}
      alt={photo.path}
      draggable="false"
      style:width="{photo.width * scale}px"
      style:height="{photo.height * scale}px"
      style:transform="translate({left}px, {top}px)"
    />
  </div>
  <div class="zoom" role="group" aria-label={t("section.zoom")}>
    <button
      type="button"
      aria-label={t("section.zoomOut")}
      disabled={atMin}
      onclick={() => change(0, 0, 1 / ZOOM_STEP)}><Icon name="remove" /></button
    >
    <button
      type="button"
      aria-label={t("section.zoomIn")}
      disabled={atMax}
      onclick={() => change(0, 0, ZOOM_STEP)}><Icon name="add" /></button
    >
    <button type="button" aria-pressed={crop === null} onclick={() => (crop = null)}
      >{t("section.automatic")}</button
    >
  </div>
  {#if error}<p class="error" role="alert">{error}</p>{/if}
  <div class="actions">
    <button type="button" onclick={oncancel}>{t("common.cancel")}</button>
    <button type="button" class="primary" disabled={saving} onclick={save}>{t("common.save")}</button>
  </div>
</dialog>

<style>
  dialog {
    border: 0;
    border-radius: var(--radius-m);
    background: var(--surface);
    color: var(--text);
    padding: 16px;
    width: min(1100px, calc(100vw - 16px));
    max-width: calc(100vw - 16px);
    max-height: calc(100dvh - 16px);
    box-sizing: border-box;
  }
  /* On a phone the viewport takes nearly the whole width. */
  @media (max-width: 600px) {
    dialog {
      padding: 8px;
      width: calc(100vw - 8px);
      max-width: calc(100vw - 8px);
    }
  }
  dialog::backdrop {
    background: var(--scrim);
  }
  h2 {
    margin: 0 0 4px;
  }
  .hint {
    margin: 0 0 12px;
    font-size: var(--text-label);
  }
  .viewport {
    position: relative;
    width: 100%;
    margin: 0 auto;
    overflow: hidden;
    background: var(--photo-backdrop);
    touch-action: none;
    cursor: grab;
    user-select: none;
  }
  .viewport:active {
    cursor: grabbing;
  }
  img {
    position: absolute;
    left: 0;
    top: 0;
    max-width: none;
    transform-origin: 0 0;
    pointer-events: none;
  }
  .zoom,
  .actions {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
    margin-top: 12px;
  }
  .actions {
    justify-content: flex-end;
  }
  button {
    min-width: 44px;
    min-height: var(--target);
  }
  .zoom button:not(:last-child) {
    display: grid;
    place-items: center;
    width: var(--target);
    padding: 0;
  }
  button[aria-pressed="true"] {
    border-color: var(--accent);
    color: var(--accent);
  }
</style>
