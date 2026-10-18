import { svelte } from "@sveltejs/vite-plugin-svelte";
import { defineConfig } from "vitest/config";

export default defineConfig({
  plugins: [svelte()],
  // Die Web-UI wird vom Rahmen unter "/" ausgeliefert, alle Assets liegen lokal.
  base: "/",
  build: {
    outDir: "dist",
    emptyOutDir: true,
    target: "es2022",
    assetsInlineLimit: 0,
  },
  test: {
    include: ["src/**/*.test.ts"],
    environment: "node",
  },
});
