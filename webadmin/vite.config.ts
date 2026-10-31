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
    // Licences of the bundled libraries, served by the frame and linked from the licence menu. A .txt name, because
    // the frame's static file server has no type for .md and a .json name would switch Vite to JSON output.
    license: { fileName: "licenses/third-party-webadmin.txt" },
  },
  test: {
    include: ["src/**/*.test.ts"],
    environment: "node",
  },
});
