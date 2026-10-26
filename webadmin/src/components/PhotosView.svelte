<script lang="ts">
  import { SvelteSet } from "svelte/reactivity";
  import { albumOptions } from "../lib/albums";
  import type { Album, Crop, Photo, PhotoSort, Settings, SkippedFile } from "../lib/api/client";
  import { t, tn } from "../lib/i18n/i18n.svelte";
  import { errorText } from "../lib/i18n/errors";
  import { PhotoPages } from "../lib/photos";
  import { api, session } from "../lib/session.svelte";
  import ConfirmDialog from "./ConfirmDialog.svelte";
  import PhotoDetail from "./PhotoDetail.svelte";
  import SkippedFiles from "./SkippedFiles.svelte";
  import UploadPanel from "./UploadPanel.svelte";
  import VirtualGrid from "./VirtualGrid.svelte";

  let album = $state("");
  let onlyThisAlbum = $state(false);
  let visibility = $state<"all" | "visible" | "hidden">("all");
  let sort = $state<PhotoSort>("date_desc");

  const pages = new PhotoPages(api, {});
  let items = $state<Photo[]>([]);
  let total = $state(0);
  let hasMore = $state(true);
  let albums = $state<Album[]>([]);
  const albumChoices = $derived(albumOptions(albums));
  let skipped = $state<SkippedFile[]>([]);
  let error = $state<string | null>(null);

  let selecting = $state(false);
  const selected = new SvelteSet<string>();
  // Photos of a remote source are only read by the frame: they can be hidden, not deleted.
  const canDelete = $derived(
    [...selected].every((id) => (items.find((p) => p.id === id)?.source ?? "local") === "local"),
  );
  let detail = $state<Photo | null>(null);
  let confirm = $state<{ message: string; action: () => Promise<void> } | null>(null);
  let uploadFiles = $state<File[] | null>(null);
  let dragging = $state(false);

  pages.subscribe(() => {
    items = pages.items;
    total = pages.total;
    hasMore = pages.hasMore;
  });

  function query() {
    return {
      album: album || undefined,
      subalbums: album && onlyThisAlbum ? false : undefined,
      visible: visibility === "all" ? undefined : visibility === "visible",
      sort,
    };
  }

  async function guard(fn: () => Promise<unknown>) {
    try {
      error = null;
      await fn();
    } catch (e) {
      error = errorText(e);
    }
  }

  // Filter or sort order changed: load from the start.
  $effect(() => {
    const q = query();
    void guard(() => pages.setQuery(q));
  });

  /** The open photo as the reloaded pages have it. */
  function refreshDetail() {
    const open = detail;
    if (open) detail = pages.items.find((p) => p.id === open.id) ?? open;
  }

  // Library changed elsewhere (USB, second browser): reload loaded pages, albums and skipped files.
  let seenVersion = -1;
  $effect(() => {
    const v = session.libraryVersion;
    if (seenVersion !== -1 && v !== seenVersion)
      void guard(async () => {
        await pages.reload();
        refreshDetail();
      });
    seenVersion = v;
    void guard(async () => {
      albums = await api.albums();
      skipped = await api.skippedFiles();
    });
  });

  // The part of a photo the frame shows depends on the settings too (fill mode, orientation).
  let settings = $state<Settings | null>(null);
  let seenSettings = -1;
  $effect(() => {
    const v = session.settingsVersion;
    // -1 until the first poll of the versions, which comes at once: loading then would be the same request twice.
    if (v === -1) return;
    const changed = seenSettings !== -1 && v !== seenSettings;
    seenSettings = v;
    void guard(async () => {
      settings = await api.settings();
      if (changed) {
        await pages.reload();
        refreshDetail();
      }
    });
  });

  // Renew signed URLs before they expire.
  $effect(() => {
    const timer = setInterval(() => void guard(() => pages.refreshStale()), 60_000);
    return () => clearInterval(timer);
  });

  function toggle(photo: Photo) {
    if (selected.has(photo.id)) selected.delete(photo.id);
    else selected.add(photo.id);
  }

  function endSelection() {
    selecting = false;
    selected.clear();
  }

  async function batch(action: "hide" | "show" | "delete") {
    const ids = [...selected];
    await guard(async () => {
      const result = await api.batch(action, ids);
      endSelection();
      await pages.reload();
      albums = await api.albums();
      if (result.failed.length > 0) error = t("photos.batchFailed", { count: result.failed.length });
    });
  }

  function askDelete(ids: string[], one: boolean) {
    confirm = {
      message:
        one || ids.length === 1
          ? t("photos.confirmDeleteOne")
          : tn("photos.confirmDeleteOne", "photos.confirmDeleteMany", ids.length),
      action: async () => {
        const result = await api.batch("delete", ids);
        detail = null;
        endSelection();
        await pages.reload();
        albums = await api.albums();
        if (result.failed.length > 0) error = t("photos.batchFailed", { count: result.failed.length });
      },
    };
  }

  async function toggleHidden(photo: Photo) {
    await guard(async () => {
      const updated = await api.setPhotoHidden(photo.id, !photo.hidden);
      pages.replace(updated);
      detail = updated;
    });
  }

  /** Saves a section, or with `null` removes it. Errors are thrown to the editor, which stays open. */
  async function saveCrop(photo: Photo, crop: Crop | null) {
    const updated = await api.patchPhoto(photo.id, { crop });
    pages.replace(updated);
    detail = updated;
  }

  function onDrop(event: DragEvent) {
    event.preventDefault();
    dragging = false;
    const files = event.dataTransfer?.files;
    if (files && files.length > 0) uploadFiles = [...files];
  }
