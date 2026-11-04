<script lang="ts">
  import AlbumsView from "./components/AlbumsView.svelte";
  import AppearanceMenu from "./components/AppearanceMenu.svelte";
  import DevicesView from "./components/DevicesView.svelte";
  import Icon from "./components/Icon.svelte";
  import PairedBanner from "./components/PairedBanner.svelte";
  import SafeModeBanner from "./components/SafeModeBanner.svelte";
  import PairingDialog from "./components/PairingDialog.svelte";
  import PhotosView from "./components/PhotosView.svelte";
  import ReloadBanner from "./components/ReloadBanner.svelte";
  import SettingsView from "./components/SettingsView.svelte";
  import SourcesView from "./components/SourcesView.svelte";
  import { i18n, t, type Key } from "./lib/i18n/i18n.svelte";
  import type { IconName } from "./lib/icons";
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

  const sections: Record<Route, { label: Key; icon: IconName }> = {
    photos: { label: "nav.photos", icon: "photoLibrary" },
    albums: { label: "nav.albums", icon: "photoAlbum" },
    sources: { label: "nav.sources", icon: "cloud" },
    settings: { label: "nav.settings", icon: "settings" },
    devices: { label: "nav.devices", icon: "devices" },
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

  function navigate(event: MouseEvent, route: Route) {
    if (event.metaKey || event.ctrlKey || event.shiftKey || event.button !== 0) return;
    event.preventDefault();
    router.go(route);
  }
</script>

{#snippet brand()}
  <span class="logo" aria-hidden="true"></span>
  <span class="name">{session.info?.name ?? t("app.title")}</span>
{/snippet}

{#if session.linkPending}
  <main class="pending" aria-busy="true">
    <p class="muted" role="status">{t("pair.linkPending")}</p>
  </main>
{:else if !session.paired}
  <PairingDialog />
{:else}
  <!-- One DOM for every width (design-system, design.md, decision 7): the media queries below place the navigation
       as a bar at the bottom, a rail or a side navigation, so crossing a width keeps scroll position, focus and input. -->
  <div class="shell">
    <header class="top-bar">
      <div class="brand">{@render brand()}</div>
      <button type="button" class="icon-button" popovertarget="appearance" aria-label={t("appearance.open")}>
        <Icon name="palette" />
      </button>
    </header>

    <nav aria-label={t("nav.label")}>
      <div class="brand side-only">{@render brand()}</div>
      <div class="links">
        {#each ROUTES as route (route)}
          {@const current = router.route === route}
          <a
            href={"/" + route}
            aria-current={current ? "page" : undefined}
            onclick={(e) => navigate(e, route)}
          >
            <span class="indicator"><Icon name={sections[route].icon} filled={current} /></span>
            <span class="label">{t(sections[route].label)}</span>
          </a>
        {/each}
      </div>
      <button type="button" class="appearance side-only" popovertarget="appearance">
        <Icon name="palette" />
        <span>{t("appearance.open")}</span>
      </button>
    </nav>

    <main>
      <!-- Forms and lists in a centred column; the photo grid uses the full width. -->
      <div class:column={router.route !== "photos"}>
        <SafeModeBanner />
        <ReloadBanner />
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
      </div>
    </main>
  </div>
  <AppearanceMenu />
{/if}

<style>
  /* Compact, below 600 px: top bar, section, navigation bar at the bottom. */
  .shell {
    /* What the navigation covers, for bars that the sections keep at the bottom of the screen. */
    --content-bottom: calc(var(--bar-height) + env(safe-area-inset-bottom));
    --content-left: 0px;
    min-height: 100dvh;
    display: grid;
    grid-template-columns: minmax(0, 1fr);
    grid-template-rows: auto 1fr auto;
    grid-template-areas: "top" "main" "nav";
  }
  .top-bar {
    grid-area: top;
    position: sticky;
    top: 0;
    z-index: 10;
    display: flex;
    align-items: center;
    gap: var(--space-2);
    min-height: calc(var(--bar-height) + env(safe-area-inset-top));
    padding: env(safe-area-inset-top) calc(var(--space-1) + env(safe-area-inset-right)) 0
      calc(var(--space-4) + env(safe-area-inset-left));
    background: color-mix(in srgb, var(--bg) 88%, transparent);
    backdrop-filter: blur(8px);
    border-bottom: 1px solid var(--divider);
  }
  .brand {
    display: flex;
    align-items: center;
    gap: var(--space-2);
    min-width: 0;
    flex: 1 1 auto;
    font-weight: 700;
  }
  .name {
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
  .icon-button {
    display: grid;
    place-items: center;
    width: var(--target);
    height: var(--target);
    min-height: var(--target);
    padding: 0;
    border: 0;
    border-radius: var(--radius-full);
    background: none;
  }
  .icon-button:hover {
    background: var(--surface-2);
  }
  nav {
    grid-area: nav;
    position: sticky;
    bottom: 0;
    z-index: 10;
    padding: 0 env(safe-area-inset-right) env(safe-area-inset-bottom) env(safe-area-inset-left);
    background: var(--surface);
    border-top: 1px solid var(--divider);
  }
  .links {
    display: flex;
  }
  nav a {
    display: flex;
    flex: 1 1 0;
    min-width: 0;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: var(--space-1);
    min-height: var(--bar-height);
    padding: var(--space-2) var(--space-1);
    color: var(--muted);
    font-size: var(--text-caption);
    font-weight: 500;
    text-decoration: none;
  }
  nav a[aria-current="page"] {
    color: var(--text);
    font-weight: 600;
  }
  .indicator {
    display: grid;
    place-items: center;
    width: 56px;
    height: 32px;
    border-radius: var(--radius-full);
    transition: background-color var(--motion-short) var(--ease);
  }
  nav a:hover .indicator {
    background: var(--surface-2);
  }
  nav a[aria-current="page"] .indicator {
    background: color-mix(in srgb, var(--accent) 24%, transparent);
  }
  .label {
    max-width: 100%;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .side-only {
    display: none;
  }
  main {
    grid-area: main;
    min-width: 0;
    padding: var(--space-4) calc(var(--space-4) + env(safe-area-inset-right)) var(--space-5)
      calc(var(--space-4) + env(safe-area-inset-left));
  }
  .column {
    max-width: var(--form-width);
    margin-inline: auto;
  }
  .pending {
    min-height: 100dvh;
    display: grid;
    place-items: center;
  }

  /* Medium, from 600 px: a rail at the left edge next to top bar and section. */
  @media (min-width: 600px) {
    .shell {
      --content-bottom: env(safe-area-inset-bottom);
      --content-left: calc(var(--rail-width) + env(safe-area-inset-left));
      grid-template-columns: calc(var(--rail-width) + env(safe-area-inset-left)) minmax(0, 1fr);
      grid-template-rows: auto 1fr;
      grid-template-areas: "nav top" "nav main";
    }
    .top-bar {
      padding-left: var(--space-5);
    }
    nav {
      top: 0;
      bottom: auto;
      height: 100dvh;
      padding: calc(var(--space-3) + env(safe-area-inset-top)) 0 env(safe-area-inset-bottom)
        env(safe-area-inset-left);
      border-top: 0;
      border-right: 1px solid var(--divider);
    }
    .links {
      flex-direction: column;
      gap: var(--space-2);
    }
    nav a {
      flex: none;
    }
    main {
      padding-left: var(--space-5);
      padding-right: calc(var(--space-5) + env(safe-area-inset-right));
    }
  }

  /* Expanded, from 1200 px: a side navigation with labels, the frame's name and the appearance button. */
  @media (min-width: 1200px) {
    .shell {
      --content-left: var(--side-nav-width);
      grid-template-columns: var(--side-nav-width) minmax(0, 1fr);
      grid-template-rows: 1fr;
      grid-template-areas: "nav main";
    }
    .top-bar {
      display: none;
    }
    nav {
      display: flex;
      flex-direction: column;
      padding: var(--space-4) var(--space-3);
    }
    .side-only {
      display: flex;
    }
    nav .brand {
      flex: none;
      padding: var(--space-2) var(--space-3) var(--space-5);
    }
    .links {
      gap: var(--space-1);
    }
    nav a {
      flex-direction: row;
      justify-content: flex-start;
      gap: var(--space-3);
      min-height: 48px;
      padding: 0 var(--space-4) 0 var(--space-2);
      border-radius: var(--radius-full);
      color: var(--text);
      font-size: var(--text-label);
      transition: background-color var(--motion-short) var(--ease);
    }
    nav a:hover {
      background: var(--surface-2);
    }
    nav a[aria-current="page"] {
      background: color-mix(in srgb, var(--accent) 20%, transparent);
    }
    nav a .indicator,
    nav a:hover .indicator,
    nav a[aria-current="page"] .indicator {
      width: 32px;
      background: none;
    }
    .appearance {
      align-items: center;
      gap: var(--space-3);
      margin-top: auto;
      min-height: 48px;
      padding: 0 var(--space-4) 0 var(--space-3);
      border: 0;
      border-radius: var(--radius-full);
      background: none;
      font-size: var(--text-label);
    }
    .appearance:hover {
      background: var(--surface-2);
    }
    main {
      padding: var(--space-5) var(--space-6);
    }
    /* The open photo detail is a panel at the right edge (PhotoDetail); the section makes room for it. */
    .shell:has(:global(.panel)) main {
      padding-right: calc(var(--detail-width) + var(--space-5));
    }
  }
</style>
