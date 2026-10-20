import { svelte } from "@sveltejs/vite-plugin-svelte";
import { defineConfig } from "vitest/config";

// For `npm run dev`: API calls go to a frame or to the :core test server.
const frame = process.env.OPENFRAME_URL ?? "http://127.0.0.1:8090";

export default defineConfig({
  plugins: [svelte()],
  server: {
    proxy: {
      "/api": {
        target: frame,
        changeOrigin: true,
        // The frame rejects mutating requests from foreign origins.
        headers: { origin: frame },
      },
    },
  },
  // The web UI is served by the frame at "/", all assets are local.
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
