<script lang="ts">
  import { ApiError, type DisplayState, type Settings, type UpdateState } from "../lib/api/client";
  import { i18n, t, tOr, type Key } from "../lib/i18n/i18n.svelte";
  import { errorText } from "../lib/i18n/errors";
  import { api, refreshState, session } from "../lib/session.svelte";
  import { markUnsaved, updatePollMs } from "../lib/update";

  let saved = $state<Settings | null>(null);
  let form = $state<Settings | null>(null);
  let errors = $state<Record<string, string>>({});
  let status = $state<"idle" | "saving" | "saved">("idle");
  let error = $state<string | null>(null);
  let display = $state<DisplayState | null>(null);
  let switching = $state(false);

  const dirty = $derived(form !== null && saved !== null && JSON.stringify(form) !== JSON.stringify(saved));

  // A reload after a frame update would lose changes that are not saved.
  $effect(() => {
    markUnsaved("settings", dirty);
    return () => markUnsaved("settings", false);
  });

  const ranges: Record<string, [number, number]> = {
    intervalSeconds: [5, 86400],
    transitionDurationMs: [0, 5000],
    "clock.sizePercent": [50, 300],
    cacheBudgetMb: [100, 10000],
    "displaySchedule.wakeMinutes": [1, 120],
    "displaySchedule.dimLevel": [1, 100],
    "brightness.level": [1, 100],
  };

  /** The four corners, for the clock and for the caption. */
  const corners = ["top-left", "top-right", "bottom-left", "bottom-right"] as const;

  const scheduleTimes = [
    ["weekdays", "on"],
    ["weekdays", "off"],
    ["weekend", "on"],
    ["weekend", "off"],
  ] as const;

  const windows = ["weekdays", "weekend"] as const;

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

  // The brightness (spec web-admin, "Brightness in the settings", "Display state and manual switch").
  const brightnessAvailable = $derived(display?.brightness.available ?? false);
  const autoSupported = $derived(display?.brightness.autoSupported ?? false);
  /** The slider's value while it is dragged, `null` otherwise. */
  let dragged = $state<number | null>(null);
  let settingBrightness = $state(false);

  /** Sets the brightness when the slider is let go, so that a drag is one request. */
  async function setBrightness(level: number) {
    settingBrightness = true;
    error = null;
    try {
      display = await api.setBrightness(level);
      // Outside a dim period the frame stores the level as its fixed brightness.
      void refreshState();
    } catch (e) {
      error = errorText(e);
      void loadDisplay();
    } finally {
      dragged = null;
      settingBrightness = false;
    }
  }

  /** The dim fields carry the brightness notice while the frame may not change its brightness. */
  const dimHint = $derived(
    display && !brightnessAvailable
      ? `${t("settings.dimHint")} ${t("brightness.notAvailable")}`
      : t("settings.dimHint"),
  );

  const displayText = $derived.by(() => {
    if (!display) return "";
    const key = (
      {
        schedule: display.on ? "display.onSchedule" : "display.offSchedule",
        manual: display.on ? "display.onManual" : "display.offManual",
        wake: "display.onWake",
        none: display.on ? "display.on" : "display.off",
      } as Record<string, Key>
    )[display.cause];
    // A cause from a newer frame shows as itself.
    const state = key ? t(key) : display.cause;
    if (!display.nextChange) return state;
    const time = new Date(display.nextChange).toLocaleString(i18n.lang, {
      weekday: "short",
      hour: "2-digit",
      minute: "2-digit",
    });
    return t("display.until", { state, time });
  });

  // Software (spec web-admin, "Software update in the interface"; design decision 12 of app-auto-update).
  let update = $state<UpdateState | null>(null);
  let updateError = $state<string | null>(null);
  let installRequested = $state(false);
  let updateBusy = $state(false);
  /** The frame stopped answering while its installation dialog was open: it restarts with the new version. */
  let restarting = $state(false);

  async function loadUpdate() {
    try {
      const next = await api.update();
      if (next.phase !== "installing") installRequested = false;
      update = next;
      restarting = false;
    } catch (e) {
      if (update?.phase === "installing" && !(e instanceof ApiError)) restarting = true;
    }
  }

  $effect(() => {
    const ms = updatePollMs(update?.phase);
    const timer = setTimeout(() => void loadUpdate(), ms);
    return () => clearTimeout(timer);
  });
  $effect(() => {
    void loadUpdate();
  });

  async function checkUpdate() {
    updateBusy = true;
    updateError = null;
    try {
      update = await api.checkUpdate();
    } catch (e) {
      updateError = errorText(e);
    } finally {
      updateBusy = false;
    }
  }

  async function installUpdate() {
    updateBusy = true;
    updateError = null;
    try {
      update = await api.installUpdate();
      installRequested = true;
    } catch (e) {
      updateError = errorText(e);
      void loadUpdate();
    } finally {
      updateBusy = false;
    }
  }

  function when(iso: string | null | undefined): string {
    if (!iso) return "";
    return new Date(iso).toLocaleString(i18n.lang, { dateStyle: "medium", timeStyle: "short" });
  }

  const phaseText = $derived.by(() => {
    if (!update) return "";
    if (restarting) return t("update.restarting");
    return tOr(`update.phase.${update.phase}`, update.phase, { version: update.latest?.version ?? "" });
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

  /** The text of an enum value; a value from a newer frame shows as itself. */
  function option(prefix: string, value: string) {
    return tOr(`${prefix}.${value}`, value);
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
        <!-- Shipped with the frame's place data; plain links, nothing is loaded from there. The full source note
             is in the licence menu. -->
        <span class="hint" id="s-caption-attribution"
          >{t("settings.placeAttribution")} (<a
            href="https://www.geonames.org/"
            target="_blank"
            rel="noreferrer">geonames.org</a
          >),
          <a href="https://creativecommons.org/licenses/by/4.0/" target="_blank" rel="noreferrer">CC BY 4.0</a
          >;
          {t("settings.placeChanges")}</span
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
        <div class="field">
          <label for="s-brightness-now">{t("display.brightness")}</label>
          <div class="brightness-now">
            <input
              id="s-brightness-now"
              type="range"
              min="1"
              max="100"
              value={dragged ?? display.brightness.level}
              disabled={!display.brightness.available || settingBrightness}
              aria-describedby="s-brightness-now-state"
              oninput={(e) => (dragged = e.currentTarget.valueAsNumber)}
              onchange={(e) => void setBrightness(e.currentTarget.valueAsNumber)}
            />
            <span id="s-brightness-now-state" aria-live="polite"
              >{dragged ?? display.brightness.level} %{display.brightness.dimmed
                ? ` · ${t("display.dimmed")}`
                : ""}</span
            >
          </div>
        </div>
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
      <div class="times">
        {#each windows as day (day)}
          {@const path = `displaySchedule.${day}.dim`}
          <div class="field">
            <label for="s-{day}-dim">{option("settings.display", day + ".dim")}</label>
            <!-- Empty for no dimming: the frame stores `null`. -->
            <input
              id="s-{day}-dim"
              type="time"
              bind:value={
                () => form!.displaySchedule[day].dim ?? "",
                (v) => (form!.displaySchedule[day].dim = v || null)
              }
              aria-invalid={!!errors[path]}
              aria-describedby="s-dim-hint"
            />
            {#if fieldError(path)}<span class="error" role="alert">{fieldError(path)}</span>{/if}
          </div>
        {/each}
      </div>
      <div class="field">
        <label for="s-dim-level">{t("settings.dimLevel")}</label>
        <input
          id="s-dim-level"
          type="number"
          inputmode="numeric"
          bind:value={form.displaySchedule.dimLevel}
          aria-invalid={!!errors["displaySchedule.dimLevel"]}
          aria-describedby="s-dim-hint"
        />
        {#if fieldError("displaySchedule.dimLevel")}<span class="error" role="alert"
            >{fieldError("displaySchedule.dimLevel")}</span
          >{/if}
      </div>
      <span class="hint" id="s-dim-hint">{dimHint}</span>
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
      <legend>{t("settings.section.brightness")}</legend>
      <div class="field">
        <label for="s-brightness-mode">{t("settings.brightnessMode")}</label>
        <select
          id="s-brightness-mode"
          bind:value={form.brightness.mode}
          aria-invalid={!!errors["brightness.mode"]}
          aria-describedby="s-brightness-hint"
        >
          <option value="system">{option("settings.brightnessMode", "system")}</option>
          <option value="manual" disabled={!brightnessAvailable}
            >{option("settings.brightnessMode", "manual")}</option
          >
          {#if autoSupported || saved?.brightness.mode === "auto"}
            <option value="auto" disabled={!brightnessAvailable || !autoSupported}
              >{option("settings.brightnessMode", "auto")}</option
            >
          {/if}
        </select>
        {#if fieldError("brightness.mode")}<span class="error" role="alert"
            >{fieldError("brightness.mode")}</span
          >{/if}
      </div>
      <div class="field">
        <label for="s-brightness-level">{t("settings.brightnessLevel")}</label>
        <input
          id="s-brightness-level"
          type="number"
          inputmode="numeric"
          bind:value={form.brightness.level}
          disabled={form.brightness.mode !== "manual"}
          aria-invalid={!!errors["brightness.level"]}
        />
        {#if fieldError("brightness.level")}<span class="error" role="alert"
            >{fieldError("brightness.level")}</span
          >{/if}
      </div>
      <span class="hint" id="s-brightness-hint">{t("settings.brightnessHint")}</span>
      {#if display && !brightnessAvailable}<p class="hint">{t("brightness.notAvailable")}</p>{/if}
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

    <fieldset class="card">
      <legend>{t("settings.section.software")}</legend>
      {#if update}
        <dl class="versions">
          <dt>{t("update.installed")}</dt>
          <dd>{update.installedVersion}</dd>
          {#if update.latest}
            <dt>{t("update.latest")}</dt>
            <dd>
              {update.latest.publishedAt
                ? t("update.published", {
                    version: update.latest.version,
                    date: when(update.latest.publishedAt),
                  })
                : update.latest.version}
            </dd>
          {/if}
        </dl>
        <p class="phase" aria-live="polite">{phaseText}</p>
        {#if update.phase !== "unsupported"}
          <p class="hint">
            {update.lastCheck
              ? t("update.lastCheck", {
                  time: when(update.lastCheck.at),
                  result: tOr(`update.result.${update.lastCheck.result}`, update.lastCheck.result),
                })
              : t("update.neverChecked")}
          </p>
          {#if update.error && update.phase !== "installing" && !restarting}
            <p class="error">{tOr(`update.error.${update.error.code}`, update.error.code)}</p>
          {/if}
          {#if update.latest?.notes}
            <!-- Plain text, never HTML or Markdown (spec web-admin, "Release notes as plain text"). -->
            <div class="notes">
              <span class="notes-title">{t("update.notes")}</span>
              <p class="notes-text">{update.latest.notes}</p>
            </div>
          {/if}
          <div class="update-actions">
            <button
              type="button"
              onclick={checkUpdate}
              disabled={updateBusy || update.phase === "checking" || update.phase === "downloading"}
              >{t("update.check")}</button
            >
            {#if update.phase === "ready"}
              <button
                type="button"
                class="primary"
                onclick={installUpdate}
                disabled={updateBusy || !update.installAllowed}
                aria-describedby="s-install-hint">{t("update.install")}</button
              >
            {/if}
          </div>
          {#if update.phase === "ready"}
            <p class="hint" id="s-install-hint">
              {update.installAllowed ? t("update.installHint") : t("update.notAllowed")}
            </p>
          {/if}
          {#if installRequested && update.phase === "installing"}<p role="status">
              {t("update.requested")}
            </p>{/if}
          {#if updateError}<p class="error" role="alert">{updateError}</p>{/if}
        {/if}
      {/if}
      <label class="check">
        <input
          type="checkbox"
          bind:checked={form.autoUpdateCheck}
          aria-invalid={!!errors.autoUpdateCheck}
          aria-describedby="s-auto-update-hint"
        />
        <span>{t("settings.autoUpdateCheck")}</span>
      </label>
      <span class="hint" id="s-auto-update-hint">{t("settings.autoUpdateCheckHint")}</span>
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
  .brightness-now {
    display: flex;
    align-items: center;
    gap: 12px;
  }

  .brightness-now input {
    flex: 1;
    min-width: 0;
    /* A touch target of 44 px (spec web-admin, "Touch targets"); the track stays centred. */
    height: 44px;
    margin: 0;
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
  .versions {
    display: grid;
    grid-template-columns: max-content 1fr;
    gap: 4px 16px;
    margin: 0;
  }
  .versions dd {
    margin: 0;
    font-weight: 600;
  }
  .phase {
    margin: 0;
    font-weight: 600;
  }
  .notes {
    display: grid;
    gap: 4px;
  }
  .notes-title {
    font-size: var(--text-small);
    font-weight: 600;
  }
  .notes-text {
    margin: 0;
    white-space: pre-line;
    overflow-wrap: anywhere;
  }
  .update-actions {
    display: flex;
    flex-wrap: wrap;
    gap: 12px;
  }
  .times {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
    gap: 14px;
  }
</style>
