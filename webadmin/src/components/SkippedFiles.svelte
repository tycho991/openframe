<script lang="ts">
  import type { SkippedFile } from "../lib/api/client";
  import { t, tn } from "../lib/i18n/i18n.svelte";

  let { files }: { files: SkippedFile[] } = $props();
  let open = $state(false);
</script>

{#if files.length > 0}
  <section class="skipped" aria-live="polite">
    <p>
      <span>{tn("skipped.hintOne", "skipped.hint", files.length)}</span>
      <button type="button" onclick={() => (open = !open)} aria-expanded={open}>
        {open ? t("skipped.hide") : t("skipped.show")}
      </button>
    </p>
    {#if open}
      <ul>
        {#each files as file (file.path)}
          <li>
            <code>{file.path}</code> – {file.reason === "unsupported"
              ? t("skipped.unsupported")
              : t("skipped.corrupt")}
          </li>
        {/each}
      </ul>
    {/if}
  </section>
{/if}

<style>
  .skipped {
    border: 1px solid color-mix(in srgb, var(--danger) 40%, var(--divider));
    background: color-mix(in srgb, var(--danger) 8%, var(--surface));
    border-radius: var(--radius-s);
    padding: 8px 12px;
    margin-bottom: 12px;
  }
  p {
    margin: 0;
    display: flex;
    gap: 8px;
    align-items: center;
    justify-content: space-between;
    flex-wrap: wrap;
  }
  ul {
    margin: 8px 0 0;
    padding-left: 20px;
  }
  code {
    overflow-wrap: anywhere;
  }
</style>
