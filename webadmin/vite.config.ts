import { svelte } from "@sveltejs/vite-plugin-svelte";
import { defineConfig } from "vitest/config";

// Für `npm run dev`: API-Aufrufe gehen an einen Rahmen oder an den :core-Testserver.
const frame = process.env.OPENFRAME_URL ?? "http://127.0.0.1:8090";

export default defineConfig({
  plugins: [svelte()],
  server: {
    proxy: {
      "/api": {
        target: frame,
        changeOrigin: true,
        // Der Rahmen lehnt ändernde Anfragen von fremden Origins ab.
        headers: { origin: frame },
      },
    },
  },
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
