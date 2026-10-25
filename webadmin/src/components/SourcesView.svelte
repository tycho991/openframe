<script lang="ts">
  import { ApiError, type Source } from "../lib/api/client";
  import { errorMessage, errorText } from "../lib/i18n/errors";
  import { t } from "../lib/i18n/i18n.svelte";
  import { isPlainHttp, isShareLink, MAX_SOURCES } from "../lib/sources";
  import { api } from "../lib/session.svelte";
  import SourceCard from "./SourceCard.svelte";

  let sources = $state<Source[] | null>(null);
  let error = $state<string | null>(null);
  let tab = $state<"share" | "account">("share");

  let link = $state("");
  let password = $state("");
  let name = $state("");
  let linkError = $state<string | null>(null);
  let passwordError = $state<string | null>(null);
  let nameError = $state<string | null>(null);
  let shareMessage = $state<string | null>(null);
  let adding = $state(false);

  let serverUrl = $state("");
  let serverError = $state<string | null>(null);
  let login = $state<{ flowId: string; loginUrl: string } | null>(null);
  let loginMessage = $state<string | null>(null);

  const full = $derived((sources?.length ?? 0) >= MAX_SOURCES);

  async function load() {
    try {
      sources = await api.sources();
      error = null;
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
        const polled = await api.pollSourceLogin(flowId);
        if (polled.state === "pending") return;
        login = null;
        if (polled.state === "expired") loginMessage = t("sources.loginExpired");
        else if (polled.state === "failed")
          loginMessage = t("sources.loginFailed", { message: polled.error ?? "" });
        else {
          loginMessage = null;
          serverUrl = "";
          await load();
        }
      } catch (e) {
        login = null;
        loginMessage = errorText(e);
      }
    }, 2000);
    return () => clearInterval(id);
  });

  async function addShare(event: SubmitEvent) {
    event.preventDefault();
    linkError = passwordError = nameError = shareMessage = null;
    if (!isShareLink(link)) {
      linkError = t("sources.linkInvalid");
      return;
    }
    adding = true;
    try {
      const added = await api.addShare({
        link: link.trim(),
        ...(password ? { password } : {}),
        ...(name.trim() ? { name: name.trim() } : {}),
      });
      link = password = name = "";
      // At once, so that it shows its first sync running.
      sources = [...(sources ?? []).filter((s) => s.id !== added.id), added];
    } catch (e) {
      if (e instanceof ApiError && e.fields["password"]) passwordError = errorMessage(e);
      else if (e instanceof ApiError && e.fields["link"])
        linkError = e.code === "validation_failed" ? t("sources.linkInvalid") : errorMessage(e);
      else if (e instanceof ApiError && e.fields["name"]) nameError = t("settings.invalid");
      else shareMessage = errorText(e);
    } finally {
      adding = false;
    }
  }

  async function connect(event: SubmitEvent) {
    event.preventDefault();
    serverError = null;
    loginMessage = null;
    try {
      const started = await api.startSourceLogin(serverUrl.trim());
      login = { flowId: started.flowId, loginUrl: started.loginUrl };
      window.open(started.loginUrl, "_blank", "noopener");
    } catch (e) {
      if (e instanceof ApiError && e.fields["serverUrl"]) serverError = t("sources.serverInvalid");
      else loginMessage = errorText(e);
    }
  }
</script>

