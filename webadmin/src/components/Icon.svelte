<script lang="ts">
  import { filledIcons, icons, type IconName } from "../lib/icons";

  // Decorative: the control around the icon carries the accessible name.
  let { name, filled = false, size = 24 }: { name: IconName; filled?: boolean; size?: number } = $props();
</script>

<svg
  class="icon"
  viewBox="0 -960 960 960"
  width={size}
  height={size}
  fill="currentColor"
  aria-hidden="true"
  focusable="false"><path d={(filled && filledIcons[name]) || icons[name]} /></svg
>

<style>
  .icon {
    flex: none;
    display: block;
  }
</style>
