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

  // Back to the control that opened the question once it is answered (spec web-admin, "Keyboard operation").
  $effect(() => {
    const opener = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    dialog.showModal();
    return () => {
      if (opener?.isConnected) opener.focus();
    };
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
    padding: var(--space-5);
  }
  dialog::backdrop {
    background: var(--scrim);
  }
  .actions {
    display: flex;
    justify-content: flex-end;
    gap: var(--space-2);
    flex-wrap: wrap;
  }
  .danger-fill {
    background: var(--danger);
    border-color: var(--danger);
    color: var(--on-danger);
  }
</style>
