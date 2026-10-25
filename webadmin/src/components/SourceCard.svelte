<script lang="ts">
  import { untrack } from "svelte";
  import { ApiError, type Source, type SourceKind, type SourceSettings } from "../lib/api/client";
  import { errorText } from "../lib/i18n/errors";
  import { i18n, t } from "../lib/i18n/i18n.svelte";
  import { settingsPatch, sourceRanges, statusLine } from "../lib/sources";
  import { api } from "../lib/session.svelte";
  import ConfirmDialog from "./ConfirmDialog.svelte";

  interface Props {
    source: Source;
    /** The source changed or was removed: the list loads again. */
    onchanged: () => void;
  }

  let { source, onchanged }: Props = $props();

  const kindKeys = {
    "nextcloud-account": "sources.kind.nextcloud-account",
    "nextcloud-share": "sources.kind.nextcloud-share",
  } as const satisfies Record<SourceKind, string>;

  // The form and the settings it started from; a change that is not saved yet is not overwritten by the next poll.
  let base = $state<SourceSettings>(current());
  let form = $state<SourceSettings>(current());
  let errors = $state<Record<string, string>>({});
  let error = $state<string | null>(null);
  let saving = $state(false);
  let saved = $state(false);
  let syncMessage = $state<string | null>(null);
  let confirmRemove = $state(false);

  const dirty = $derived(Object.keys(settingsPatch(base, form)).length > 0);
  const needsFolder = $derived(source.kind === "nextcloud-account" && source.settings.folder === "");
  // Open at first for an account that waits for its folder; after that the user decides.
  let settingsOpen = $state(untrack(() => needsFolder));
  const status = $derived(statusLine(source.status, i18n.lang));
  const prefix = $derived("src-" + source.id);

  /** The settings as the frame has them, as a plain object that the form can change. */
  function current(): SourceSettings {
    return $state.snapshot(source.settings);
  }

  $effect(() => {
    const next = current();
    untrack(() => {
      if (dirty) return;
      base = next;
      form = structuredClone(next);
    });
  });

  async function save(event: SubmitEvent) {
    event.preventDefault();
    saving = true;
    errors = {};
    error = null;
    try {
      const next = await api.patchSource(source.id, settingsPatch(base, form));
      base = next.settings;
      form = structuredClone(next.settings);
      saved = true;
      setTimeout(() => (saved = false), 3000);
      onchanged();
    } catch (e) {
      if (e instanceof ApiError && Object.keys(e.fields).length > 0) errors = e.fields;
      else error = errorText(e);
    } finally {
      saving = false;
    }
  }

  async function syncNow() {
    syncMessage = null;
    error = null;
    try {
      const res = await api.syncSource(source.id);
      if (!res.started) syncMessage = t("sources.alreadySyncing");
      onchanged();
    } catch (e) {
      error = errorText(e);
    }
  }

  async function remove() {
    confirmRemove = false;
    try {
      await api.removeSource(source.id);
      onchanged();
    } catch (e) {
      error = errorText(e);
    }
  }

  function fieldError(key: string): string | undefined {
    if (!errors[key]) return undefined;
    const range = sourceRanges[key];
    return range ? t("settings.range", { min: range[0], max: range[1] }) : t("settings.invalid");
  }
</script>

