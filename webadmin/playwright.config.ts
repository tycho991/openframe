import { defineConfig, devices } from "@playwright/test";

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
    baseURL: "http://127.0.0.1:8090",
    trace: "retain-on-failure",
    locale: "de-DE",
  },
  webServer: {
    command: "cd .. && ./gradlew -q :core:runTestServer --args='8090 8091 webadmin/dist'",
    url: "http://127.0.0.1:8090/api/v1/info",
    reuseExistingServer: !process.env.CI,
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
