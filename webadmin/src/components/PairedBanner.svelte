<script lang="ts">
  import type { Client } from "../lib/api/client";
  import { t } from "../lib/i18n/i18n.svelte";
  import { api, session } from "../lib/session.svelte";
  import RenameForm from "./RenameForm.svelte";

  /** Shown once after pairing through a link: under which name the device was paired. */
  let { client }: { client: Client } = $props();
  let renaming = $state(false);

  async function save(name: string) {
    session.pairedAs = await api.renameClient(client.id, name);
    renaming = false;
  }
</script>

<section class="card banner" role="status">
  {#if renaming}
    <RenameForm name={client.name} onsave={save} oncancel={() => (renaming = false)} />
  {:else}
    <p>{t("pair.pairedAs", { name: client.name })}</p>
    <div class="actions">
      <button type="button" onclick={() => (renaming = true)}>{t("devices.rename")}</button>
      <button type="button" class="primary" onclick={() => (session.pairedAs = null)}
        >{t("common.close")}</button
      >
    </div>
  {/if}
</section>

<style>
  .banner {
    display: flex;
    gap: 12px;
    align-items: center;
    justify-content: space-between;
    flex-wrap: wrap;
    margin-bottom: 16px;
    max-width: 720px;
  }
  p {
    margin: 0;
    overflow-wrap: anywhere;
  }
  .actions {
    display: flex;
    gap: 8px;
    flex-wrap: wrap;
  }
</style>
