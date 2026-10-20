<script lang="ts">
  import { t } from "../lib/i18n/i18n.svelte";

  interface Props {
    name: string;
    /** Saves the trimmed name; a rejected promise shows its message. */
    onsave: (name: string) => Promise<void>;
    oncancel: () => void;
  }

  let { name, onsave, oncancel }: Props = $props();
  // svelte-ignore state_referenced_locally
  let value = $state(name);
  let busy = $state(false);
  let error = $state<string | null>(null);
  const id = `rename-${Math.random().toString(36).slice(2)}`;
  const valid = $derived(value.trim().length >= 1 && value.trim().length <= 64);

  async function submit(event: SubmitEvent) {
    event.preventDefault();
    if (!valid || busy) return;
    busy = true;
    error = null;
    try {
      await onsave(value.trim());
    } catch (e) {
      error = t("common.error", { message: e instanceof Error ? e.message : String(e) });
    } finally {
      busy = false;
    }
  }
</script>

<form class="rename" onsubmit={submit}>
  <div class="field">
    <label for={id}>{t("devices.newName")}</label>
    <!-- svelte-ignore a11y_autofocus -->
    <input {id} type="text" maxlength="64" bind:value autofocus />
  </div>
  {#if error}<p class="error" role="alert">{error}</p>{/if}
  <div class="actions">
    <button type="button" onclick={oncancel}>{t("common.cancel")}</button>
    <button type="submit" class="primary" disabled={!valid || busy}>{t("common.save")}</button>
  </div>
</form>

<style>
  .rename {
    display: grid;
    gap: 8px;
    width: 100%;
  }
  .actions {
    display: flex;
    gap: 8px;
    justify-content: flex-end;
    flex-wrap: wrap;
  }
</style>
