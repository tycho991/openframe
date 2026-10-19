<script lang="ts">
  import { ApiError, type Settings } from "../lib/api/client";
  import { t, type Key } from "../lib/i18n/i18n.svelte";
  import { api, session } from "../lib/session.svelte";

  let saved = $state<Settings | null>(null);
  let form = $state<Settings | null>(null);
  let errors = $state<Record<string, string>>({});
  let status = $state<"idle" | "saving" | "saved">("idle");
  let error = $state<string | null>(null);

  const dirty = $derived(form !== null && saved !== null && JSON.stringify(form) !== JSON.stringify(saved));

  const ranges: Record<string, [number, number]> = {
    intervalSeconds: [5, 86400],
    transitionDurationMs: [0, 5000],
    "clock.sizePercent": [50, 300],
    cacheBudgetMb: [100, 10000],
  };

  async function load() {
    try {
      const s = await api.settings();
      saved = s;
      form = structuredClone(s);
    } catch (e) {
      error = t("common.error", { message: e instanceof Error ? e.message : String(e) });
    }
  }

  // Changed elsewhere and nothing pending here: reload.
  $effect(() => {
    void session.settingsVersion;
    if (!dirty) void load();
  });

  /** Only the changed fields, `clock` also partially. */
  function patch(from: Settings, to: Settings) {
    const out: Record<string, unknown> = {};
    for (const key of Object.keys(to) as (keyof Settings)[]) {
      if (key === "clock") {
        const clock: Record<string, unknown> = {};
        for (const k of Object.keys(to.clock) as (keyof Settings["clock"])[]) {
          if (to.clock[k] !== from.clock[k]) clock[k] = to.clock[k];
        }
        if (Object.keys(clock).length > 0) out.clock = clock;
      } else if (to[key] !== from[key]) {
        out[key] = to[key];
      }
    }
    return out;
  }

  function fieldError(key: string): string | undefined {
    if (!errors[key]) return undefined;
    const range = ranges[key];
    return range ? t("settings.range", { min: range[0], max: range[1] }) : t("settings.invalid");
  }

  async function submit(event: SubmitEvent) {
    event.preventDefault();
    if (!form || !saved) return;
    status = "saving";
    errors = {};
    error = null;
    try {
      const s = await api.patchSettings(patch(saved, form));
      saved = s;
      form = structuredClone(s);
      status = "saved";
      setTimeout(() => {
        if (status === "saved") status = "idle";
      }, 3000);
    } catch (e) {
      status = "idle";
      if (e instanceof ApiError && Object.keys(e.fields).length > 0) errors = e.fields;
      else error = t("common.error", { message: e instanceof Error ? e.message : String(e) });
    }
  }

  function option(prefix: string, value: string) {
    return t(`${prefix}.${value}` as Key);
  }
</script>

