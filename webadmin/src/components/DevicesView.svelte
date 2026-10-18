<script lang="ts">
  import type { Client } from "../lib/api/client";
  import { i18n, t } from "../lib/i18n/i18n.svelte";
  import { api, session, tokens } from "../lib/session.svelte";
  import ConfirmDialog from "./ConfirmDialog.svelte";

  let clients = $state<Client[] | null>(null);
  let error = $state<string | null>(null);
  let confirm = $state<Client | null>(null);

  async function load() {
    try {
      clients = await api.clients();
    } catch (e) {
      error = t("common.error", { message: e instanceof Error ? e.message : String(e) });
    }
  }

  $effect(() => {
    void load();
  });

  function date(iso: string) {
    return new Date(iso).toLocaleString(i18n.lang, { dateStyle: "medium", timeStyle: "short" });
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
      error = t("common.error", { message: e instanceof Error ? e.message : String(e) });
    }
  }
</script>

<h1>{t("devices.title")}</h1>
{#if error}<p class="error" role="alert">{error}</p>{/if}
{#if clients === null}
  <p class="muted">{t("common.loading")}</p>
{:else}
  <ul class="devices">
    {#each clients as client (client.id)}
      <li class="card">
        <div class="info">
          <h2>
            {client.name}
            {#if client.current}<span class="badge">{t("devices.current")}</span>{/if}
          </h2>
          <p class="muted">{t("devices.paired", { date: date(client.pairedAt) })}</p>
          <p class="muted">{t("devices.lastSeen", { date: date(client.lastSeenAt) })}</p>
        </div>
        <button type="button" class="danger" onclick={() => (confirm = client)}>{t("devices.remove")}</button>
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
    color: var(--accent-text);
    vertical-align: middle;
    margin-left: 6px;
  }
</style>
