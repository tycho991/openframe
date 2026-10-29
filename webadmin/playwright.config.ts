import { defineConfig, devices } from "@playwright/test";
import { CONTROL_PORT, FRAME_ORIGIN, FRAME_PORT } from "./e2e/ports";

// Locally without downloaded browsers: path to an existing Chromium.
const chromiumPath = process.env.PW_CHROMIUM_PATH;

export default defineConfig({
  testDir: "e2e",
  // One shared test server with state: run one after another.
  workers: 1,
  fullyParallel: false,
  timeout: 90_000,
  expect: { timeout: 10_000 },
  reporter: process.env.CI ? [["list"], ["html", { open: "never" }]] : "list",
  use: {
    baseURL: FRAME_ORIGIN,
    trace: "retain-on-failure",
    locale: "de-DE",
  },
  webServer: {
    command: `cd .. && ./gradlew -q :core:runTestServer --args='${FRAME_PORT} ${CONTROL_PORT} webadmin/dist'`,
    url: `${FRAME_ORIGIN}/api/v1/info`,
    // A server already on the port may run another checkout's code, so it is taken over only on request
    // (PW_REUSE_SERVER=1); otherwise Playwright stops with "already used".
    reuseExistingServer: process.env.PW_REUSE_SERVER === "1",
    // Without it Playwright kills the server with SIGKILL, and the server cannot delete its data folder.
    gracefulShutdown: { signal: "SIGTERM", timeout: 5_000 },
    timeout: 300_000,
  },
  projects: [
    {
      name: "chromium",
      use: {
        ...devices["Desktop Chrome"],
        launchOptions: chromiumPath ? { executablePath: chromiumPath } : {},
      },
    },
    { name: "firefox", use: { ...devices["Desktop Firefox"] } },
    { name: "webkit", use: { ...devices["Desktop Safari"] } },
  ],
});
