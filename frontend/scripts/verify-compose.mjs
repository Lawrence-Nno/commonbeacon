import { chromium } from "@playwright/test";
import { mkdirSync } from "node:fs";
import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { execFileSync } from "node:child_process";
import { fileURLToPath } from "node:url";

if (!process.argv.includes("--allow-restart")) throw new Error("Pass --allow-restart: this check restarts the local Compose stack and signs users out.");
const root = fileURLToPath(new URL("../../", import.meta.url));
const origin = process.env.COMPOSE_ORIGIN ?? "http://127.0.0.1:8081";
assert(process.env.DEMO_PASSWORD, "Load use-local-database.ps1 and enable demo seeding first.");
const docker = (...args) => execFileSync("docker", ["compose", ...args], { cwd: root, stdio: "inherit", timeout: 240000 });
const browser = await chromium.launch({ channel: "chrome" });
const contexts = [];
async function login(email) {
  const context = await browser.newContext();
  contexts.push(context);
  const page = await context.newPage();
  await page.goto(origin + "/login");
  await page.getByLabel("Email address").fill(email);
  await page.getByLabel("Password", { exact: true }).fill(process.env.DEMO_PASSWORD);
  await page.getByRole("button", { name: "Sign in", exact: true }).click();
  await page.getByRole("button", { name: "Sign out" }).waitFor();
  const cookies = await context.cookies();
  assert(cookies.some(c => c.name === "JSESSIONID" && c.httpOnly && c.sameSite === "Lax"));
  return page;
}
async function mutate(page, path, data, method = "POST") {
  const csrf = await (await page.request.get(origin + "/api/v1/auth/csrf")).json();
  return page.request.fetch(origin + path, { method, data, headers: { [csrf.headerName]: csrf.token } });
}
try {
  const admin = await login("avery.admin@example.test");
  const owner = await login("alex.member@example.test");
  const other = await login("sam.member@example.test");
  const boardResponse = await mutate(admin, "/api/v1/boards", { name: "Compose verification", slug: "compose-" + randomUUID(), description: "Fictional persistence verification board." });
  assert.equal(boardResponse.status(), 201);
  const board = await boardResponse.json();
  const questionResponse = await mutate(owner, "/api/v1/boards/" + board.id + "/questions", { title: "Does the container retain my question?", body: "This question checks container persistence." });
  assert.equal(questionResponse.status(), 201);
  const question = await questionResponse.json();
  const replyResponse = await mutate(other, "/api/v1/questions/" + question.id + "/replies", { body: "The named volume retains the conversation." });
  assert.equal(replyResponse.status(), 201);
  const reply = await replyResponse.json();
  const endpoint = "/api/v1/questions/" + question.id + "/accepted-reply";
  assert.equal((await owner.request.put(origin + endpoint, { data: { replyId: reply.id, expectedVersion: 0 } })).status(), 403);
  assert.equal((await mutate(owner, endpoint, { replyId: reply.id, expectedVersion: 0 }, "PUT")).status(), 200);
  async function verifyPersisted() {
    const response = await owner.request.get(origin + "/api/v1/questions/" + question.id);
    assert.equal(response.status(), 200);
    const saved = await response.json();
    assert.equal(saved.body, question.body);
    assert.equal(saved.acceptedReply.id, reply.id);
    assert.equal(saved.acceptedReply.body, reply.body);
    assert.equal(saved.solved, true);
    await owner.goto(origin + "/questions/" + question.id);
    await owner.reload();
    await owner.getByRole("heading", { name: "Accepted answer" }).waitFor();
    assert.equal(await owner.locator(".accepted-answer .reply-body").textContent(), reply.body);
    assert.equal((await owner.request.get(origin + "/api/health")).status(), 200);
  }
  await verifyPersisted();
  docker("restart", "db", "backend");
  docker("up", "-d", "--wait", "--wait-timeout", "180");
  assert.equal((await owner.request.get(origin + "/api/v1/auth/me")).status(), 401);
  await verifyPersisted();
  docker("down"); // Deliberately no --volumes: ordinary shutdown must preserve data.
  docker("up", "-d", "--wait", "--wait-timeout", "180");
  await verifyPersisted();
  await owner.setViewportSize({ width: 390, height: 844 });
  assert(await owner.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
  mkdirSync("test-results", { recursive: true });
  await owner.screenshot({ path: "test-results/compose-mobile.png", fullPage: true });
  console.log("PASS: proxy, CSRF, cookies, deep links, backend/database restart, logout on restart, and volume persistence after down/up.");
} finally {
  await Promise.all(contexts.map(context => context.close()));
  await browser.close();
}
