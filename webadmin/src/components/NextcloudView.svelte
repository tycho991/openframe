<script lang="ts">
  import { ApiError, type NextcloudSettings, type NextcloudState } from "../lib/api/client";
  import { errorText } from "../lib/i18n/errors";
  import { i18n, t } from "../lib/i18n/i18n.svelte";
  import { formatTime, isPlainHttp, nextcloudRanges, settingsPatch } from "../lib/nextcloud";
  import { api } from "../lib/session.svelte";
  import ConfirmDialog from "./ConfirmDialog.svelte";

  let nc = $state<NextcloudState | null>(null);
  let form = $state<NextcloudSettings | null>(null);
  let errors = $state<Record<string, string>>({});
  let error = $state<string | null>(null);
  let saving = $state(false);
  let saved = $state(false);

  let serverUrl = $state("");
  let serverError = $state<string | null>(null);
  let login = $state<{ flowId: string; loginUrl: string } | null>(null);
  let loginMessage = $state<string | null>(null);
  let syncMessage = $state<string | null>(null);
  let confirmDisconnect = $state(false);

  const dirty = $derived(
    form !== null && nc !== null && Object.keys(settingsPatch(nc.settings, form)).length > 0,
  );

  async function load() {
    try {
      const state = await api.nextcloud();
      nc = state;
      // A form with changes that are not saved yet is not overwritten.
      if (form === null || !dirty) form = structuredClone(state.settings);
    } catch (e) {
      error = errorText(e);
    }
  }

  // The status of a running sync and a change on another device show up without a reload.
  $effect(() => {
    void load();
    const id = setInterval(() => void load(), 3000);
    return () => clearInterval(id);
  });

  // While the user approves in the other tab, ask the frame every two seconds.
  $effect(() => {
    if (!login) return;
    const flowId = login.flowId;
    const id = setInterval(async () => {
      try {
        const polled = await api.pollNextcloudLogin(flowId);
        if (polled.state === "pending") return;
        login = null;
        if (polled.state === "expired") loginMessage = t("nextcloud.loginExpired");
        else if (polled.state === "failed")
          loginMessage = t("nextcloud.loginFailed", { message: polled.error ?? "" });
        else {
          loginMessage = null;
          form = null;
          await load();
        }
      } catch (e) {
        login = null;
        loginMessage = errorText(e);
      }
    }, 2000);
    return () => clearInterval(id);
  });

  async function connect(event: SubmitEvent) {
    event.preventDefault();
    serverError = null;
    loginMessage = null;
    try {
      const started = await api.startNextcloudLogin(serverUrl.trim());
      login = { flowId: started.flowId, loginUrl: started.loginUrl };
      window.open(started.loginUrl, "_blank", "noopener");
    } catch (e) {
      if (e instanceof ApiError && e.fields["serverUrl"]) serverError = t("nextcloud.serverInvalid");
      else loginMessage = errorText(e);
    }
  }

  async function save(event: SubmitEvent) {
    event.preventDefault();
    if (!form || !nc) return;
    saving = true;
    errors = {};
    error = null;
    try {
      nc = await api.patchNextcloud(settingsPatch(nc.settings, form));
      form = structuredClone(nc.settings);
      saved = true;
      setTimeout(() => (saved = false), 3000);
    } catch (e) {
      if (e instanceof ApiError && Object.keys(e.fields).length > 0) errors = e.fields;
      else error = errorText(e);
    } finally {
      saving = false;
    }
  }

  async function syncNow() {
    syncMessage = null;
    try {
      const res = await api.syncNextcloud();
      if (!res.started) syncMessage = t("nextcloud.alreadySyncing");
      await load();
    } catch (e) {
      error = errorText(e);
    }
  }

  async function disconnect() {
    confirmDisconnect = false;
    try {
      await api.disconnectNextcloud();
      form = null;
      await load();
    } catch (e) {
      error = errorText(e);
    }
  }

  function fieldError(key: string): string | undefined {
    if (!errors[key]) return undefined;
    const range = nextcloudRanges[key];
    return range ? t("settings.range", { min: range[0], max: range[1] }) : t("settings.invalid");
  }
</script>

