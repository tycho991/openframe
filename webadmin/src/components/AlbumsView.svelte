<script lang="ts">
  import type { Album } from "../lib/api/client";
  import { t, tn } from "../lib/i18n/i18n.svelte";
  import { errorText } from "../lib/i18n/errors";
  import { api, session } from "../lib/session.svelte";

  let albums = $state<Album[] | null>(null);
  let error = $state<string | null>(null);

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

  async function toggle(album: Album) {
    try {
      const updated = await api.setAlbumHidden(album.name, !album.hidden);
      albums = albums!.map((a) => (a.name === updated.name ? updated : a));
    } catch (e) {
      error = errorText(e);
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
    {#each albums as album (album.name)}
      <li class="card" class:hidden={album.hidden}>
        <div class="cover">
          {#if album.cover}<img src={album.cover.thumb} alt="" loading="lazy" />{/if}
        </div>
        <div class="info">
          <h2>{album.name}</h2>
          <p class="muted">{tn("albums.countOne", "albums.count", album.count)}</p>
          <label class="switch">
            <input type="checkbox" role="switch" checked={!album.hidden} onchange={() => toggle(album)} />
            <span>{t("albums.visibleLabel")}</span>
          </label>
        </div>
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
    gap: 12px;
    grid-template-columns: repeat(auto-fill, minmax(min(260px, 100%), 1fr));
  }
  li {
    padding: 0;
    overflow: hidden;
  }
  .cover {
    aspect-ratio: 3 / 2;
    background: var(--surface-2);
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
  .info {
    padding: 12px 14px 14px;
  }
  h2 {
    font-size: 1.05rem;
    margin: 0;
    overflow-wrap: anywhere;
  }
  .info p {
    margin: 2px 0 10px;
  }
  .switch {
    display: flex;
    gap: 8px;
    align-items: center;
    cursor: pointer;
  }
  .switch input {
    width: 20px;
    height: 20px;
    accent-color: var(--accent);
  }
</style>
