<script lang="ts">
  import type { Client, TrashEntry } from "../lib/api/client";
  import { t } from "../lib/i18n/i18n.svelte";
  import { chunks, deletedAtText, deletedByText, entryName, keptUntilText, summaryText } from "../lib/trash";
  import QuarantinedPlaceholder from "./QuarantinedPlaceholder.svelte";

  /**
   * The trash in the photo overview (spec web-admin, "Trash in the interface"): shown while it holds photos, folded by
   * default with count and size. A quarantined or damaged entry, or one without a thumbnail, shows a placeholder and
   * requests no image.
   */
  let {
    items,
    totalBytes,
    clients,
    onrestore,
  }: {
    items: TrashEntry[];
    totalBytes: number;
    clients: Client[];
    /** Restores the entries [ids], in requests of at most 500; throws to show the error. */
    onrestore: (ids: string[]) => Promise<void>;
  } = $props();

  let open = $state(false);
  let busy = $state(false);

  const restorable = $derived(items.filter((e) => !e.damaged).map((e) => e.id));

  async function restore(ids: string[]) {
    busy = true;
    try {
      for (const chunk of chunks(ids)) await onrestore(chunk);
    } finally {
      busy = false;
    }
  }
</script>

{#if items.length > 0}
  <section class="trash" aria-label={t("trash.title")}>
    <p class="head">
      <span>{summaryText(items.length, totalBytes)}</span>
      <button type="button" onclick={() => (open = !open)} aria-expanded={open}>
        {open ? t("trash.hide") : t("trash.show")}
      </button>
    </p>
    {#if open}
      <p class="muted hint">{t("trash.hint")}</p>
      <ul>
        {#each items as entry (entry.id)}
          <li>
            <span class="thumb">
              {#if entry.quarantined}
                <QuarantinedPlaceholder name={entryName(entry)} />
              {:else if entry.thumb && !entry.damaged}
                <img src={entry.thumb} alt={entryName(entry)} loading="lazy" decoding="async" />
              {:else}
                <span class="none" role="img" aria-label={t("trash.noPreview")}>{t("trash.noPreview")}</span>
              {/if}
            </span>
            <span class="info">
              <strong>{entryName(entry)}</strong>
              {#if entry.album}<span class="muted">{t("trash.album", { album: entry.album })}</span>{/if}
              <span class="muted">{deletedAtText(entry)} {deletedByText(entry, clients)}</span>
              {#if entry.damaged}
                <span class="muted">{t("trash.damagedNote")}</span>
              {:else}
                <span class="muted">{keptUntilText(entry)}</span>
              {/if}
            </span>
            {#if !entry.damaged}
              <button type="button" disabled={busy} onclick={() => restore([entry.id])}
                >{t("trash.restore")}</button
              >
            {/if}
          </li>
        {/each}
      </ul>
      {#if restorable.length > 1}
        <p class="all">
          <button type="button" class="primary" disabled={busy} onclick={() => restore(restorable)}
            >{t("trash.restoreAll")}</button
          >
        </p>
      {/if}
    {/if}
  </section>
{/if}

<style>
  .trash {
    border: 1px solid var(--divider);
    background: var(--surface);
    border-radius: var(--radius-s);
    padding: 8px 12px;
    margin-bottom: 12px;
  }
  .head,
  .all {
    margin: 0;
    display: flex;
    gap: 8px;
    align-items: center;
    justify-content: space-between;
    flex-wrap: wrap;
  }
  .all {
    justify-content: flex-end;
    margin-top: 8px;
  }
  .hint {
    margin: 8px 0 0;
    font-size: var(--text-label);
  }
  ul {
    list-style: none;
    margin: 8px 0 0;
    padding: 0;
    display: grid;
    gap: 8px;
  }
  li {
    display: flex;
    gap: 12px;
    align-items: center;
    flex-wrap: wrap;
  }
  .thumb {
    width: 64px;
    height: 64px;
    flex: none;
    display: flex;
    overflow: hidden;
    border-radius: var(--radius-s);
    background: var(--surface-2);
  }
  .thumb img {
    width: 100%;
    height: 100%;
    object-fit: cover;
  }
  .none {
    margin: auto;
    font-size: var(--text-caption);
    color: var(--muted);
    text-align: center;
    padding: 4px;
  }
  .info {
    display: flex;
    flex-direction: column;
    min-width: 0;
    flex: 1;
    font-size: var(--text-label);
  }
  .info strong {
    overflow-wrap: anywhere;
  }
</style>
