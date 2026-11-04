<script lang="ts">
  import { t } from "../lib/i18n/i18n.svelte";

  /** Instead of the image of a quarantined photo, which is never requested (spec web-admin, "Quarantined photos"). */
  let { name, detailed = false }: { name: string; detailed?: boolean } = $props();
</script>

<span class="quarantined" class:detailed role="img" aria-label={t("photos.quarantinedLabel", { name })}>
  <span class="title">{t("photos.quarantined")}</span>
  {#if detailed}<span class="text">{t("photos.quarantinedText")}</span>{/if}
</span>

<style>
  .quarantined {
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: var(--space-1);
    width: 100%;
    height: 100%;
    padding: var(--space-2);
    box-sizing: border-box;
    text-align: center;
    color: var(--muted);
    background: repeating-linear-gradient(
      135deg,
      var(--surface-2),
      var(--surface-2) 8px,
      color-mix(in srgb, var(--danger) 10%, var(--surface-2)) 8px,
      color-mix(in srgb, var(--danger) 10%, var(--surface-2)) 16px
    );
  }
  .detailed {
    min-height: 12rem;
    border-radius: var(--radius-s);
  }
  .title {
    font-weight: 600;
  }
  .text {
    max-width: 32rem;
  }
</style>
