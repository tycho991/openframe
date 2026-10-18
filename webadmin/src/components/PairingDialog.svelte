<script lang="ts">
  import { ApiError } from "../lib/api/client";
  import { t } from "../lib/i18n/i18n.svelte";
  import { api, session } from "../lib/session.svelte";

  let code = $state("");
  let deviceName = $state("");
  let busy = $state(false);
  let message = $state<string | null>(null);
  let error = $state<string | null>(null);

  const valid = $derived(/^[0-9]{6}$/.test(code));

  function describe(e: unknown): string {
    if (!(e instanceof ApiError)) return t("pair.network");
    switch (e.code) {
      case "invalid_code":
        return t("pair.invalid");
      case "expired_code":
        return t("pair.expired");
      case "locked":
        return t("pair.locked", { seconds: /[0-9]+/.exec(e.message)?.[0] ?? "60" });
      case "network":
        return t("pair.network");
      default:
        return t("common.error", { message: e.message });
    }
  }

  async function submit(event: SubmitEvent) {
    event.preventDefault();
    if (!valid || busy) return;
    busy = true;
    error = null;
    try {
      await api.pair(code, deviceName.trim() || undefined);
      session.paired = true;
    } catch (e) {
      error = describe(e);
      code = "";
    } finally {
      busy = false;
    }
  }

  async function showCode() {
    error = null;
    message = null;
    try {
      await api.showCodeOnFrame();
      message = t("pair.codeShown");
    } catch (e) {
      error = describe(e);
    }
  }
</script>

<main class="pairing">
  <form class="card" onsubmit={submit} aria-labelledby="pair-title">
    <h1 id="pair-title">{t("pair.title")}</h1>
    {#if session.info}<p class="frame-name">{session.info.name}</p>{/if}
    <p class="muted">{t("pair.hint")}</p>

    <div class="field">
      <label for="pair-code">{t("pair.code")}</label>
      <input
        id="pair-code"
        type="text"
        inputmode="numeric"
        autocomplete="one-time-code"
        maxlength="6"
        class="code"
        bind:value={code}
        oninput={() => (code = code.replace(/\D/g, "").slice(0, 6))}
      />
    </div>

    <div class="field">
      <label for="pair-name">{t("pair.deviceName")}</label>
      <input
        id="pair-name"
        type="text"
        maxlength="64"
        placeholder={t("pair.deviceNamePlaceholder")}
        bind:value={deviceName}
      />
    </div>

    {#if error}<p class="error" role="alert">{error}</p>{/if}
    {#if message}<p class="muted" role="status">{message}</p>{/if}

    <div class="actions">
      <button type="button" onclick={showCode}>{t("pair.showCode")}</button>
      <button type="submit" class="primary" disabled={!valid || busy}>{t("pair.submit")}</button>
    </div>
  </form>
</main>

<style>
  .pairing {
    min-height: 100dvh;
    display: grid;
    place-items: center;
    padding: 16px;
  }
  form {
    width: min(420px, 100%);
    display: grid;
    gap: 14px;
  }
  .frame-name {
    margin: -6px 0 0;
    font-weight: 600;
  }
  .code {
    font-size: 1.8rem;
    letter-spacing: 0.35em;
    text-align: center;
    font-variant-numeric: tabular-nums;
  }
  .actions {
    display: flex;
    gap: 8px;
    justify-content: space-between;
    flex-wrap: wrap;
  }
</style>
