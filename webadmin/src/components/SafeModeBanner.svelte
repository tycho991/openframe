<script lang="ts">
  import { errorText } from "../lib/i18n/errors";
  import { t } from "../lib/i18n/i18n.svelte";
  import { RESTART_POLL_MS, safeModeBanner } from "../lib/safeMode";
  import { api, refreshState, session } from "../lib/session.svelte";

  let requested = $state(false);
  let error = $state<string | null>(null);
  const shown = $derived(safeModeBanner(session.safeMode, requested));

  // While the frame restarts, ask it more often than the usual poll, until it reports normal mode.
  $effect(() => {
    if (shown !== "restarting") return;
    const timer = setInterval(() => void refreshState(), RESTART_POLL_MS);
    return () => clearInterval(timer);
  });

  $effect(() => {
    if (!session.safeMode) requested = false;
  });

  async function startNormally() {
    error = null;
    try {
      await api.exitSafeMode();
      requested = true;
    } catch (e) {
      error = errorText(e);
    }
  }
</script>

{#if shown !== "hidden"}
  <section class="card banner" role="status">
    <div>
      <p class="title">{t("safe.title")}</p>
      <p>{shown === "restarting" ? t("safe.restarting") : t("safe.text")}</p>
      {#if error}<p class="error" role="alert">{error}</p>{/if}
    </div>
    {#if shown === "offer"}
      <button type="button" class="primary" onclick={startNormally}>{t("safe.startNormally")}</button>
    {/if}
  </section>
{/if}

<style>
  .banner {
    display: flex;
    gap: var(--space-3);
    align-items: center;
    justify-content: space-between;
    flex-wrap: wrap;
    margin-bottom: var(--space-4);
    max-width: 720px;
    border-color: color-mix(in srgb, var(--danger) 40%, var(--divider));
  }
  p {
    margin: 0;
    overflow-wrap: anywhere;
  }
  .title {
    font-weight: 600;
  }
  .error {
    color: var(--danger);
  }
</style>