</script>

<section
  class="photos"
  class:dragging
  aria-label={t("nav.photos")}
  ondragover={(e) => {
    if (e.dataTransfer?.types.includes("Files")) {
      e.preventDefault();
      dragging = true;
    }
  }}
  ondragleave={(e) => {
    if (e.currentTarget === e.target) dragging = false;
  }}
  ondrop={onDrop}
>
  <div class="toolbar">
    <label>
      <span class="sr-only">{t("photos.filterAlbum")}</span>
      <select bind:value={album} aria-label={t("photos.filterAlbum")}>
        <option value="">{t("photos.allAlbums")}</option>
        {#each albumChoices as a (a.value)}<option value={a.value}>{a.label}</option>{/each}
      </select>
    </label>
    {#if album}
      <label class="check">
        <input type="checkbox" bind:checked={onlyThisAlbum} />
        <span>{t("photos.onlyThisAlbum")}</span>
      </label>
    {/if}
    <label>
      <span class="sr-only">{t("photos.filterVisibility")}</span>
      <select bind:value={visibility} aria-label={t("photos.filterVisibility")}>
        <option value="all">{t("photos.all")}</option>
        <option value="visible">{t("photos.visible")}</option>
        <option value="hidden">{t("photos.hidden")}</option>
      </select>
    </label>
    <label>
      <span class="sr-only">{t("photos.sort")}</span>
      <select bind:value={sort} aria-label={t("photos.sort")}>
        <option value="date_desc">{t("photos.sortDateDesc")}</option>
        <option value="date_asc">{t("photos.sortDateAsc")}</option>
        <option value="name_asc">{t("photos.sortNameAsc")}</option>
        <option value="name_desc">{t("photos.sortNameDesc")}</option>
      </select>
    </label>
    <span class="spacer"></span>
    {#if selecting}
      <button type="button" onclick={endSelection}>{t("photos.endSelection")}</button>
    {:else}
      <button type="button" onclick={() => (selecting = true)}>{t("photos.select")}</button>
    {/if}
    <button type="button" class="primary" onclick={() => (uploadFiles = [])}>{t("upload.button")}</button>
  </div>

  <SkippedFiles files={skipped} />

  {#if error}<p class="error" role="alert">{error}</p>{/if}

  <p class="muted count">{tn("photos.countOne", "photos.count", total)}</p>

  {#if total === 0 && !hasMore}
    <p class="empty">{t("photos.empty")}</p>
  {:else}
    <VirtualGrid
      {items}
      {hasMore}
      {selecting}
      {selected}
      onloadmore={() => void guard(() => pages.loadMore())}
      ontoggle={toggle}
      onopen={(p) => (detail = p)}
      onimageerror={(p) => void pages.onImageError(p.id)}
    />
  {/if}

  {#if selecting && selected.size > 0}
    <div class="selection-bar" role="toolbar" aria-label={t("photos.selected", { n: selected.size })}>
      <span>{t("photos.selected", { n: selected.size })}</span>
      <button type="button" onclick={() => batch("hide")}>{t("photos.hide")}</button>
      <button type="button" onclick={() => batch("show")}>{t("photos.show")}</button>
      {#if canDelete}
        <button type="button" class="danger" onclick={() => askDelete([...selected], false)}
          >{t("common.delete")}</button
        >
      {:else}
        <span class="muted">{t("photos.noDeleteRemote")}</span>
      {/if}
    </div>
  {/if}
</section>

{#if detail}
  <PhotoDetail
    photo={detail}
    {settings}
    onclose={() => (detail = null)}
    ontogglehidden={toggleHidden}
    ondelete={(p) => askDelete([p.id], true)}
    onsavecrop={saveCrop}
  />
{/if}

{#if confirm}
  <ConfirmDialog
    message={confirm.message}
    confirmLabel={t("common.delete")}
    oncancel={() => (confirm = null)}
    onconfirm={() => {
      const action = confirm!.action;
      confirm = null;
      void guard(action);
    }}
  />
{/if}

{#if uploadFiles}
  <UploadPanel
    {albums}
    initialFiles={uploadFiles}
    onclose={() => (uploadFiles = null)}
    onuploaded={() =>
      void guard(async () => {
        await pages.reload();
        albums = await api.albums();
      })}
  />
{/if}

<style>
  .photos {
    min-height: 60dvh;
    padding-bottom: 80px;
  }
  .photos.dragging {
    outline: 3px dashed var(--accent);
    outline-offset: 4px;
    border-radius: var(--radius);
  }
  .toolbar {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
    align-items: center;
    margin-bottom: 12px;
  }
  .toolbar label {
    flex: 1 1 140px;
    max-width: 220px;
  }
  .toolbar label.check {
    flex: 0 0 auto;
    display: flex;
    gap: 6px;
    align-items: center;
    min-height: 44px;
    cursor: pointer;
  }
  .spacer {
    flex: 1 1 0;
  }
  .count {
    margin: 0 0 8px;
    font-size: 0.9rem;
  }
  .empty {
    padding: 40px 0;
    text-align: center;
    color: var(--muted);
  }
  .selection-bar {
    position: fixed;
    left: 50%;
    bottom: 16px;
    transform: translateX(-50%);
    display: flex;
    gap: 8px;
    align-items: center;
    flex-wrap: wrap;
    justify-content: center;
    padding: 10px 14px;
    width: max-content;
    max-width: calc(100vw - 32px);
    background: var(--surface);
    border: 1px solid var(--border);
    border-radius: 999px;
    box-shadow: var(--shadow);
    z-index: 20;
  }
</style>
