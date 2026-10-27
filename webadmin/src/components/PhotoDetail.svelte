<script lang="ts">
  import { untrack } from "svelte";
  import { displayPath } from "../lib/albums";
  import { ApiError, type Crop, type Photo, type Settings } from "../lib/api/client";
  import { commentLength, commentPatch, coordinates, MAX_COMMENT_LENGTH } from "../lib/comments";
  import { errorText } from "../lib/i18n/errors";
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
    /** Saves the comment, `null` removes it; an error is thrown back to the field. */
    onsavecomment: (photo: Photo, comment: string | null) => Promise<Photo>;
  }

  let { photo, settings, onclose, ontogglehidden, ondelete, onsavecrop, onsavecomment }: Props = $props();
  let dialog: HTMLDialogElement;
  let editing = $state(false);

  /** The text in the comment field; it starts from the photo's comment and is kept while the list reloads. */
  let comment = $state("");
  let commentError = $state<string | null>(null);
  let commentStatus = $state<"idle" | "saving" | "saved">("idle");
  // Another photo opened: its comment. The same photo refreshed by the list keeps what is being typed.
  $effect(() => {
    void photo.id;
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
    try {
      const updated = await onsavecomment(photo, commentPatch(comment));
      // The frame's form of it: trimmed, line breaks as spaces.
      comment = updated.comment ?? "";
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
  const position = $derived(photo.location ? coordinates(photo.location, i18n.lang) : null);
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
  .comment {
    display: grid;
    gap: 6px;
    margin: 0 0 16px;
  }
  .comment label {
    font-weight: 600;
  }
  .comment textarea {
    width: 100%;
    box-sizing: border-box;
    resize: vertical;
    font: inherit;
  }
  .comment-row {
    display: flex;
    gap: 8px;
    align-items: center;
    flex-wrap: wrap;
  }
  .comment-row button {
    margin-left: auto;
  }
  .hint {
    font-size: 0.85rem;
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
  @media (min-width: 900px) {
    .layout {
      grid-template-columns: 1fr 300px;
    }
    .plain {
      max-height: calc(100dvh - 32px);
    }
  }
</style>
