<script lang="ts">
  import { displayPath } from "../lib/albums";
  import type { Crop, Photo, Settings } from "../lib/api/client";
  import { i18n, t, type Key } from "../lib/i18n/i18n.svelte";
  import { screenFor, shownArea } from "../lib/presentation";
  import { session } from "../lib/session.svelte";
  import SectionEditor from "./SectionEditor.svelte";

  interface Props {
    photo: Photo;
    /** For the frame's orientation and fill mode; `null` until loaded. */
    settings: Settings | null;
    onclose: () => void;
    ontogglehidden: (photo: Photo) => void;
    ondelete: (photo: Photo) => void;
    onsavecrop: (photo: Photo, crop: Crop | null) => Promise<void>;
  }

  let { photo, settings, onclose, ontogglehidden, ondelete, onsavecrop }: Props = $props();
  let dialog: HTMLDialogElement;
  let editing = $state(false);

  $effect(() => {
    dialog.showModal();
  });

  const taken = $derived(
    new Date(photo.takenAt).toLocaleString(i18n.lang, { dateStyle: "long", timeStyle: "short" }),
  );
  const megabytes = $derived((photo.fileSize / 1024 / 1024).toFixed(1));
  /** The frame's screen in its orientation: the photo is shown as the frame shows it, borders included. */
  const screen = $derived(
    session.info && settings ? screenFor(session.info.screen, settings.orientation) : null,
  );
  const area = $derived(screen ? shownArea(photo, screen, photo.presentation) : null);
</script>

<dialog bind:this={dialog} oncancel={onclose} aria-label={photo.path}>
  <div class="layout">
    {#if screen && area}
      <div
        class="frame"
        style:aspect-ratio="{screen.width} / {screen.height}"
        style:max-width="calc(70dvh * {screen.width} / {screen.height})"
      >
        <img
          class="shown"
          src={photo.urls.display}
          alt={photo.path}
          style:left="{((1 - area.width) / 2) * 100}%"
          style:top="{((1 - area.height) / 2) * 100}%"
          style:width="{area.width * 100}%"
          style:height="{area.height * 100}%"
        />
      </div>
    {:else}
      <img class="plain" src={photo.urls.display} alt={photo.path} />
    {/if}
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
        <dt>{t("section.label")}</dt>
        <dd>
          {photo.crop
            ? t("section.own")
            : t("section.automaticFrom", {
                mode: t(`settings.fillMode.${settings?.fillMode ?? "auto"}` as Key),
              })}
        </dd>
      </dl>
      <div class="actions">
        <button type="button" disabled={!screen || !settings} onclick={() => (editing = true)}
          >{t("section.choose")}</button
        >
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

{#if editing && screen && settings}
  <SectionEditor
    {photo}
    {screen}
    fillMode={settings.fillMode}
    oncancel={() => (editing = false)}
    onsave={async (crop) => {
      await onsavecrop(photo, crop);
      editing = false;
    }}
  />
{/if}

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
  .frame {
    position: relative;
    width: 100%;
    margin: 0 auto;
    background: #000;
    overflow: hidden;
  }
  .shown {
    position: absolute;
    display: block;
  }
  .plain {
    width: 100%;
    max-height: 70dvh;
    object-fit: contain;
    background: #000;
    display: block;
  }
  .layout > :first-child {
    align-self: center;
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
    .plain {
      max-height: calc(100dvh - 32px);
    }
  }
</style>
