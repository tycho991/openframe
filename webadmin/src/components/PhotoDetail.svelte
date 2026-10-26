<script lang="ts">
  import { displayPath } from "../lib/albums";
  import type { Photo } from "../lib/api/client";
  import { i18n, t } from "../lib/i18n/i18n.svelte";

  interface Props {
    photo: Photo;
    onclose: () => void;
    ontogglehidden: (photo: Photo) => void;
    ondelete: (photo: Photo) => void;
  }

  let { photo, onclose, ontogglehidden, ondelete }: Props = $props();
  let dialog: HTMLDialogElement;

  $effect(() => {
    dialog.showModal();
  });

  const taken = $derived(
    new Date(photo.takenAt).toLocaleString(i18n.lang, { dateStyle: "long", timeStyle: "short" }),
  );
  const megabytes = $derived((photo.fileSize / 1024 / 1024).toFixed(1));
</script>

<dialog bind:this={dialog} oncancel={onclose} aria-label={photo.path}>
  <div class="layout">
    <img src={photo.urls.display} alt={photo.path} />
    <div class="meta">
      <h2>{photo.path.split("/").at(-1)}</h2>
      <dl>
        <dt>{t("photos.detailAlbum")}</dt>
        <dd>{photo.album ? displayPath(photo.album) : t("photos.detailNoAlbum")}</dd>
        <dt>{t("photos.detailTaken")}</dt>
        <dd>{taken}</dd>
        <dt>{t("photos.detailSize")}</dt>
        <dd>{photo.width} × {photo.height}</dd>
        <dt>{t("photos.detailFile")}</dt>
        <dd>{megabytes} MB</dd>
      </dl>
      <div class="actions">
        <button type="button" onclick={() => ontogglehidden(photo)}
          >{photo.hidden ? t("photos.show") : t("photos.hide")}</button
        >
        {#if photo.source !== "local"}
          <span class="muted">{t("photos.noDeleteRemote")}</span>
        {:else}
          <button type="button" class="danger" onclick={() => ondelete(photo)}>{t("common.delete")}</button>
        {/if}
        <button type="button" class="primary" onclick={onclose}>{t("common.close")}</button>
      </div>
    </div>
  </div>
</dialog>

<style>
  dialog {
    border: 0;
    border-radius: var(--radius);
    background: var(--surface);
    color: var(--text);
    padding: 0;
    width: min(1100px, calc(100vw - 32px));
    max-height: calc(100dvh - 32px);
  }
  dialog::backdrop {
    background: rgb(0 0 0 / 70%);
  }
  .layout {
    display: grid;
    grid-template-columns: 1fr;
  }
  img {
    width: 100%;
    max-height: 70dvh;
    object-fit: contain;
    background: #000;
    display: block;
  }
  .meta {
    padding: 16px;
    min-width: 0;
  }
  h2 {
    overflow-wrap: anywhere;
    font-size: 1.1rem;
  }
  dl {
    display: grid;
    grid-template-columns: auto 1fr;
    gap: 4px 12px;
    margin: 0 0 16px;
  }
  dt {
    color: var(--muted);
  }
  dd {
    margin: 0;
    overflow-wrap: anywhere;
  }
  .actions {
    display: flex;
    gap: 8px;
    flex-wrap: wrap;
  }
  @media (min-width: 900px) {
    .layout {
      grid-template-columns: 1fr 300px;
    }
    img {
      max-height: calc(100dvh - 32px);
    }
  }
</style>
