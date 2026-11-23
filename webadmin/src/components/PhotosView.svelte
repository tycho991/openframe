<script lang="ts">
  import { tick, untrack } from "svelte";
  import { SvelteMap } from "svelte/reactivity";
  import {
    albumHeadings,
    displayPath,
    gridHeadings,
    libraryCount,
    NO_ALBUM,
    type AlbumHeading,
    type Visibility,
  } from "../lib/albums";
  import type {
    Album,
    AlbumList,
    Crop,
    Photo,
    PhotoQuery,
    PhotoSort,
    Settings,
    SkippedFile,
    Client,
    TrashEntry,
  } from "../lib/api/client";
  import { t, tn } from "../lib/i18n/i18n.svelte";
  import { errorText } from "../lib/i18n/errors";
  import { OpenAlbums } from "../lib/openAlbums";
  import { PhotoPages, PhotoRequestQueue } from "../lib/photos";
  import { api, refreshState, session } from "../lib/session.svelte";
  import ConfirmDialog from "./ConfirmDialog.svelte";
  import Icon from "./Icon.svelte";
  import PhotoDetail from "./PhotoDetail.svelte";
  import SkippedFiles from "./SkippedFiles.svelte";
  import TrashPanel from "./TrashPanel.svelte";
  import { restoreInChunks, restoreMessage } from "../lib/trash";
  import UploadPanel from "./UploadPanel.svelte";
  import VirtualGrid from "./VirtualGrid.svelte";

  let visibility = $state<Visibility>("all");
  let sort = $state<PhotoSort>("date_desc");

  let albumList = $state.raw<AlbumList | null>(null);
  let skipped = $state<SkippedFile[]>([]);
  let trash = $state.raw<{ items: TrashEntry[]; totalBytes: number }>({ items: [], totalBytes: 0 });
  /** The paired devices, for the deleting device of a trash entry. */
  let clients = $state.raw<Client[]>([]);
  let error = $state<string | null>(null);
  /**
   * What the error came from: `null` for an action of the user, which only the next action clears; otherwise the
   * background work (a grid's pages, the library, the settings) whose next success clears it. Background work never
   * clears or replaces an error it did not cause.
   */
  let errorSource: object | null = null;
  const LIBRARY = {};
  const SETTINGS = {};

  function showError(text: string, source: object | null): void {
    if (source !== null && error !== null && errorSource === null) return;
    error = text;
    errorSource = source;
  }

  function clearError(): void {
    error = null;
    errorSource = null;
  }

  // Open headings, remembered per browser (design decision 7 of album-groups-in-photos).
  const openAlbums = new OpenAlbums();
  let openPaths = $state.raw<string[]>(openAlbums.paths);
  const openSet = $derived(new Set(openPaths));
  /** Headings switched while a filter is set: they stay with their ancestors until filter or sort order change. */
  let pinned = $state.raw<ReadonlySet<string>>(new Set());

  const headings = $derived(
    albumList
      ? albumHeadings({
          albums: albumList.albums,
          noAlbum: albumList.noAlbum,
          open: openSet,
          filter: visibility,
          pinned,
        })
      : [],
  );
  const slots = $derived(gridHeadings(headings));
  const total = $derived(albumList ? libraryCount(albumList.albums, albumList.noAlbum, visibility) : 0);
  const empty = $derived(
    albumList !== null && albumList.albums.length === 0 && albumList.noAlbum.count === 0,
  );

  // One paged grid per open album with photos of its own (decision 1); all share one request queue (decision 4).
  interface GridView {
    items: Photo[];
    hasMore: boolean;
    loaded: boolean;
    paused: boolean;
  }
  const queue = new PhotoRequestQueue();
  // Maps, since an album path can be any folder name, also one like `__proto__`.
  const pagesByPath = new SvelteMap<string, PhotoPages>();
  let views = $state.raw<ReadonlyMap<string, GridView>>(new Map());
  const grids: Record<string, VirtualGrid | undefined> = Object.create(null);

  function viewOf(pages: PhotoPages): GridView {
    return {
      items: pages.items,
      hasMore: pages.hasMore,
      loaded: pages.loaded,
      paused: pages.loading || pages.stalled,
    };
  }

  function queryFor(path: string): Omit<PhotoQuery, "cursor" | "limit"> {
    const visible = visibility === "all" ? undefined : visibility === "visible";
    return path === NO_ALBUM
      ? { noalbum: true, visible, sort }
      : { album: path, subalbums: false, visible, sort };
  }

  function openGrid(path: string): void {
    const pages = new PhotoPages(api, queryFor(path), {
      queue,
      onScreen: () => onScreen(path),
      position: () => boxes.get(path)?.offset ?? Number.MAX_SAFE_INTEGER,
    });
    pagesByPath.set(path, pages);
    pages.subscribe(() => {
      if (pagesByPath.get(path) === pages) views = new Map([...views, [path, viewOf(pages)]]);
    });
    views = new Map([...views, [path, viewOf(pages)]]);
  }

  function closeGrid(path: string): void {
    pagesByPath.get(path)?.close();
    pagesByPath.delete(path);
    views = new Map([...views].filter(([p]) => p !== path));
    delete grids[path];
  }

  // The grids follow the open headings and the query: a folded album drops its pages, a changed filter or sort
  // order starts every grid from no page.
  let seenQuery = "";
  $effect(() => {
    const wanted = new Set(slots.map((h) => h.path));
    const key = `${visibility} ${sort}`;
    untrack(() => {
      const changed = key !== seenQuery;
      seenQuery = key;
      for (const path of [...pagesByPath.keys()]) if (!wanted.has(path)) closeGrid(path);
      for (const path of wanted) {
        const pages = pagesByPath.get(path);
        if (!pages) openGrid(path);
        else if (changed) pages.setQuery(queryFor(path));
      }
    });
  });

  // Leaving the overview aborts its running requests too, so that coming back keeps to two at a time.
  $effect(() => () => {
    for (const pages of pagesByPath.values()) pages.close();
    queue.close();
  });

  // One measurement for the whole page (decision 2): the list's top in every scroll frame and on resize, the grids'
  // offsets relative to the list from one observer. A grid's top is the sum.
  let list = $state<HTMLElement>();
  let listTop = $state(0);
  let scrollY = $state(0);
  let viewport = $state(800);
  interface Box {
    offset: number;
    height: number;
    width: number;
  }
  let boxes = $state.raw<ReadonlyMap<string, Box>>(new Map());

  function measureList(): void {
    if (!list) return;
    scrollY = window.scrollY;
    viewport = window.innerHeight;
    listTop = list.getBoundingClientRect().top + window.scrollY;
  }

  function measureGrids(): void {
    if (!list) return;
    const base = list.getBoundingClientRect().top;
    scrollY = window.scrollY;
    viewport = window.innerHeight;
    listTop = base + window.scrollY;
    const next = new Map(
      [...list.querySelectorAll<HTMLElement>("[data-grid]")].map((el): [string, Box] => {
        const r = el.getBoundingClientRect();
        return [el.dataset.grid ?? "", { offset: r.top - base, height: r.height, width: el.clientWidth }];
      }),
    );
    const same =
      next.size === boxes.size &&
      [...next].every(([path, b]) => {
        const old = boxes.get(path);
        return (
          old !== undefined && old.offset === b.offset && old.height === b.height && old.width === b.width
        );
      });
    if (!same) boxes = next;
  }

  /** Whether the grid of [path] intersects the viewport now, for the order of the queue. */
  function onScreen(path: string): boolean {
    const box = boxes.get(path);
    if (!box) return false;
    const top = listTop + box.offset;
    return top < scrollY + viewport && top + box.height > scrollY;
  }

  const observer = new ResizeObserver(() => measureGrids());

  /** Attaches a grid's box to the observer: a fold, a grid that grows or a heading that wraps resizes a box. */
  function observed(el: HTMLElement) {
    observer.observe(el);
    return () => observer.unobserve(el);
  }

  $effect(() => {
    const el = list;
    if (!el) return;
    observer.observe(el);
    untrack(measureGrids);
    return () => observer.unobserve(el);
  });

  // Content outside the overview (banners, an error) moves the list without resizing it: its top is read once per
  // scroll frame.
  $effect(() => {
    let frame = 0;
    const onScroll = () => {
      if (frame === 0)
        frame = requestAnimationFrame(() => {
          frame = 0;
          measureList();
        });
    };
    const onResize = () => measureGrids();
    window.addEventListener("scroll", onScroll, { passive: true });
    window.addEventListener("resize", onResize);
    return () => {
      cancelAnimationFrame(frame);
      observer.disconnect();
      window.removeEventListener("scroll", onScroll);
      window.removeEventListener("resize", onResize);
    };
  });

  // The rendered rows changed: grids may have moved without being resized.
  $effect(() => {
    void headings;
    void tick().then(measureGrids);
  });

  // Selection is one map from photo to source for the whole page (decision 10): folding an album drops its pages,
  // not its selected photos, and photos of a remote source can be hidden, not deleted.
  let selecting = $state(false);
  const selected = new SvelteMap<string, string>();
  const canDelete = $derived([...selected.values()].every((source) => source === "local"));
  let detail = $state<Photo | null>(null);

  /** The path of the grid that holds photo [id]. */
  function gridOf(id: string): string | undefined {
    for (const [path, view] of views) if (view.items.some((p) => p.id === id)) return path;
    return undefined;
  }

  /** Brings the tile of photo [id] into view at its current place and focuses it. */
  async function focusPhoto(id: string): Promise<void> {
    measureGrids();
    const path = gridOf(id);
    if (path === undefined) return;
    await tick();
    const grid = grids[path];
    const box = boxes.get(path);
    const tile = grid?.tileBox(id);
    if (!grid || !box || !tile) return;
    const tileTop = listTop + box.offset + tile.y;
    const bars = list?.closest("main")?.getBoundingClientRect().top ?? 0;
    if (
      tileTop < window.scrollY + Math.max(0, bars) ||
      tileTop + tile.size > window.scrollY + window.innerHeight
    ) {
      window.scrollTo({
        top: Math.max(0, tileTop - (window.innerHeight - tile.size) / 2),
        behavior: "instant",
      });
      measureList();
    }
    await tick();
    grid.focusTile(id);
  }

  /** Back to the grid at the tile of the photo that was open. */
  function closeDetail() {
    const id = detail?.id;
    detail = null;
    if (id) void focusPhoto(id);
  }
  let confirm = $state<{ message: string; action: () => Promise<void> } | null>(null);
  let uploadFiles = $state<File[] | null>(null);
  let dragging = $state(false);

  /** An action of the user: clears the error, and shows its own if it fails. */
  async function guard(fn: () => Promise<unknown>) {
    try {
      clearError();
      await fn();
    } catch (e) {
      showError(errorText(e), null);
    }
  }

  /** Background work for [source]: shows its failure, and clears only the error it caused once it succeeds. */
  async function background(source: object, fn: () => Promise<unknown>) {
    try {
      await fn();
      if (error !== null && errorSource === source) clearError();
    } catch (e) {
      showError(errorText(e), source);
    }
  }

  /** The open photo as the reloaded pages have it. */
  function refreshDetail() {
    const open = detail;
    if (!open) return;
    const path = gridOf(open.id);
    detail = (path !== undefined ? views.get(path)?.items.find((p) => p.id === open.id) : undefined) ?? open;
  }

  /** Reloads the loaded pages of every open grid through the queue, the grids on screen first. */
  async function reloadGrids(): Promise<void> {
    await Promise.all([...pagesByPath.values()].map((pages) => background(pages, () => pages.reload())));
    refreshDetail();
  }

  async function loadTrash(): Promise<void> {
    trash = await api.trash();
    if (trash.items.length > 0) clients = await api.clients();
  }

  /**
   * Restores trash entries in chunks, stopping at a chunk that fails; the new library version reloads album list and
   * grids, the trash follows. One message names what stopped it, what failed and what was already gone.
   */
  async function restoreTrash(ids: string[]): Promise<void> {
    clearError();
    const outcome = await restoreInChunks(ids, api.restoreTrash);
    await guard(async () => {
      await loadTrash();
      await refreshState();
    });
    const message = restoreMessage(outcome);
    if (message) showError(message, null);
  }

  /** The album list, then the pages of every open album. */
  async function reloadLibrary(): Promise<void> {
    albumList = await api.albums();
    // The grids follow the new list before their pages are reloaded.
    await tick();
    await reloadGrids();
  }

  // Library changed elsewhere (USB, second browser, a switch): album list, skipped files, then the open grids.
  let seenVersion = -1;
  $effect(() => {
    const v = session.libraryVersion;
    const changed = seenVersion !== -1 && v !== seenVersion;
    seenVersion = v;
    void background(LIBRARY, async () => {
      if (changed) await reloadLibrary();
      else albumList = await api.albums();
      skipped = await api.skippedFiles();
      // With the library version, so that the signed thumbnail URLs stay fresh.
      await loadTrash();
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
    void background(SETTINGS, async () => {
      settings = await api.settings();
      if (changed) await reloadGrids();
    });
  });

  // Renew signed URLs before they expire, per grid through the queue.
  $effect(() => {
    const timer = setInterval(() => {
      for (const pages of pagesByPath.values()) void background(pages, () => pages.refreshStale());
    }, 60_000);
    return () => clearInterval(timer);
  });

  function toggleOpen(path: string) {
    openAlbums.toggle(path);
    openPaths = openAlbums.paths;
  }

  /** Filter or sort order changed: switched headings are no longer held in place. */
  function unpin() {
    pinned = new Set();
  }

  // One switch at a time: a switch changes the albums below it, so the next one must see the answer.
  let busy = $state(false);

  async function toggleAlbum(album: Album, input: HTMLInputElement) {
    busy = true;
    // Pinned before the answer, so that the heading does not leave the rows under the pointer (decision 9).
    if (visibility !== "all") pinned = new Set([...pinned, album.name]);
    try {
      // Switching resets the sub-albums, so the whole list comes back.
      albumList = await api.setAlbumHidden(album.name, !album.hidden);
      clearError();
      // The new library version reloads the open grids now instead of at the next regular check.
      void refreshState();
    } catch (e) {
      showError(errorText(e), null);
      // Nothing changed on the frame, so the switch must show the album's state again.
      input.checked = !album.hidden;
    } finally {
      busy = false;
    }
  }

  function toggle(photo: Photo) {
    if (selected.has(photo.id)) selected.delete(photo.id);
    else selected.set(photo.id, photo.source);
  }

  function endSelection() {
    selecting = false;
    selected.clear();
  }

  async function batch(action: "hide" | "show" | "delete") {
    const ids = [...selected.keys()];
    await guard(async () => {
      const result = await api.batch(action, ids);
      endSelection();
      await reloadLibrary();
      if (result.failed.length > 0) showError(t("photos.batchFailed", { count: result.failed.length }), null);
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
        await reloadLibrary();
        await loadTrash();
        if (result.failed.length > 0)
          showError(t("photos.batchFailed", { count: result.failed.length }), null);
      },
    };
  }

  /** A photo changed on the frame: every grid that holds it shows the new state. */
  function replace(photo: Photo) {
    for (const pages of pagesByPath.values()) pages.replace(photo);
    detail = photo;
  }

  async function toggleHidden(photo: Photo) {
    await guard(async () => replace(await api.setPhotoHidden(photo.id, !photo.hidden)));
  }

  /** Saves a section, or with `null` removes it. Errors are thrown to the editor, which stays open. */
  async function saveCrop(photo: Photo, crop: Crop | null) {
    replace(await api.patchPhoto(photo.id, { crop }));
  }

  /** Saves the comment, or with `null` removes it. Errors are thrown to the field, which keeps the text. */
  async function saveComment(photo: Photo, comment: string | null): Promise<Photo> {
    const updated = await api.patchPhoto(photo.id, { comment });
    replace(updated);
    return updated;
  }

  /** Tries a quarantined photo again: the frame renders it at the next request (spec "Quarantined photos"). */
  async function retry(photo: Photo) {
    await guard(async () => replace(await api.patchPhoto(photo.id, { quarantined: false })));
  }

  /** After an upload the headings the photos went to are open, with those above them (decision 8). */
  async function uploaded(albums: (string | null)[]) {
    for (const album of albums) openAlbums.openWithAncestors(album ?? NO_ALBUM);
    if (albums.length > 0) openPaths = openAlbums.paths;
    await guard(reloadLibrary);
    const first = albums[0];
    if (first === undefined) return;
    await tick();
    document.getElementById(headingId(first ?? NO_ALBUM))?.scrollIntoView({ block: "nearest" });
  }

  function onDrop(event: DragEvent) {
    event.preventDefault();
    dragging = false;
    const files = event.dataTransfer?.files;
    if (files && files.length > 0) uploadFiles = [...files];
  }

  /** Stable ids from the path: no album path is empty, and an encoded path holds no space. */
  function headingId(path: string): string {
    return path === NO_ALBUM ? "photos-no-album" : "album-" + encodeURIComponent(path);
  }
  function contentId(path: string): string {
    return headingId(path) + "-content";
  }

  function nameOf(heading: AlbumHeading): string {
    return heading.path === NO_ALBUM ? t("photos.noAlbumHeading") : displayPath(heading.path);
  }
</script>

{#snippet section(heading: AlbumHeading)}
  {@const name = nameOf(heading)}
  {@const view = views.get(heading.path)}
  {@const box = boxes.get(heading.path)}
  <div class="album" style:--depth={Math.min(heading.depth, 4)}>
    <div class="heading-row" id={headingId(heading.path)}>
      <div class="heading" role="heading" aria-level={heading.depth + 2}>
        <button
          type="button"
          class="fold"
          aria-expanded={heading.open}
          aria-controls={contentId(heading.path)}
          aria-label={heading.count === 1
            ? t("photos.foldLabelOne", { path: name })
            : t("photos.foldLabel", { path: name, n: heading.count })}
          onclick={() => toggleOpen(heading.path)}
        >
          <Icon name={heading.open ? "expandMore" : "chevronRight"} />
          <span class="name">{heading.path === NO_ALBUM ? name : heading.title}</span>
          <span class="album-count">{tn("photos.countOne", "photos.count", heading.count)}</span>
        </button>
      </div>
      {#if heading.album}
        {@const album = heading.album}
        {#if album.overridesParent}
          <span class="badge" title={t("albums.exceptionHint")}>{t("albums.exception")}</span>
        {/if}
        <label class="switch">
          <!-- Not disabled while busy: a disabled control loses the focus, which the switch keeps (decision 9). -->
          <input
            type="checkbox"
            role="switch"
            checked={!album.hidden}
            aria-disabled={busy}
            aria-label={t("photos.albumSwitchLabel", { path: name })}
            onclick={(e) => {
              if (busy) e.preventDefault();
            }}
            onkeydown={(e) => {
              // A checkbox toggles with Space only; the switch works with Enter too (decision 12).
              if (e.key === "Enter") {
                e.preventDefault();
                e.currentTarget.click();
              }
            }}
            onchange={(e) => toggleAlbum(album, e.currentTarget)}
          />
          <span>{t("albums.visibleLabel")}</span>
        </label>
      {/if}
    </div>
    <div class="content" id={contentId(heading.path)}>
      {#if heading.grid && view}
        <div class="grid-box" data-grid={heading.path} {@attach observed}>
          <VirtualGrid
            bind:this={grids[heading.path]}
            items={view.items}
            hasMore={view.hasMore}
            loaded={view.loaded}
            reserve={heading.ownCount}
            paused={view.paused}
            top={listTop + (box?.offset ?? 0)}
            measured={box?.height ?? -1}
            width={box?.width ?? 0}
            {scrollY}
            {viewport}
            {selecting}
            {selected}
            onloadmore={() => {
              const pages = pagesByPath.get(heading.path);
              if (pages) void background(pages, () => pages.loadMore());
            }}
            ontoggle={toggle}
            onopen={(p) => (detail = p)}
            onimageerror={(p) => {
              const pages = pagesByPath.get(heading.path);
              if (pages) void background(pages, () => pages.onImageError(p.id));
            }}
          />
        </div>
      {/if}
      {#each heading.children as child (child.path)}{@render section(child)}{/each}
    </div>
  </div>
{/snippet}

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
      <span class="sr-only">{t("photos.filterVisibility")}</span>
      <select bind:value={visibility} onchange={unpin} aria-label={t("photos.filterVisibility")}>
        <option value="all">{t("photos.all")}</option>
        <option value="visible">{t("photos.visible")}</option>
        <option value="hidden">{t("photos.hidden")}</option>
      </select>
    </label>
    <label>
      <span class="sr-only">{t("photos.sort")}</span>
      <select bind:value={sort} onchange={unpin} aria-label={t("photos.sort")}>
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
  <TrashPanel items={trash.items} totalBytes={trash.totalBytes} {clients} onrestore={restoreTrash} />

  {#if error}<p class="error" role="alert">{error}</p>{/if}

  {#if empty}
    <p class="empty">{t("photos.empty")}</p>
  {:else if albumList}
    <p class="muted count">{tn("photos.countOne", "photos.count", total)}</p>
    <div class="albums" bind:this={list}>
      {#each headings as heading (heading.path)}{@render section(heading)}{/each}
    </div>
  {/if}

  {#if selecting && selected.size > 0}
    <div class="selection-bar" role="toolbar" aria-label={t("photos.selected", { n: selected.size })}>
      <span>{t("photos.selected", { n: selected.size })}</span>
      <button type="button" onclick={() => batch("hide")}>{t("photos.hide")}</button>
      <button type="button" onclick={() => batch("show")}>{t("photos.show")}</button>
      {#if canDelete}
        <button type="button" class="danger" onclick={() => askDelete([...selected.keys()], false)}
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
    onclose={closeDetail}
    ontogglehidden={toggleHidden}
    ondelete={(p) => askDelete([p.id], true)}
    onsavecrop={saveCrop}
    onsavecomment={saveComment}
    onretry={retry}
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
    albums={albumList?.albums ?? []}
    initialFiles={uploadFiles}
    onclose={() => (uploadFiles = null)}
    onuploaded={(albums) => void uploaded(albums)}
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
    border-radius: var(--radius-m);
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
  .spacer {
    flex: 1 1 0;
  }
  .count {
    margin: 0 0 8px;
    font-size: var(--text-label);
  }
  .empty {
    padding: 40px 0;
    text-align: center;
    color: var(--muted);
  }
  /* Indented by 12 px per level up to depth 4 (decision 12); deeper albums stay there. */
  .heading-row {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 0 12px;
    margin-left: calc(var(--depth) * 12px);
    border-bottom: 1px solid var(--divider);
    /* Below the top bar when an upload scrolls it into view. */
    scroll-margin-top: calc(var(--bar-height) + env(safe-area-inset-top) + var(--space-2));
  }
  .heading {
    flex: 1 1 12rem;
    min-width: 0;
    margin: 0;
  }
  .fold {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 0 8px;
    width: 100%;
    min-height: var(--target);
    padding: 0 8px 0 0;
    border: 0;
    border-radius: var(--radius-s);
    background: none;
    color: var(--text);
    font-size: var(--text-body);
    font-weight: 600;
    text-align: left;
  }
  .fold:hover {
    background: var(--surface-2);
  }
  .name {
    min-width: 0;
    overflow-wrap: anywhere;
  }
  .album-count {
    color: var(--muted);
    font-size: var(--text-label);
    font-weight: normal;
    white-space: nowrap;
  }
  .badge {
    font-size: var(--text-caption);
    padding: 1px 8px;
    border-radius: 999px;
    background: color-mix(in srgb, var(--accent) 15%, transparent);
    color: var(--text);
  }
  .switch {
    display: flex;
    gap: 8px;
    align-items: center;
    min-height: var(--target);
    cursor: pointer;
  }
  .switch input {
    width: 20px;
    height: 20px;
    margin: 0;
    accent-color: var(--accent);
  }
  /* No padding or border: the box's top is the grid's top for the measurement. */
  .grid-box {
    margin: 8px 0 8px calc(var(--depth) * 12px);
  }
  .selection-bar {
    position: fixed;
    left: calc(50% + var(--content-left, 0px) / 2);
    bottom: calc(var(--content-bottom, 0px) + var(--space-4));
    transform: translateX(-50%);
    display: flex;
    gap: 8px;
    align-items: center;
    flex-wrap: wrap;
    justify-content: center;
    padding: 10px 14px;
    width: max-content;
    max-width: calc(100vw - var(--content-left, 0px) - 2 * var(--space-4));
    background: var(--surface);
    border: 1px solid var(--divider);
    border-radius: var(--radius-full);
    box-shadow: var(--shadow-1);
    z-index: 20;
  }
</style>
