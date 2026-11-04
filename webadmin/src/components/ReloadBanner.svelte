<script lang="ts">
  import { t } from "../lib/i18n/i18n.svelte";
  import { session } from "../lib/session.svelte";
</script>

<!-- The frame updated itself while input was unsaved (spec web-admin, "Reload after a frame update"). -->
{#if session.reloadVersion}
  <section class="card banner" role="status">
    <p>{t("reload.text", { version: session.reloadVersion })}</p>
    <button type="button" class="primary" onclick={() => location.reload()}>{t("reload.button")}</button>
  </section>
{/if}

<style>
  .banner {
    display: flex;
    gap: var(--space-3);
    align-items: center;
    justify-content: space-between;
    flex-wrap: wrap;
    margin-bottom: var(--space-4);
    max-width: 720px;
  }
  p {
    margin: 0;
    overflow-wrap: anywhere;
  }
</style>
