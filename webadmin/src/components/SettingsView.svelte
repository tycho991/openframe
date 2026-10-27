<script lang="ts">
  import { ApiError, type DisplayState, type Settings } from "../lib/api/client";
  import { i18n, t, type Key } from "../lib/i18n/i18n.svelte";
  import { errorText } from "../lib/i18n/errors";
  import { api, refreshState, session } from "../lib/session.svelte";

  let saved = $state<Settings | null>(null);
  let form = $state<Settings | null>(null);
  let errors = $state<Record<string, string>>({});
  let status = $state<"idle" | "saving" | "saved">("idle");
  let error = $state<string | null>(null);
  let display = $state<DisplayState | null>(null);
  let switching = $state(false);

  const dirty = $derived(form !== null && saved !== null && JSON.stringify(form) !== JSON.stringify(saved));

  const ranges: Record<string, [number, number]> = {
    intervalSeconds: [5, 86400],
    transitionDurationMs: [0, 5000],
    "clock.sizePercent": [50, 300],
    cacheBudgetMb: [100, 10000],
    "displaySchedule.wakeMinutes": [1, 120],
  };

  /** The four corners, for the clock and for the caption. */
  const corners = ["top-left", "top-right", "bottom-left", "bottom-right"] as const;

  const scheduleTimes = [
    ["weekdays", "on"],
    ["weekdays", "off"],
    ["weekend", "on"],
    ["weekend", "off"],
  ] as const;

  /** How often the display state is read while the settings are open, so that changes on the frame show up. */
  const DISPLAY_POLL_MS = 30_000;

  async function loadDisplay() {
    try {
      display = await api.display();
    } catch {
      // Frame briefly unreachable: the next poll tries again.
    }
  }

  $effect(() => {
    void loadDisplay();
    const timer = setInterval(() => void loadDisplay(), DISPLAY_POLL_MS);
    return () => clearInterval(timer);
  });

  async function switchDisplay() {
    if (!display) return;
    switching = true;
    error = null;
    try {
      display = await api.setDisplay(!display.on);
    } catch (e) {
      error = errorText(e);
      void loadDisplay();
    } finally {
      switching = false;
    }
  }

  const displayText = $derived.by(() => {
    if (!display) return "";
    const key = {
      schedule: display.on ? "display.onSchedule" : "display.offSchedule",
      manual: display.on ? "display.onManual" : "display.offManual",
      wake: "display.onWake",
      none: display.on ? "display.on" : "display.off",
    }[display.cause] as Key;
    if (!display.nextChange) return t(key);
    const time = new Date(display.nextChange).toLocaleString(i18n.lang, {
      weekday: "short",
      hour: "2-digit",
      minute: "2-digit",
    });
    return t("display.until", { state: t(key), time });
  });

  async function load() {
    try {
      const s = await api.settings();
      saved = s;
      form = structuredClone(s);
    } catch (e) {
      error = errorText(e);
    }
  }

  // Changed elsewhere and nothing pending here: reload.
  $effect(() => {
    void session.settingsVersion;
    if (!dirty) void load();
  });

  /** Only the changed fields, `clock` and `displaySchedule` also partially. */
  function patch(from: object, to: object): Record<string, unknown> {
    const out: Record<string, unknown> = {};
    const before = from as Record<string, unknown>;
    for (const [key, value] of Object.entries(to)) {
      if (value !== null && typeof value === "object") {
        const inner = patch(before[key] as object, value as object);
        if (Object.keys(inner).length > 0) out[key] = inner;
      } else if (value !== before[key]) {
        out[key] = value;
      }
    }
    return out;
  }

  function fieldError(key: string): string | undefined {
    if (!errors[key]) return undefined;
    if (key === "displaySchedule.enabled") return t("display.notAvailable");
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
      void refreshState();
      void loadDisplay();
      status = "saved";
      setTimeout(() => {
        if (status === "saved") status = "idle";
      }, 3000);
    } catch (e) {
      status = "idle";
      if (e instanceof ApiError && Object.keys(e.fields).length > 0) errors = e.fields;
      else error = errorText(e);
    }
  }

  // The note is about the saved transition: while it is edited the frame will try again anyway.
  const showsInstantChange = $derived(
    session.transitionFallback &&
      !!form &&
      !!saved &&
      saved.transition !== "none" &&
      form.transition === saved.transition &&
      form.transitionDurationMs === saved.transitionDurationMs,
  );

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
        {#if showsInstantChange}<span class="hint">{t("settings.transitionFallback")}</span>{/if}
      </div>
      <div class="field">
        <label for="s-fill">{t("settings.fillMode")}</label>
        <select id="s-fill" bind:value={form.fillMode}>
          {#each ["auto", "fill", "fit"] as v (v)}<option value={v}>{option("settings.fillMode", v)}</option
            >{/each}
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
          {#each corners as v (v)}
            <option value={v}>{option("settings.corner", v)}</option>
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
      <legend>{t("settings.section.caption")}</legend>
      <p class="hint">{t("settings.captionHint")}</p>
      <label class="check">
        <input
          type="checkbox"
          bind:checked={form.caption.albumTitle}
          aria-invalid={!!errors["caption.albumTitle"]}
        />
        <span>{t("settings.captionAlbumTitle")}</span>
      </label>
      <div class="place">
        <label class="check">
          <input
            type="checkbox"
            bind:checked={form.caption.place}
            aria-invalid={!!errors["caption.place"]}
            aria-describedby="s-caption-attribution"
          />
          <span>{t("settings.captionPlace")}</span>
        </label>
        <!-- Shipped with the frame's place data; a plain link, nothing is loaded from there. -->
        <span class="hint" id="s-caption-attribution"
          >{t("settings.placeAttribution")} (<a
            href="https://www.geonames.org/"
            target="_blank"
            rel="noreferrer">geonames.org</a
          >)</span
        >
      </div>
      <div class="field">
        <label for="s-caption-pos">{t("settings.captionPosition")}</label>
        <select
          id="s-caption-pos"
          bind:value={form.caption.position}
          aria-invalid={!!errors["caption.position"]}
        >
          {#each corners as v (v)}
            <option value={v}>{option("settings.corner", v)}</option>
          {/each}
        </select>
        {#if fieldError("caption.position")}<span class="error" role="alert"
            >{fieldError("caption.position")}</span
          >{/if}
      </div>
    </fieldset>

    <fieldset class="card">
      <legend>{t("settings.section.display")}</legend>
      {#if display}
        <div class="display-state">
          <span aria-live="polite">{displayText}</span>
          <button type="button" onclick={switchDisplay} disabled={!display.available || switching}
            >{display.on ? t("display.turnOff") : t("display.turnOn")}</button
          >
        </div>
        {#if !display.available}<p class="hint">{t("display.notAvailable")}</p>{/if}
      {/if}
      <label class="check">
        <input
          type="checkbox"
          bind:checked={form.displaySchedule.enabled}
          disabled={!display?.available && !form.displaySchedule.enabled}
          aria-invalid={!!errors["displaySchedule.enabled"]}
        />
        <span>{t("settings.displaySchedule")}</span>
      </label>
      {#if fieldError("displaySchedule.enabled")}<span class="error" role="alert"
          >{fieldError("displaySchedule.enabled")}</span
        >{/if}
      <div class="times">
        {#each scheduleTimes as [day, edge] (day + edge)}
          {@const path = `displaySchedule.${day}.${edge}`}
          <div class="field">
            <label for="s-{day}-{edge}">{option("settings.display", day + "." + edge)}</label>
            <input
              id="s-{day}-{edge}"
              type="time"
              required
              bind:value={form.displaySchedule[day][edge]}
              aria-invalid={!!errors[path]}
            />
            {#if fieldError(path)}<span class="error" role="alert">{fieldError(path)}</span>{/if}
          </div>
        {/each}
      </div>
      <span class="hint">{t("settings.displayTimesHint")}</span>
      <div class="field">
        <label for="s-wake">{t("settings.wakeMinutes")}</label>
        <input
          id="s-wake"
          type="number"
          inputmode="numeric"
          bind:value={form.displaySchedule.wakeMinutes}
          aria-invalid={!!errors["displaySchedule.wakeMinutes"]}
          aria-describedby="s-wake-hint"
        />
        <span class="hint" id="s-wake-hint">{t("settings.wakeMinutesHint")}</span>
        {#if fieldError("displaySchedule.wakeMinutes")}<span class="error" role="alert"
            >{fieldError("displaySchedule.wakeMinutes")}</span
          >{/if}
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
  /* A link in running text: taller hit area without changing the line (spec web-admin, "Touch targets"). */
  .hint a {
    padding-block: 14px;
  }
  .actions {
    position: sticky;
    bottom: var(--content-bottom, 0px);
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
    font-size: var(--text-small);
  }
  .display-state {
    display: flex;
    flex-wrap: wrap;
    gap: 12px;
    justify-content: space-between;
    align-items: center;
    font-weight: 600;
  }
  .place {
    display: grid;
    gap: 4px;
  }
  .times {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
    gap: 14px;
  }
</style>
