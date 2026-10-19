<script lang="ts">
  import { ApiError, type Album } from "../lib/api/client";
  import { t } from "../lib/i18n/i18n.svelte";
  import { api } from "../lib/session.svelte";

  interface Props {
    albums: Album[];
    initialFiles?: File[];
    onclose: () => void;
    onuploaded: () => void;
  }

  let { albums, initialFiles = [], onclose, onuploaded }: Props = $props();

  interface Item {
    file: File;
    progress: number;
    status: "waiting" | "uploading" | "done" | "error";
    error?: string;
  }

  let items = $state<Item[]>([]);
  let albumChoice = $state("");
  let newAlbum = $state("");
  let running = $state(false);
  let dialog: HTMLDialogElement;
  let input: HTMLInputElement;
  let dragging = $state(false);

  const NEW = "__new__";
  const album = $derived(albumChoice === NEW ? newAlbum.trim() || null : albumChoice || null);
  const finished = $derived(
    items.length > 0 && items.every((i) => i.status === "done" || i.status === "error"),
  );
  const okCount = $derived(items.filter((i) => i.status === "done").length);
  const failedCount = $derived(items.filter((i) => i.status === "error").length);

  $effect(() => {
    dialog.showModal();
  });

  $effect(() => {
    if (initialFiles.length > 0) add(initialFiles);
  });

  function add(files: Iterable<File>) {
    for (const file of files) items.push({ file, progress: 0, status: "waiting" });
  }

  function describe(e: unknown): string {
    if (e instanceof ApiError) {
      if (e.status === 415) return t("upload.unsupported");
      if (e.status === 507) return t("upload.storage");
      return t("upload.failed", { message: e.message });
    }
    return t("upload.failed", { message: String(e) });
  }

  async function start() {
    running = true;
    const queue = items.filter((i) => i.status === "waiting");
    // Two at a time: faster than one by one, without overloading the frame.
    const worker = async () => {
      for (let item = queue.shift(); item; item = queue.shift()) {
        item.status = "uploading";
        try {
          await api.upload(item.file, album, (p) => (item.progress = p));
          item.status = "done";
        } catch (e) {
          item.status = "error";
          item.error = describe(e);
        }
      }
    };
    await Promise.all([worker(), worker()]);
    running = false;
    onuploaded();
  }

  function onDrop(event: DragEvent) {
    event.preventDefault();
    dragging = false;
    if (event.dataTransfer?.files) add(event.dataTransfer.files);
  }
</script>

<dialog
  bind:this={dialog}
  oncancel={(e) => (running ? e.preventDefault() : onclose())}
  aria-labelledby="upload-title"
>
  <h2 id="upload-title">{t("upload.title")}</h2>

  <div
    class="drop"
    class:dragging
    role="region"
    aria-label={t("upload.drop")}
    ondragover={(e) => {
      e.preventDefault();
      dragging = true;
    }}
    ondragleave={() => (dragging = false)}
    ondrop={onDrop}
  >
    <p>{t("upload.drop")}</p>
    <button type="button" onclick={() => input.click()} disabled={running}>{t("upload.choose")}</button>
    <input
      bind:this={input}
      type="file"
      multiple
      accept="image/jpeg,image/png,image/webp,image/gif,image/heic,image/heif,.heic,.heif"
      class="sr-only"
      data-testid="upload-input"
      onchange={() => {
        if (input.files) add(input.files);
        input.value = "";
      }}
    />
  </div>

  <div class="field">
    <label for="upload-album">{t("upload.album")}</label>
    <select id="upload-album" bind:value={albumChoice} disabled={running}>
      <option value="">{t("upload.noAlbum")}</option>
      {#each albums as a (a.name)}<option value={a.name}>{a.name}</option>{/each}
      <option value={NEW}>{t("upload.newAlbum")}</option>
    </select>
  </div>
  {#if albumChoice === NEW}
    <div class="field">
      <label for="upload-new-album">{t("upload.newAlbumName")}</label>
      <input id="upload-new-album" type="text" maxlength="64" bind:value={newAlbum} disabled={running} />
    </div>
  {/if}

  {#if items.length > 0}
    <ul class="files">
      {#each items as item, i (i)}
        <li class={item.status}>
          <span class="name">{item.file.name}</span>
          {#if item.status === "error"}
            <span class="error" role="alert">{item.error}</span>
          {:else}
            <progress max="1" value={item.progress} aria-label={item.file.name}></progress>
          {/if}
        </li>
      {/each}
    </ul>
  {/if}

  {#if finished}<p role="status">{t("upload.summary", { ok: okCount, failed: failedCount })}</p>{/if}

  <div class="actions">
    <button type="button" onclick={onclose} disabled={running}
      >{finished ? t("upload.done") : t("common.cancel")}</button
    >
    <button
      type="button"
      class="primary"
      onclick={start}
      disabled={running || !items.some((i) => i.status === "waiting")}>{t("upload.start")}</button
    >
  </div>
</dialog>

<style>
  dialog {
    border: 1px solid var(--border);
    border-radius: var(--radius);
    background: var(--surface);
    color: var(--text);
    width: min(560px, calc(100vw - 32px));
    padding: 20px;
  }
  dialog::backdrop {
    background: rgb(0 0 0 / 45%);
  }
  dialog > * + * {
    margin-top: 14px;
  }
  .drop {
    border: 2px dashed var(--border);
    border-radius: var(--radius);
    padding: 20px;
    text-align: center;
  }
  .drop.dragging {
    border-color: var(--accent);
    background: color-mix(in srgb, var(--accent) 8%, transparent);
  }
  .drop p {
    margin: 0 0 10px;
  }
  .files {
    list-style: none;
    padding: 0;
    margin: 0;
    max-height: 40dvh;
    overflow: auto;
    display: grid;
    gap: 6px;
  }
  .files li {
    display: grid;
    gap: 2px;
  }
  .name {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    font-size: 0.92rem;
  }
  progress {
    width: 100%;
  }
  .actions {
    display: flex;
    justify-content: flex-end;
    gap: 8px;
    flex-wrap: wrap;
  }
</style>
