<script lang="ts">
  import { t } from "../lib/i18n/i18n.svelte";

  interface Props {
    message: string;
    confirmLabel: string;
    onconfirm: () => void;
    oncancel: () => void;
  }

  let { message, confirmLabel, onconfirm, oncancel }: Props = $props();
  let dialog: HTMLDialogElement;

  $effect(() => {
    dialog.showModal();
  });
</script>

<dialog bind:this={dialog} {oncancel} aria-labelledby="confirm-text">
  <p id="confirm-text">{message}</p>
  <div class="actions">
    <button type="button" onclick={oncancel}>{t("common.cancel")}</button>
    <button type="button" class="primary danger-fill" onclick={onconfirm}>{confirmLabel}</button>
  </div>
</dialog>

<style>
  dialog {
    border: 1px solid var(--divider);
    border-radius: var(--radius-m);
    background: var(--surface);
    color: var(--text);
    max-width: min(440px, calc(100vw - 32px));
    padding: 20px;
  }
  dialog::backdrop {
    background: rgb(0 0 0 / 45%);
  }
  .actions {
    display: flex;
    justify-content: flex-end;
    gap: 8px;
    flex-wrap: wrap;
  }
  .danger-fill {
    background: var(--danger);
    border-color: var(--danger);
    color: #fff;
  }
</style>
