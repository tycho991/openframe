<script lang="ts">
  import { untrack } from "svelte";
  import { displayPath } from "../lib/albums";
  import { ApiError, type Crop, type Photo, type Settings } from "../lib/api/client";
  import { commentLength, commentPatch, coordinates, MAX_COMMENT_LENGTH } from "../lib/comments";
  import { errorText } from "../lib/i18n/errors";
  import { i18n, t, type Key } from "../lib/i18n/i18n.svelte";
  import { screenFor, shownArea } from "../lib/presentation";
  import { session } from "../lib/session.svelte";
  import { viewport } from "../lib/viewport.svelte";
  import Icon from "./Icon.svelte";
  import SectionEditor from "./SectionEditor.svelte";

  interface Props {
    photo: Photo;
    /** For the frame's orientation and fill mode; `null` until loaded. */
    settings: Settings | null;
    onclose: () => void;
    ontogglehidden: (photo: Photo) => void;
    ondelete: (photo: Photo) => void;
    onsavecrop: (photo: Photo, crop: Crop | null) => Promise<void>;
    /** Saves the comment, `null` removes it; an error is thrown back to the field. */
    onsavecomment: (photo: Photo, comment: string | null) => Promise<Photo>;
  }

  let { photo, settings, onclose, ontogglehidden, ondelete, onsavecrop, onsavecomment }: Props = $props();
  let dialog = $state<HTMLDialogElement>();
  let panel = $state<HTMLElement>();
  let editing = $state(false);

  // Spec web-admin, "Detail views by width" (design-system, design.md, decision 8): a modal dialog below 1200 px, as
  // a full-screen layer or a sheet; from 1200 px a panel next to the grid, which stays usable. The photo is state of
  // PhotosView, so a change of width class only switches the form; what is typed here stays in this component.
  const asPanel = $derived(viewport.widthClass === "expanded");

  /** The text in the comment field; it starts from the photo's comment and is kept while the list reloads. */
  let comment = $state("");
  let commentError = $state<string | null>(null);
  let commentStatus = $state<"idle" | "saving" | "saved">("idle");
  // Another photo opened: its comment. The same photo refreshed by the list or by a save keeps what is being typed,
  // so the effect depends on the ID alone, which a new object of the same photo does not change.
  const photoId = $derived(photo.id);
  $effect(() => {
    void photoId;
    untrack(() => {
      comment = photo.comment ?? "";
      commentError = null;
      commentStatus = "idle";
    });
  });
  const commentUsed = $derived(commentLength(comment));
  const commentChanged = $derived(comment !== (photo.comment ?? ""));

  async function saveComment(event: SubmitEvent) {
    event.preventDefault();
    commentStatus = "saving";
    commentError = null;
    const sent = comment;
    try {
      const updated = await onsavecomment(photo, commentPatch(sent));
      // The frame's form of it (trimmed, line breaks as spaces), unless the user went on typing meanwhile.
      if (comment === sent) comment = updated.comment ?? "";
      commentStatus = "saved";
    } catch (e) {
      commentStatus = "idle";
      commentError =
        e instanceof ApiError && e.fields["comment"]
          ? t("photos.commentTooLong", { max: MAX_COMMENT_LENGTH })
          : errorText(e);
    }
  }

  $effect(() => {
    if (dialog && !dialog.open) dialog.showModal();
  });

  // Into the panel when it appears, so that the keyboard follows; choosing another photo keeps the focus in the grid.
  $effect(() => {
    if (panel) untrack(() => panel?.querySelector<HTMLElement>(".close")?.focus());
  });

  // Escape closes the panel like the dialog, from anywhere inside it.
  $effect(() => {
    if (!panel) return;
    const onKey = (event: KeyboardEvent) => {
      if (event.key !== "Escape" || editing) return;
      event.preventDefault();
      onclose();
    };
    panel.addEventListener("keydown", onKey);
    return () => panel?.removeEventListener("keydown", onKey);
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
  const position = $derived(photo.location ? coordinates(photo.location, i18n.lang) : null);
</script>

{#snippet content()}
  <header class="bar">
    <button type="button" class="close" aria-label={t("common.close")} onclick={onclose}>
      <Icon name={viewport.widthClass === "compact" ? "arrowBack" : "close"} />
    </button>
    <h2>{photo.path.split("/").at(-1)}</h2>
  </header>
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
      <dl>
        <dt>{t("photos.detailAlbum")}</dt>
        <dd>{photo.album ? displayPath(photo.album) : t("photos.detailNoAlbum")}</dd>
        <dt>{t("photos.detailTaken")}</dt>
        <dd>{taken}</dd>
        <dt>{t("photos.detailSize")}</dt>
        <dd>{photo.width} × {photo.height}</dd>
        <dt>{t("photos.detailFile")}</dt>
        <dd>{megabytes} MB</dd>
        {#if photo.location && position}
          <dt>{t("photos.detailPlace")}</dt>
          <dd>{photo.location.place ?? t("photos.noPlace")}</dd>
          <dt>{t("photos.detailCoordinates")}</dt>
          <dd>{t("photos.coordinates", position)}</dd>
        {/if}
        <dt>{t("section.label")}</dt>
        <dd>
          {photo.crop
            ? t("section.own")
            : t("section.automaticFrom", {
                mode: t(`settings.fillMode.${settings?.fillMode ?? "auto"}` as Key),
              })}
        </dd>
      </dl>
      <form class="comment" onsubmit={saveComment} novalidate>
        <label for="p-comment">{t("photos.comment")}</label>
        <textarea
          id="p-comment"
          rows="3"
          bind:value={comment}
          oninput={() => (commentStatus = "idle")}
          aria-invalid={!!commentError}
          aria-describedby="p-comment-count p-comment-hint"></textarea>
        <div class="comment-row">
          <span id="p-comment-count" class="hint" class:over={commentUsed > MAX_COMMENT_LENGTH}
            >{t("photos.commentCount", { count: commentUsed, max: MAX_COMMENT_LENGTH })}</span
          >
          {#if commentStatus === "saved"}<span role="status" class="saved">{t("common.saved")}</span>{/if}
          <button type="submit" disabled={!commentChanged || commentStatus === "saving"}
            >{t("photos.commentSave")}</button
          >
        </div>
        <span id="p-comment-hint" class="hint">{t("photos.commentHint")}</span>
        {#if commentError}<span class="error" role="alert">{commentError}</span>{/if}
      </form>
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
      </div>
    </div>
  </div>
{/snippet}

{#if asPanel}
  <aside class="panel" bind:this={panel} aria-label={photo.path}>
    {@render content()}
  </aside>
{:else}
  <dialog
    class={viewport.widthClass === "compact" ? "layer" : "sheet"}
    bind:this={dialog}
    oncancel={onclose}
    aria-label={photo.path}
  >
    {@render content()}
  </dialog>
{/if}

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
  dialog,
  .panel {
    border: 0;
    padding: 0;
    background: var(--surface);
    color: var(--text);
    overflow-y: auto;
    overscroll-behavior: contain;
    transition:
      transform var(--motion-medium) var(--ease),
      opacity var(--motion-medium) var(--ease);
  }
  dialog::backdrop {
    background: var(--scrim);
  }
  /* Below 600 px: a layer over the whole screen. */
  dialog.layer {
    width: 100vw;
    max-width: none;
    height: 100dvh;
    max-height: none;
    margin: 0;
    padding: env(safe-area-inset-top) env(safe-area-inset-right) env(safe-area-inset-bottom)
      env(safe-area-inset-left);
  }
  /* From 600 px: a sheet from the bottom over the list. */
  dialog.sheet {
    width: min(1100px, 100vw);
    max-width: none;
    max-height: 90dvh;
    margin: auto auto 0;
    padding-bottom: env(safe-area-inset-bottom);
    border-radius: var(--radius-l) var(--radius-l) 0 0;
  }
  /* From 1200 px: a panel next to the grid, which stays usable. */
  .panel {
    position: fixed;
    top: 0;
    right: 0;
    bottom: 0;
    z-index: 15;
    width: var(--detail-width);
    border-left: 1px solid var(--divider);
    box-shadow: var(--shadow-1);
  }
  @starting-style {
    dialog[open].layer {
      opacity: 0;
    }
    dialog[open].sheet {
      transform: translateY(32px);
      opacity: 0;
    }
    .panel {
      transform: translateX(32px);
      opacity: 0;
    }
  }
  .bar {
    position: sticky;
    top: 0;
    z-index: 1;
    display: flex;
    align-items: center;
    gap: var(--space-1);
    padding: var(--space-1) var(--space-4) var(--space-1) var(--space-1);
    background: var(--surface);
    border-bottom: 1px solid var(--divider);
  }
  .close {
    display: grid;
    place-items: center;
    flex: none;
    width: var(--target);
    height: var(--target);
    padding: 0;
    border: 0;
    border-radius: var(--radius-full);
    background: none;
  }
  .close:hover {
    background: var(--surface-2);
  }
  h2 {
    margin: 0;
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .layout {
    display: grid;
    grid-template-columns: 1fr;
  }
  .frame {
    position: relative;
    width: 100%;
    margin: 0 auto;
    background: var(--photo-backdrop);
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
    background: var(--photo-backdrop);
    display: block;
  }
  .layout > :first-child {
    align-self: center;
  }
  .meta {
    padding: var(--space-4);
    min-width: 0;
  }
  dl {
    display: grid;
    grid-template-columns: auto 1fr;
    gap: var(--space-1) var(--space-3);
    margin: 0 0 var(--space-4);
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
    gap: var(--space-2);
    flex-wrap: wrap;
  }
  .comment {
    display: grid;
    gap: 6px;
    margin: 0 0 var(--space-4);
  }
  .comment label {
    font-weight: 600;
  }
  .comment textarea {
    resize: vertical;
  }
  .comment-row {
    display: flex;
    gap: var(--space-2);
    align-items: center;
    flex-wrap: wrap;
  }
  .comment-row button {
    margin-left: auto;
  }
  .hint {
    font-size: var(--text-small);
    color: var(--muted);
  }
  .over {
    color: var(--danger);
    font-weight: 600;
  }
  .saved {
    color: var(--accent);
    font-weight: 600;
  }
  /* A wide sheet puts the photo and its details side by side. */
  @media (min-width: 900px) {
    .sheet .layout {
      grid-template-columns: 1fr 320px;
    }
    .sheet .plain {
      max-height: calc(90dvh - var(--bar-height));
    }
  }
</style>