<h1>{t("nav.sources")}</h1>
<p class="intro">{t("sources.intro")}</p>
{#if error}<p class="error" role="alert">{error}</p>{/if}

{#if sources}
  <div class="list">
    {#each sources as source (source.id)}
      <SourceCard {source} onchanged={() => void load()} />
    {:else}
      <p class="muted">{t("sources.empty")}</p>
    {/each}
  </div>

  <section class="card add" aria-labelledby="add-source">
    <h2 id="add-source">{t("sources.add")}</h2>
    {#if full}
      <p class="muted" role="status">{t("sources.full")}</p>
    {:else}
      <div class="tabs" role="tablist">
        <button
          type="button"
          role="tab"
          id="tab-share"
          aria-selected={tab === "share"}
          aria-controls="panel-share"
          onclick={() => (tab = "share")}>{t("sources.tab.share")}</button
        >
        <button
          type="button"
          role="tab"
          id="tab-account"
          aria-selected={tab === "account"}
          aria-controls="panel-account"
          onclick={() => (tab = "account")}>{t("sources.tab.account")}</button
        >
      </div>

      {#if tab === "share"}
        <div id="panel-share" role="tabpanel" aria-labelledby="tab-share">
          <form onsubmit={addShare} novalidate>
            <div class="field">
              <label for="share-link">{t("sources.link")}</label>
              <input
                id="share-link"
                type="url"
                inputmode="url"
                placeholder="https://cloud.example.org/s/…"
                bind:value={link}
                disabled={adding}
                aria-invalid={!!linkError}
                aria-describedby="share-link-hint"
              />
              <span class="hint" id="share-link-hint">{t("sources.linkHint")}</span>
              {#if isPlainHttp(link)}<span class="hint warn" role="status">{t("sources.httpWarning")}</span
                >{/if}
              {#if linkError}<span class="error" role="alert">{linkError}</span>{/if}
            </div>
            <div class="field">
              <label for="share-password">{t("sources.password")}</label>
              <input
                id="share-password"
                type="password"
                autocomplete="off"
                bind:value={password}
                disabled={adding}
                aria-invalid={!!passwordError}
                aria-describedby="share-password-hint"
              />
              <span class="hint" id="share-password-hint">{t("sources.passwordHint")}</span>
              {#if passwordError}<span class="error" role="alert">{passwordError}</span>{/if}
            </div>
            <div class="field">
              <label for="share-name">{t("sources.shareName")}</label>
              <input
                id="share-name"
                type="text"
                bind:value={name}
                disabled={adding}
                aria-invalid={!!nameError}
              />
              {#if nameError}<span class="error" role="alert">{nameError}</span>{/if}
            </div>
            <div class="actions">
              {#if adding}<span role="status">{t("sources.adding")}</span>{/if}
              <button type="submit" class="primary" disabled={link.trim() === "" || adding}
                >{t("sources.addShare")}</button
              >
            </div>
            {#if shareMessage}<p class="error" role="alert">{shareMessage}</p>{/if}
          </form>
        </div>
      {:else}
        <div id="panel-account" role="tabpanel" aria-labelledby="tab-account">
          <form onsubmit={connect} novalidate>
            <div class="field">
              <label for="nc-server">{t("sources.server")}</label>
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
              <span class="hint" id="nc-server-hint">{t("sources.serverHint")}</span>
              {#if isPlainHttp(serverUrl)}<span class="hint warn" role="status"
                  >{t("sources.httpWarning")}</span
                >{/if}
              {#if serverError}<span class="error" role="alert">{serverError}</span>{/if}
            </div>
            {#if login}
              <p role="status">{t("sources.waiting")}</p>
              <p>
                <a href={login.loginUrl} target="_blank" rel="noopener noreferrer">{t("sources.openLogin")}</a
                >
                <button type="button" onclick={() => (login = null)}>{t("sources.cancelLogin")}</button>
              </p>
            {:else}
              <div class="actions">
                <button type="submit" class="primary" disabled={serverUrl.trim() === ""}
                  >{t("sources.connect")}</button
                >
              </div>
            {/if}
            {#if loginMessage}<p class="error" role="alert">{loginMessage}</p>{/if}
          </form>
        </div>
      {/if}
    {/if}
  </section>
{:else if !error}
  <p class="muted">{t("common.loading")}</p>
{/if}

<style>
  .intro {
    max-width: 720px;
  }
  .list,
  .add {
    display: grid;
    gap: 16px;
    max-width: 720px;
  }
  .add {
    margin-top: 16px;
  }
  .add h2 {
    margin: 0;
    font-size: 1.15rem;
  }
  .tabs {
    display: flex;
    gap: 4px;
    flex-wrap: wrap;
  }
  .tabs button {
    border-radius: 999px;
  }
  .tabs button[aria-selected="true"] {
    background: var(--accent);
    color: var(--accent-text);
    border-color: var(--accent);
  }
  form {
    display: grid;
    gap: 14px;
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
