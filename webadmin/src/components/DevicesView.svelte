<script lang="ts">
  import type { Client, Invite } from "../lib/api/client";
  import { i18n, t } from "../lib/i18n/i18n.svelte";
  import { errorText } from "../lib/i18n/errors";
  import { inviteDeadline } from "../lib/pairingLink";
  import { api, session, tokens } from "../lib/session.svelte";
  import ConfirmDialog from "./ConfirmDialog.svelte";
  import InvitePanel from "./InvitePanel.svelte";
  import RenameForm from "./RenameForm.svelte";

  /** How often the list is reloaded while the view is visible, so that invited devices appear. */
  const RELOAD_MS = 5_000;

  let clients = $state<Client[] | null>(null);
  let error = $state<string | null>(null);
  let confirm = $state<Client | null>(null);
  let renaming = $state<string | null>(null);

  let invite = $state<Invite | null>(null);
  let deadline = $state(0);
  let now = $state(Date.now());
  // Devices that were paired before the invitation, to recognize the invited one.
  let knownIds = new Set<string>();

  const accepted = $derived(invite && clients ? (clients.find((c) => !knownIds.has(c.id)) ?? null) : null);

  function message(e: unknown) {
    return errorText(e);
  }

  async function load() {
    try {
      clients = await api.clients();
    } catch (e) {
      error = message(e);
    }
  }

  $effect(() => {
    void load();
    const reload = setInterval(() => {
      if (document.visibilityState === "visible") void load();
    }, RELOAD_MS);
    // Counts down the invitation's remaining time.
    const clock = setInterval(() => (now = Date.now()), 1000);
    return () => {
      clearInterval(reload);
      clearInterval(clock);
    };
  });

  async function createInvite() {
    error = null;
    try {
      knownIds = new Set((clients ?? []).map((c) => c.id));
      invite = await api.createInvite();
      now = Date.now();
      deadline = inviteDeadline(invite.expiresAt, now);
    } catch (e) {
      error = message(e);
    }
  }

  function date(iso: string) {
    return new Date(iso).toLocaleString(i18n.lang, { dateStyle: "medium", timeStyle: "short" });
  }

  async function rename(client: Client, name: string) {
    const renamed = await api.renameClient(client.id, name);
    clients = (clients ?? []).map((c) => (c.id === renamed.id ? renamed : c));
    if (session.pairedAs?.id === renamed.id) session.pairedAs = renamed;
    renaming = null;
  }

  async function remove(client: Client) {
    confirm = null;
    try {
      await api.deleteClient(client.id);
      if (client.current) {
        tokens.set(null);
        session.paired = false;
        return;
      }
      await load();
    } catch (e) {
      error = message(e);
    }
  }
</script>

<div class="title">
  <h1>{t("devices.title")}</h1>
  {#if !invite}
    <button type="button" class="primary" onclick={createInvite}>{t("devices.invite")}</button>
  {/if}
</div>
{#if error}<p class="error" role="alert">{error}</p>{/if}

{#if invite}
  <InvitePanel
    {invite}
    remainingMs={deadline - now}
    {accepted}
    onrenew={createInvite}
    onclose={() => (invite = null)}
  />
{/if}

{#if clients === null}
  <p class="muted">{t("common.loading")}</p>
{:else}
  <ul class="devices">
    {#each clients as client (client.id)}
      <li class="card">
        {#if renaming === client.id}
          <RenameForm
            name={client.name}
            onsave={(name) => rename(client, name)}
            oncancel={() => (renaming = null)}
          />
        {:else}
          <div class="info">
            <h2>
              {client.name}
              {#if client.current}<span class="badge">{t("devices.current")}</span>{/if}
            </h2>
            <p class="muted">{t("devices.paired", { date: date(client.pairedAt) })}</p>
            <p class="muted">{t("devices.lastSeen", { date: date(client.lastSeenAt) })}</p>
          </div>
          <div class="buttons">
            <button type="button" onclick={() => (renaming = client.id)}>{t("devices.rename")}</button>
            <button type="button" class="danger" onclick={() => (confirm = client)}
              >{t("devices.remove")}</button
            >
          </div>
        {/if}
      </li>
    {/each}
  </ul>
{/if}

{#if confirm}
  <ConfirmDialog
    message={confirm.current
      ? t("devices.confirmRemoveSelf")
      : t("devices.confirmRemove", { name: confirm.name })}
    confirmLabel={t("devices.remove")}
    oncancel={() => (confirm = null)}
    onconfirm={() => remove(confirm!)}
  />
{/if}

<style>
  .title {
    display: flex;
    gap: 12px;
    align-items: center;
    justify-content: space-between;
    flex-wrap: wrap;
    max-width: 720px;
    margin-bottom: 12px;
  }
  .title h1 {
    margin: 0;
  }
  .devices {
    list-style: none;
    padding: 0;
    margin: 0;
    display: grid;
    gap: 10px;
    max-width: 720px;
  }
  li {
    display: flex;
    gap: 12px;
    align-items: center;
    justify-content: space-between;
    flex-wrap: wrap;
  }
  .info {
    min-width: 0;
  }
  .buttons {
    display: flex;
    gap: 8px;
    flex-wrap: wrap;
  }
  h2 {
    font-size: 1.05rem;
    margin: 0 0 2px;
    overflow-wrap: anywhere;
  }
  .info p {
    margin: 0;
    font-size: 0.9rem;
  }
  .badge {
    font-size: 0.75rem;
    font-weight: 600;
    padding: 2px 8px;
    border-radius: 999px;
    background: var(--accent);
    color: var(--on-accent);
    vertical-align: middle;
    margin-left: 6px;
  }
</style>