<h1>{t("nav.nextcloud")}</h1>
{#if error}<p class="error" role="alert">{error}</p>{/if}

{#if nc && !nc.connected}
  <form class="card connect" onsubmit={connect} novalidate>
    <p>{t("nextcloud.intro")}</p>
    <div class="field">
      <label for="nc-server">{t("nextcloud.server")}</label>
      <input
        id="nc-server"
        type="url"
        inputmode="url"
        autocomplete="url"
        placeholder="https://cloud.example.org"
        bind:value={serverUrl}
        disabled={login !== null}
        aria-invalid={!!serverError}
        aria-describedby="nc-server-hint"
      />
      <span class="hint" id="nc-server-hint">{t("nextcloud.serverHint")}</span>
      {#if isPlainHttp(serverUrl)}<span class="hint warn" role="status">{t("nextcloud.httpWarning")}</span
        >{/if}
      {#if serverError}<span class="error" role="alert">{serverError}</span>{/if}
    </div>
    {#if login}
      <p role="status">{t("nextcloud.waiting")}</p>
      <p>
        <a href={login.loginUrl} target="_blank" rel="noopener noreferrer">{t("nextcloud.openLogin")}</a>
        <button type="button" onclick={() => (login = null)}>{t("nextcloud.cancelLogin")}</button>
      </p>
    {:else}
      <div class="actions">
        <button type="submit" class="primary" disabled={serverUrl.trim() === ""}
          >{t("nextcloud.connect")}</button
        >
      </div>
    {/if}
    {#if loginMessage}<p class="error" role="alert">{loginMessage}</p>{/if}
  </form>
{:else if nc && form}
  <p>{t("nextcloud.connectedAs", { server: nc.serverUrl ?? "", user: nc.user ?? "" })}</p>

  <form onsubmit={save} novalidate>
    <fieldset class="card">
      <legend>{t("nextcloud.section.folder")}</legend>
      <label class="check">
        <input type="checkbox" bind:checked={form.enabled} />
        <span>{t("nextcloud.enabled")}</span>
      </label>
      <div class="field">
        <label for="nc-folder">{t("nextcloud.folder")}</label>
        <input id="nc-folder" type="text" bind:value={form.folder} aria-invalid={!!errors["folder"]} />
        <span class="hint">{t("nextcloud.folderHint")}</span>
        {#if fieldError("folder")}<span class="error" role="alert">{fieldError("folder")}</span>{/if}
      </div>
      <label class="check">
        <input type="checkbox" bind:checked={form.includeSubfolders} />
        <span>{t("nextcloud.includeSubfolders")}</span>
      </label>
      <label class="check">
        <input type="checkbox" bind:checked={form.removeVanished} />
        <span>{t("nextcloud.removeVanished")}</span>
      </label>
      <div class="field">
        <label for="nc-interval">{t("nextcloud.interval")}</label>
        <input
          id="nc-interval"
          type="number"
          inputmode="numeric"
          bind:value={form.intervalMinutes}
          aria-invalid={!!errors["intervalMinutes"]}
        />
        {#if fieldError("intervalMinutes")}<span class="error" role="alert"
            >{fieldError("intervalMinutes")}</span
          >{/if}
      </div>
      <div class="field">
        <label for="nc-budget">{t("nextcloud.budget")}</label>
        <input
          id="nc-budget"
          type="number"
          inputmode="numeric"
          bind:value={form.originalsBudgetMb}
          aria-invalid={!!errors["originalsBudgetMb"]}
        />
        <span class="hint">{t("nextcloud.budgetHint")}</span>
        {#if fieldError("originalsBudgetMb")}<span class="error" role="alert"
            >{fieldError("originalsBudgetMb")}</span
          >{/if}
      </div>
      <div class="actions">
        {#if saved}<span role="status" class="saved">{t("common.saved")}</span>{/if}
        <button type="submit" class="primary" disabled={!dirty || saving}>{t("nextcloud.save")}</button>
      </div>
    </fieldset>
  </form>

  <section class="card status" aria-labelledby="nc-status">
    <h2 id="nc-status">{t("nextcloud.section.status")}</h2>
    {#if nc.status.syncing}
      <p role="status">{t("nextcloud.syncing")}</p>
    {:else if nc.status.ok === null}
      <p class="muted">{t("nextcloud.never")}</p>
    {:else if nc.status.ok}
      <p>
        {t("nextcloud.lastOk", {
          time: formatTime(nc.status.lastSyncAt, i18n.lang),
          added: nc.status.added,
          updated: nc.status.updated,
          removed: nc.status.removed,
        })}
      </p>
    {:else}
      <p class="error" role="alert">
        {t("nextcloud.lastFailed", {
          time: formatTime(nc.status.lastSyncAt, i18n.lang),
          error: nc.status.error ?? "",
        })}
      </p>
    {/if}
    {#if syncMessage}<p role="status">{syncMessage}</p>{/if}
    <div class="actions">
      <button type="button" onclick={syncNow} disabled={nc.status.syncing}>{t("nextcloud.sync")}</button>
      <button type="button" class="danger" onclick={() => (confirmDisconnect = true)}
        >{t("nextcloud.disconnect")}</button
      >
    </div>
  </section>
{:else if !error}
  <p class="muted">{t("common.loading")}</p>
{/if}

{#if confirmDisconnect}
  <ConfirmDialog
    message={t("nextcloud.confirmDisconnect")}
    confirmLabel={t("nextcloud.disconnect")}
    oncancel={() => (confirmDisconnect = false)}
    onconfirm={disconnect}
  />
{/if}

<style>
  form,
  .status {
    display: grid;
    gap: 16px;
    max-width: 720px;
  }
  .status {
    margin-top: 16px;
  }
  fieldset {
    display: grid;
    gap: 14px;
    margin: 0;
    min-width: 0;
  }
  legend {
    font-weight: 700;
    padding: 0 4px;
  }
  .check {
    display: flex;
    gap: 8px;
    align-items: center;
    font-weight: 600;
  }
  .check input {
    width: 20px;
    height: 20px;
    accent-color: var(--accent);
  }
  .actions {
    display: flex;
    gap: 12px;
    justify-content: flex-end;
    align-items: center;
    flex-wrap: wrap;
  }
  .warn {
    color: var(--warning, #a15c00);
  }
</style>
