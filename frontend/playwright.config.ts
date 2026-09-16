import { defineConfig } from "@playwright/test";

if (process.env.COMMONBEACON_E2E !== "isolated")
  throw new Error(
    "Use npm run test:smoke to start the disposable browser-test stack.",
  );
export default defineConfig({
  workers: 1,
  testDir: "./e2e",
  fullyParallel: false,
  retries: 0,
  reporter: "list",
  use: {
    browserName: "chromium",
    channel: "chrome",
    trace: "retain-on-failure",
  },
  webServer: [
    {
      command: "npm run dev -- --port 4173",
      url: "http://127.0.0.1:4173",
      reuseExistingServer: false,
      env: { BACKEND_URL: "http://127.0.0.1:4180" },
    },
    {
      command: "npm run dev -- --port 4174",
      url: "http://127.0.0.1:4174",
      reuseExistingServer: false,
      env: { BACKEND_URL: "http://127.0.0.1:1" },
    },
  ],
});
