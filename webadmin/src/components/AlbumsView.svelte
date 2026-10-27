<script lang="ts">
  import { SvelteSet } from "svelte/reactivity";
  import type { Album } from "../lib/api/client";
  import { albumTree, type AlbumNode } from "../lib/albums";
  import { t, tn } from "../lib/i18n/i18n.svelte";
  import { errorText } from "../lib/i18n/errors";
  import { api, session } from "../lib/session.svelte";
  import Icon from "./Icon.svelte";

  let albums = $state<Album[] | null>(null);
  let error = $state<string | null>(null);
  // Top-level albums are open, deeper ones folded until opened.
  const folded = new SvelteSet<string>();
  const opened = new SvelteSet<string>();

  const tree = $derived(albums ? albumTree(albums) : []);

  function isOpen(node: AlbumNode): boolean {
    return node.depth === 0 ? !folded.has(node.path) : opened.has(node.path);
  }

  function toggleOpen(node: AlbumNode) {
    const set = node.depth === 0 ? folded : opened;
    if (set.has(node.path)) set.delete(node.path);
    else set.add(node.path);
  }

  /** The rows on screen: every node whose parents are all open. */
  function rows(nodes: AlbumNode[]): AlbumNode[] {
    return nodes.flatMap((n) => [n, ...(isOpen(n) ? rows(n.children) : [])]);
  }

  async function load() {
    try {
      albums = await api.albums();
    } catch (e) {
      error = errorText(e);
    }
  }

  $effect(() => {
    void session.libraryVersion;
    void load();
  });

  // One switch at a time: a switch changes the albums below it, so the next one must see the answer.
  let busy = $state(false);

  async function toggle(album: Album, input: HTMLInputElement) {
    busy = true;
    try {
      // Switching resets the sub-albums, so the whole list comes back.
      albums = await api.setAlbumHidden(album.name, !album.hidden);
      error = null;
    } catch (e) {
      error = errorText(e);
      // Nothing changed on the frame, so the switch must show the album's state again.
      input.checked = !album.hidden;
    } finally {
      busy = false;
    }
  }
</script>

<h1>{t("nav.albums")}</h1>
{#if error}<p class="error" role="alert">{error}</p>{/if}
{#if albums === null}
  <p class="muted">{t("common.loading")}</p>
{:else if albums.length === 0}
  <p class="muted">{t("albums.empty")}</p>
{:else}
  <ul class="albums">
    {#each rows(tree) as node (node.path)}
      {@const album = node.album}
      <li class="card" class:hidden={album?.hidden} style:--depth={node.depth}>
        <div class="fold">
          {#if node.children.length > 0}
            <button
              type="button"
              class="icon"
              aria-expanded={isOpen(node)}
              aria-label={t(isOpen(node) ? "albums.collapse" : "albums.expand", { name: node.path })}
              onclick={() => toggleOpen(node)}
              ><Icon name={isOpen(node) ? "expandMore" : "chevronRight"} /></button
            >
          {/if}
        </div>
        <div class="cover">
          {#if album?.cover}<img src={album.cover.thumb} alt="" loading="lazy" />{/if}
        </div>
        <div class="info">
          <h2 title={node.path}>{node.title}</h2>
          {#if album}
            <p class="muted">
              {tn("albums.countOne", "albums.count", album.count)}
              {#if album.overridesParent}
                <span class="badge" title={t("albums.exceptionHint")}>{t("albums.exception")}</span>
              {/if}
            </p>
          {/if}
        </div>
        {#if album}
          <label class="switch">
            <input
              type="checkbox"
              role="switch"
              checked={!album.hidden}
              disabled={busy}
              onchange={(e) => toggle(album, e.currentTarget)}
            />
            <span>{t("albums.visibleLabel")}</span>
          </label>
        {/if}
      </li>
    {/each}
  </ul>
{/if}

<style>
  .albums {
    list-style: none;
    padding: 0;
    margin: 0;
    display: grid;
    gap: 8px;
  }
  li {
    display: grid;
    grid-template-columns: 44px 72px minmax(0, 1fr) auto;
    align-items: center;
    gap: 10px;
    padding: 8px 12px 8px 4px;
    margin-left: calc(min(var(--depth), 4) * 20px);
  }
  .fold button {
    display: grid;
    place-items: center;
    width: var(--target);
    height: var(--target);
    padding: 0;
    border: 0;
    border-radius: var(--radius-full);
    background: none;
    color: var(--text);
  }
  .cover {
    width: 72px;
    aspect-ratio: 3 / 2;
    background: var(--surface-2);
    border-radius: 4px;
    overflow: hidden;
  }
  .cover img {
    width: 100%;
    height: 100%;
    object-fit: cover;
    display: block;
  }
  li.hidden .cover img {
    opacity: 0.4;
    filter: grayscale(0.8);
  }
  h2 {
    font-size: var(--text-body);
    margin: 0;
    overflow-wrap: anywhere;
  }
  .info p {
    margin: 2px 0 0;
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
    align-items: center;
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
    cursor: pointer;
    min-height: var(--target);
  }
  .switch input {
    width: 20px;
    height: 20px;
    accent-color: var(--accent);
  }
  @media (max-width: 520px) {
    li {
      grid-template-columns: 44px 56px minmax(0, 1fr);
    }
    .cover {
      width: 56px;
    }
    .switch {
      grid-column: 3;
    }
  }
</style>
