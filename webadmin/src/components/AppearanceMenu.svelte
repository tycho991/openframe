<script lang="ts">
  import type { Lang } from "../lib/i18n/detect";
  import { i18n, t, type Key } from "../lib/i18n/i18n.svelte";
  import { theme, type ThemeChoice } from "../lib/theme.svelte";
  import Icon from "./Icon.svelte";

  // Language and theme apply per browser (spec `web-admin`, "Theme choice"); the buttons that open this menu are in
  // the top bar and, from 1200 px, in the side navigation.
  const languages: { value: Lang | "auto"; label: string }[] = [
    { value: "auto", label: "" },
    { value: "de", label: "Deutsch" },
    { value: "en", label: "English" },
  ];
  const themes: { value: ThemeChoice; label: Key }[] = [
    { value: "auto", label: "theme.auto" },
    { value: "light", label: "theme.light" },
    { value: "dark", label: "theme.dark" },
  ];

  function chooseLang(value: Lang | "auto") {
    i18n.choose(value === "auto" ? null : value);
  }
</script>

<div id="appearance" class="menu" popover="auto" aria-label={t("appearance.open")}>
  <fieldset>
    <legend>{t("lang.label")}</legend>
    {#each languages as lang (lang.value)}
      <label class="option">
        <input
          type="radio"
          name="lang"
          value={lang.value}
          checked={(i18n.chosen ?? "auto") === lang.value}
          onchange={() => chooseLang(lang.value)}
        />
        <span>{lang.value === "auto" ? t("lang.auto") : lang.label}</span>
      </label>
    {/each}
  </fieldset>

  <fieldset>
    <legend>{t("appearance.theme")}</legend>
    {#each themes as choice (choice.value)}
      <label class="option">
        <input
          type="radio"
          name="theme"
          value={choice.value}
          checked={theme.choice === choice.value}
          onchange={() => theme.choose(choice.value)}
        />
        <span>{t(choice.label)}</span>
      </label>
    {/each}
  </fieldset>

  <section aria-labelledby="licences-heading">
    <h2 id="licences-heading">{t("appearance.licences")}</h2>
    <a class="option" href="/licenses/Inter-OFL.txt" target="_blank" rel="noopener">
      <span>{t("licence.inter")}</span>
      <Icon name="openInNew" size={20} />
    </a>
    <a class="option" href="/licenses/MaterialSymbols-LICENSE.txt" target="_blank" rel="noopener">
      <span>{t("licence.icons")}</span>
      <Icon name="openInNew" size={20} />
    </a>
    <a class="option" href="/licenses/third-party-android.txt" target="_blank" rel="noopener">
      <span>{t("licence.app")}</span>
      <Icon name="openInNew" size={20} />
    </a>
    <a class="option" href="/licenses/third-party-webadmin.txt" target="_blank" rel="noopener">
      <span>{t("licence.webadmin")}</span>
      <Icon name="openInNew" size={20} />
    </a>
    <a class="option" href="/licenses/GeoNames.txt" target="_blank" rel="noopener">
      <span>{t("licence.places")}</span>
      <Icon name="openInNew" size={20} />
    </a>
  </section>
</div>

<style>
  .menu {
    position: fixed;
    inset: auto;
    top: calc(var(--bar-height) + env(safe-area-inset-top));
    right: max(var(--space-3), env(safe-area-inset-right));
    margin: 0;
    width: min(340px, calc(100vw - 2 * var(--space-3)));
    max-height: calc(100dvh - var(--bar-height) - var(--space-5));
    overflow-y: auto;
    padding: var(--space-2) 0;
    border: 1px solid var(--divider);
    border-radius: var(--radius-m);
    background: var(--surface);
    color: var(--text);
    box-shadow: var(--shadow-1);
  }
  fieldset,
  section {
    margin: 0;
    padding: var(--space-2) 0;
    border: 0;
  }
  fieldset + fieldset,
  fieldset + section {
    border-top: 1px solid var(--divider);
  }
  legend,
  h2 {
    padding: var(--space-1) var(--space-4);
    margin: 0;
    font-size: var(--text-small);
    font-weight: 600;
    color: var(--muted);
  }
  legend {
    float: left;
    width: 100%;
  }
  .option {
    display: flex;
    align-items: center;
    gap: var(--space-3);
    min-height: var(--target);
    padding: 0 var(--space-4);
    color: var(--text);
    text-decoration: none;
    cursor: pointer;
  }
  .option:hover {
    background: var(--surface-2);
  }
  a.option span {
    flex: 1;
  }
  input[type="radio"] {
    width: 20px;
    height: 20px;
    margin: 0;
    accent-color: var(--accent);
  }
  @media (min-width: 1200px) {
    .menu {
      top: auto;
      right: auto;
      bottom: var(--space-4);
      left: calc(var(--side-nav-width) - var(--space-3));
    }
  }
</style>