<h1>{t("nav.settings")}</h1>
{#if error}<p class="error" role="alert">{error}</p>{/if}

{#if form}
  <form onsubmit={submit} novalidate>
    <fieldset class="card">
      <legend>{t("settings.section.slideshow")}</legend>
      <div class="field">
        <label for="s-interval">{t("settings.interval")}</label>
        <input
          id="s-interval"
          type="number"
          inputmode="numeric"
          bind:value={form.intervalSeconds}
          aria-invalid={!!errors.intervalSeconds}
          aria-describedby="s-interval-hint"
        />
        <span class="hint" id="s-interval-hint">{t("settings.intervalHint")}</span>
        {#if fieldError("intervalSeconds")}<span class="error" role="alert"
            >{fieldError("intervalSeconds")}</span
          >{/if}
      </div>
      <div class="field">
        <label for="s-order">{t("settings.order")}</label>
        <select id="s-order" bind:value={form.order}>
          {#each ["shuffle", "chronological"] as v (v)}<option value={v}>{option("settings.order", v)}</option
            >{/each}
        </select>
      </div>
      <div class="field">
        <label for="s-transition">{t("settings.transition")}</label>
        <select id="s-transition" bind:value={form.transition}>
          {#each ["fade", "slide", "none"] as v (v)}<option value={v}
              >{option("settings.transition", v)}</option
            >{/each}
        </select>
      </div>
      <div class="field">
        <label for="s-duration">{t("settings.transitionDuration")}</label>
        <input
          id="s-duration"
          type="number"
          bind:value={form.transitionDurationMs}
          aria-invalid={!!errors.transitionDurationMs}
        />
        {#if fieldError("transitionDurationMs")}<span class="error" role="alert"
            >{fieldError("transitionDurationMs")}</span
          >{/if}
      </div>
      <div class="field">
        <label for="s-fill">{t("settings.fillMode")}</label>
        <select id="s-fill" bind:value={form.fillMode}>
          {#each ["fill", "fit"] as v (v)}<option value={v}>{option("settings.fillMode", v)}</option>{/each}
        </select>
      </div>
      <div class="field">
        <label for="s-orientation">{t("settings.orientation")}</label>
        <select id="s-orientation" bind:value={form.orientation}>
          {#each ["landscape", "portrait"] as v (v)}<option value={v}
              >{option("settings.orientation", v)}</option
            >{/each}
        </select>
      </div>
    </fieldset>

    <fieldset class="card">
      <legend>{t("settings.section.clock")}</legend>
      <label class="check">
        <input type="checkbox" bind:checked={form.clock.enabled} />
        <span>{t("settings.clockEnabled")}</span>
      </label>
      <div class="field">
        <label for="s-clock-pos">{t("settings.clockPosition")}</label>
        <select id="s-clock-pos" bind:value={form.clock.position} disabled={!form.clock.enabled}>
          {#each ["top-left", "top-right", "bottom-left", "bottom-right"] as v (v)}
            <option value={v}>{option("settings.clockPosition", v)}</option>
          {/each}
        </select>
      </div>
      <div class="field">
        <label for="s-clock-size">{t("settings.clockSize")}</label>
        <input
          id="s-clock-size"
          type="number"
          bind:value={form.clock.sizePercent}
          disabled={!form.clock.enabled}
          aria-invalid={!!errors["clock.sizePercent"]}
        />
        {#if fieldError("clock.sizePercent")}<span class="error" role="alert"
            >{fieldError("clock.sizePercent")}</span
          >{/if}
      </div>
      <div class="field">
        <label for="s-clock-format">{t("settings.clockFormat")}</label>
        <select id="s-clock-format" bind:value={form.clock.format} disabled={!form.clock.enabled}>
          {#each ["24h", "12h"] as v (v)}<option value={v}>{option("settings.clockFormat", v)}</option>{/each}
        </select>
      </div>
    </fieldset>

    <fieldset class="card">
      <legend>{t("settings.section.frame")}</legend>
      <div class="field">
        <label for="s-name">{t("settings.name")}</label>
        <input id="s-name" type="text" maxlength="64" bind:value={form.name} aria-invalid={!!errors.name} />
        {#if fieldError("name")}<span class="error" role="alert">{fieldError("name")}</span>{/if}
      </div>
      <div class="field">
        <label for="s-language">{t("settings.language")}</label>
        <select id="s-language" bind:value={form.language}>
          {#each ["auto", "de", "en"] as v (v)}<option value={v}>{option("settings.language", v)}</option
            >{/each}
        </select>
      </div>
      <div class="field">
        <label for="s-cache">{t("settings.cacheBudget")}</label>
        <input
          id="s-cache"
          type="number"
          bind:value={form.cacheBudgetMb}
          aria-invalid={!!errors.cacheBudgetMb}
        />
        <span class="hint">{t("settings.cacheBudgetHint")}</span>
        {#if fieldError("cacheBudgetMb")}<span class="error" role="alert">{fieldError("cacheBudgetMb")}</span
          >{/if}
      </div>
      <p class="hint muted">{t("settings.localOnlyHint")}</p>
    </fieldset>

    <div class="actions">
      {#if status === "saved"}<span role="status" class="saved">{t("common.saved")}</span>{/if}
      <button type="submit" class="primary" disabled={!dirty || status === "saving"}
        >{t("common.save")}</button
      >
    </div>
  </form>
{:else if !error}
  <p class="muted">{t("common.loading")}</p>
{/if}

<style>
  form {
    display: grid;
    gap: 16px;
    max-width: 720px;
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
    position: sticky;
    bottom: 0;
    display: flex;
    gap: 12px;
    justify-content: flex-end;
    align-items: center;
    padding: 12px 0;
    background: linear-gradient(transparent, var(--bg) 30%);
  }
  .saved {
    color: var(--accent);
    font-weight: 600;
  }
  .hint {
    margin: 0;
    font-size: 0.85rem;
  }
</style>
