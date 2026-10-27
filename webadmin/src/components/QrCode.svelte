<script lang="ts">
  /** Rows of the QR code from the API, `1` for a dark module; drawn dark on light with a quiet zone. */
  let { rows, label }: { rows: string[]; label: string } = $props();

  const QUIET = 4;

  // Adjacent dark modules of a row become one rect.
  const runs = $derived(
    rows.flatMap((row, y) => {
      const out: { x: number; y: number; w: number }[] = [];
      let start = -1;
      for (let x = 0; x <= row.length; x++) {
        const dark = row[x] === "1";
        if (dark && start < 0) start = x;
        if (!dark && start >= 0) {
          out.push({ x: start + QUIET, y: y + QUIET, w: x - start });
          start = -1;
        }
      }
      return out;
    }),
  );
  const side = $derived(rows.length + 2 * QUIET);
</script>

<svg
  class="qr"
  viewBox={`0 0 ${side} ${side}`}
  role="img"
  aria-label={label}
  shape-rendering="crispEdges"
  xmlns="http://www.w3.org/2000/svg"
>
  <!-- Dark on light in both themes: scanners expect it. -->
  <rect class="light" width={side} height={side} />
  <g class="dark">
    {#each runs as run (run.y * side + run.x)}
      <rect x={run.x} y={run.y} width={run.w} height="1" />
    {/each}
  </g>
</svg>

<style>
  .qr {
    display: block;
    width: 100%;
    max-width: 280px;
    height: auto;
    aspect-ratio: 1;
    border-radius: var(--radius-s);
  }
  .light {
    fill: var(--qr-light);
  }
  .dark {
    fill: var(--qr-dark);
  }
</style>
