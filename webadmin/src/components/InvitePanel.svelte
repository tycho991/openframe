<script lang="ts">
  import type { Client, Invite } from "../lib/api/client";
  import { t } from "../lib/i18n/i18n.svelte";
  import { formatRemaining } from "../lib/pairingLink";
  import QrCode from "./QrCode.svelte";

  interface Props {
    invite: Invite;
    remainingMs: number;
    /** The device that redeemed the invitation, once it shows up in the list. */
    accepted: Client | null;
    onrenew: () => void;
    onclose: () => void;
  }

  let { invite, remainingMs, accepted, onrenew, onclose }: Props = $props();
</script>

<section class="card invite" aria-labelledby="invite-title">
  <h2 id="invite-title">{t("devices.inviteTitle")}</h2>
  {#if accepted}
    <p role="status">{t("devices.inviteAccepted", { name: accepted.name })}</p>
  {:else if remainingMs > 0}
    <p class="muted">{t("devices.inviteHint")}</p>
    <QrCode rows={invite.qr} label={t("devices.inviteQr")} />
    <div class="field">
      <label for="invite-link">{t("devices.inviteLink")}</label>
      <input
        id="invite-link"
        type="text"
        readonly
        value={invite.url}
        onfocus={(e) => (e.currentTarget as HTMLInputElement).select()}
      />
    </div>
    <p class="muted" role="timer">{t("devices.inviteRemaining", { time: formatRemaining(remainingMs) })}</p>
  {:else}
    <p role="status">{t("devices.inviteExpired")}</p>
  {/if}
  <div class="actions">
    <button type="button" onclick={onclose}>{t("common.close")}</button>
    {#if !accepted && remainingMs <= 0}
      <button type="button" class="primary" onclick={onrenew}>{t("devices.inviteNew")}</button>
    {/if}
  </div>
</section>

<style>
  .invite {
    display: grid;
    gap: 12px;
    max-width: 720px;
    margin-bottom: 16px;
    justify-items: start;
  }
  .invite > * {
    margin: 0;
  }
  .field {
    width: 100%;
  }
  .actions {
    display: flex;
    gap: 8px;
    flex-wrap: wrap;
  }
</style>
