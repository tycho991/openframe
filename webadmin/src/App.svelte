<script lang="ts">
  import AlbumsView from "./components/AlbumsView.svelte";
  import DevicesView from "./components/DevicesView.svelte";
  import PairedBanner from "./components/PairedBanner.svelte";
  import PairingDialog from "./components/PairingDialog.svelte";
  import PhotosView from "./components/PhotosView.svelte";
  import SettingsView from "./components/SettingsView.svelte";
  import SourcesView from "./components/SourcesView.svelte";
  import type { Lang } from "./lib/i18n/detect";
  import { i18n, t } from "./lib/i18n/i18n.svelte";
  import { router, ROUTES, type Route } from "./lib/router.svelte";
  import { takePairingSecret } from "./lib/pairingLink";
  import { api, redeemPairingLink, session, startStatePolling } from "./lib/session.svelte";

  // Opened through a pairing link (QR code on the frame or invitation): pair on start,
  // or when the link is opened in a tab that already shows the interface.
  function onPairingLink() {
    const secret = takePairingSecret();
    if (secret) void redeemPairingLink(secret);
  }
  onPairingLink();

  $effect(() => {
    addEventListener("hashchange", onPairingLink);
    return () => removeEventListener("hashchange", onPairingLink);
  });

  const labels: Record<Route, Parameters<typeof t>[0]> = {
    photos: "nav.photos",
    albums: "nav.albums",
    sources: "nav.sources",
    settings: "nav.settings",
    devices: "nav.devices",
  };

  $effect(() => {
    document.documentElement.lang = i18n.lang;
  });

  $effect(() => {
    api
      .info()
      .then((info) => (session.info = info))
      .catch(() => {});
  });

  $effect(() => {
    if (!session.paired) return;
    return startStatePolling();
  });

  function onLangChange(event: Event) {
    const value = (event.target as HTMLSelectElement).value;
    i18n.choose(value === "auto" ? null : (value as Lang));
  }

  function navigate(event: MouseEvent, route: Route) {
    if (event.metaKey || event.ctrlKey || event.shiftKey || event.button !== 0) return;
    event.preventDefault();
    router.go(route);
  }
</script>

{#if session.linkPending}
  <main class="pending" aria-busy="true">
    <p class="muted" role="status">{t("pair.linkPending")}</p>
  </main>
{:else if !session.paired}
  <PairingDialog />
{:else}
  <header>
    <div class="brand">
      <span class="logo" aria-hidden="true"></span>
      <span>{session.info?.name ?? t("app.title")}</span>
    </div>
    <nav aria-label="Navigation">
      {#each ROUTES as route (route)}
        <a
          href={"/" + route}
          aria-current={router.route === route ? "page" : undefined}
          onclick={(e) => navigate(e, route)}>{t(labels[route])}</a
        >
      {/each}
    </nav>
    <label class="lang">
      <span class="sr-only">{t("lang.label")}</span>
      <select value={i18n.chosen ?? "auto"} onchange={onLangChange}>
        <option value="auto">{t("lang.auto")}</option>
        <option value="de">Deutsch</option>
        <option value="en">English</option>
      </select>
    </label>
  </header>

  <main>
    {#if session.pairedAs}<PairedBanner client={session.pairedAs} />{/if}
    {#if router.route === "photos"}
      <PhotosView />
    {:else if router.route === "albums"}
      <AlbumsView />
    {:else if router.route === "sources"}
      <SourcesView />
    {:else if router.route === "settings"}
      <SettingsView />
    {:else}
      <DevicesView />
    {/if}
  </main>
{/if}

<style>
  header {
    position: sticky;
    top: 0;
    z-index: 10;
    display: flex;
    align-items: center;
    gap: 12px;
    flex-wrap: wrap;
    padding: 10px 16px;
    background: color-mix(in srgb, var(--bg) 88%, transparent);
    backdrop-filter: blur(8px);
    border-bottom: 1px solid var(--border);
  }
  .brand {
    display: flex;
    align-items: center;
    gap: 8px;
    font-weight: 700;
    min-width: 0;
    flex: 1 1 auto;
  }
  .brand span:last-child {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .logo {
    width: 22px;
    height: 22px;
    border-radius: 6px;
    border: 3px solid var(--accent);
    flex: none;
  }
  nav {
    display: flex;
    gap: 4px;
    order: 3;
    width: 100%;
    overflow-x: auto;
  }
  nav a {
    padding: 8px 12px;
    border-radius: 999px;
    color: var(--text);
    text-decoration: none;
    white-space: nowrap;
  }
  nav a[aria-current="page"] {
    background: var(--accent);
    color: var(--accent-text);
  }
  .lang {
    width: auto;
  }
  .lang select {
    width: auto;
  }
  .pending {
    min-height: 100dvh;
    display: grid;
    place-items: center;
  }
  main {
    padding: 16px;
    max-width: 1400px;
    margin: 0 auto;
  }
  @media (min-width: 760px) {
    nav {
      order: 0;
      width: auto;
      flex: none;
    }
  }
</style>
