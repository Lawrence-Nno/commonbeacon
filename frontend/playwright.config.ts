import { defineConfig } from "@playwright/test";

if (process.env.COMMONBEACON_E2E !== "isolated")
  throw new Error(
    "Use npm run test:smoke to start the disposable browser-test stack.",
  );
export default defineConfig({
  workers: 1,
  testDir: "./e2e",
  testMatch: process.env.COMMONBEACON_E2E_GROUP === "erasure" ? "**/erasure.spec.ts" : process.env.COMMONBEACON_E2E_GROUP === "imports" ? "**/import-activation.spec.ts" : process.env.COMMONBEACON_E2E_GROUP === "discourse" ? "**/discourse-import.spec.ts" : undefined,
  testIgnore: process.env.COMMONBEACON_E2E_GROUP === "community" ? ["**/import-activation.spec.ts", "**/discourse-import.spec.ts", "**/erasure.spec.ts"] : undefined,
  fullyParallel: false,
  retries: 0,
  reporter: [["list"], ["html", { open: "never" }]],
  use: {
    browserName: "chromium",
    channel: "chrome",
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
  },
  webServer: [
    {
      command: "npm run dev -- --port 4174",
      url: "http://127.0.0.1:4174",
      reuseExistingServer: false,
      env: { BACKEND_URL: "http://127.0.0.1:1" },
    },
  ],
});