<section class="card source" aria-labelledby="{prefix}-name">
  <div class="head">
    <h2 id="{prefix}-name">{source.settings.name}</h2>
    <span class="muted">{t(kindKeys[source.kind])}</span>
  </div>
  <p class="muted">
    {source.user
      ? t("sources.accountOn", { user: source.user, server: source.serverUrl })
      : t("sources.shareOn", { server: source.serverUrl })}
  </p>

  {#if needsFolder}<p class="hint warn" role="status">{t("sources.noFolder")}</p>{/if}
  {#if status.failed}
    <p class="error" role="alert">{t(status.key, status.params)}</p>
  {:else}
    <p role="status" class:muted={source.status.ok === null && !source.status.syncing}>
      {t(status.key, status.params)}
    </p>
  {/if}
  {#if syncMessage}<p role="status">{syncMessage}</p>{/if}
  {#if error}<p class="error" role="alert">{error}</p>{/if}

  <div class="actions">
    <button
      type="button"
      onclick={syncNow}
      disabled={source.status.syncing || source.status.waiting || needsFolder}>{t("sources.sync")}</button
    >
    <button type="button" class="danger" onclick={() => (confirmRemove = true)}>{t("sources.remove")}</button>
  </div>

  <details bind:open={settingsOpen}>
    <summary>{t("sources.settings")}</summary>
    <form onsubmit={save} novalidate>
      <div class="field">
        <label for="{prefix}-label">{t("sources.name")}</label>
        <input id="{prefix}-label" type="text" bind:value={form.name} aria-invalid={!!errors["name"]} />
        {#if fieldError("name")}<span class="error" role="alert">{fieldError("name")}</span>{/if}
      </div>
      <label class="check">
        <input type="checkbox" bind:checked={form.enabled} />
        <span>{t("sources.enabled")}</span>
      </label>
      <div class="field">
        <label for="{prefix}-folder"
          >{source.kind === "nextcloud-share" ? t("sources.folderShare") : t("sources.folder")}</label
        >
        <input
          id="{prefix}-folder"
          type="text"
          placeholder={t("sources.folderPlaceholder")}
          bind:value={form.folder}
          aria-invalid={!!errors["folder"]}
        />
        <span class="hint">{t("sources.folderHint")}</span>
        {#if fieldError("folder")}<span class="error" role="alert">{fieldError("folder")}</span>{/if}
      </div>
      <label class="check">
        <input type="checkbox" bind:checked={form.includeSubfolders} />
        <span>{t("sources.includeSubfolders")}</span>
      </label>
      <label class="check">
        <input type="checkbox" bind:checked={form.removeVanished} />
        <span>{t("sources.removeVanished")}</span>
      </label>
      <div class="field">
        <label for="{prefix}-interval">{t("sources.interval")}</label>
        <input
          id="{prefix}-interval"
          type="number"
          inputmode="numeric"
          bind:value={form.intervalMinutes}
          aria-invalid={!!errors["intervalMinutes"]}
        />
        {#if fieldError("intervalMinutes")}<span class="error" role="alert"
            >{fieldError("intervalMinutes")}</span
          >{/if}
      </div>
      <div class="field">
        <label for="{prefix}-budget">{t("sources.budget")}</label>
        <input
          id="{prefix}-budget"
          type="number"
          inputmode="numeric"
          bind:value={form.originalsBudgetMb}
          aria-invalid={!!errors["originalsBudgetMb"]}
        />
        <span class="hint">{t("sources.budgetHint")}</span>
        {#if fieldError("originalsBudgetMb")}<span class="error" role="alert"
            >{fieldError("originalsBudgetMb")}</span
          >{/if}
      </div>
      <div class="actions">
        {#if saved}<span role="status" class="saved">{t("common.saved")}</span>{/if}
        <button type="submit" class="primary" disabled={!dirty || saving}>{t("sources.save")}</button>
      </div>
    </form>
  </details>
</section>

{#if confirmRemove}
  <ConfirmDialog
    message={t("sources.confirmRemove", { name: source.settings.name })}
    confirmLabel={t("sources.remove")}
    oncancel={() => (confirmRemove = false)}
    onconfirm={remove}
  />
{/if}

<style>
  .source {
    display: grid;
    gap: 10px;
  }
  .source p {
    margin: 0;
  }
  .head {
    display: flex;
    gap: 12px;
    align-items: baseline;
    flex-wrap: wrap;
  }
  h2 {
    margin: 0;
    font-size: 1.15rem;
    overflow-wrap: anywhere;
  }
  details summary {
    cursor: pointer;
    font-weight: 600;
    padding: 4px 0;
  }
  form {
    display: grid;
    gap: 14px;
    margin-top: 10px;
  }
  .check {
    display: flex;
    gap: 8px;
    align-items: center;
    font-weight: 600;
  }
  .check input {
    width: 20px;
    height: 20px;
    accent-color: var(--accent);
  }
  .actions {
    display: flex;
    gap: 12px;
    justify-content: flex-end;
    align-items: center;
    flex-wrap: wrap;
  }
  .warn {
    color: var(--warning, #a15c00);
  }
</style>
