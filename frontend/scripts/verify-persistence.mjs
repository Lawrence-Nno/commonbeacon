import { request } from "@playwright/test";
import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { randomUUID } from "node:crypto";
import { mkdirSync, writeFileSync } from "node:fs";
import { fileURLToPath } from "node:url";

const root = fileURLToPath(new URL("../../", import.meta.url));
const project = process.env.COMMONBEACON_PERSISTENCE_PROJECT ?? "commonbeacon-persistence-" + randomUUID().slice(0, 8);
assert.match(project, /^commonbeacon-persistence-[a-z0-9-]+$/);
const origin = "http://127.0.0.1:4175";
const composeArgs = ["compose", "--project-name", project, "--env-file", ".env.example", "-f", "compose.persistence.yaml"];
const run = (args, capture = false) => spawnSync("docker", args, { cwd: root, stdio: capture ? "pipe" : "inherit", encoding: "utf8", timeout: 600000 });
function compose(args, capture = false) {
  const result = run([...composeArgs, ...args], capture);
  assert.equal(result.status, 0, "Isolated Compose operation failed: " + args[0]);
  return result.stdout;
}
function assertClean() {
  for (const type of ["container", "network", "volume"]) {
    const result = run([type, "ls", ...(type === "container" ? ["-a"] : []), "-q", "--filter", "label=com.docker.compose.project=" + project], true);
    assert.equal(result.status, 0, "Cannot inspect isolated resources");
    assert.equal(result.stdout.trim(), "", "Isolated project still owns " + type + " resources");
  }
}
// Never adopt or clean up an already-existing project, even with a valid prefix.
assertClean();
if (process.argv.includes("--verify-failure-cleanup")) {
  const result = spawnSync(process.execPath, [fileURLToPath(import.meta.url), "--inject-failure"], {
    cwd: root, env: { ...process.env, COMMONBEACON_PERSISTENCE_PROJECT: project }, encoding: "utf8", timeout: 600000,
  });
  assert.equal(result.status, 1, "Injected failure must return nonzero");
  assert.match(result.stderr ?? "", /Injected persistence failure/);
  assertClean();
  console.log("Expected failure returned nonzero and removed only its disposable containers, network, and volume.");
} else {
  const contexts = [];
  async function session(email) {
    const context = await request.newContext({ baseURL: origin }); contexts.push(context);
    const csrf = await (await context.get("/api/v1/auth/csrf")).json();
    const response = await context.post("/api/v1/auth/login", { form: { email, password: "disposable-e2e-demo-password" }, headers: { [csrf.headerName]: csrf.token } });
    assert.equal(response.status(), 200, "Test account login failed"); return context;
  }
  async function get(context, path) {
    const response = await context.get(path); assert.equal(response.status(), 200, "Read failed: " + path); return response.json();
  }
  async function mutate(context, path, data, expected = 200, method = "POST") {
    const csrf = await get(context, "/api/v1/auth/csrf");
    const response = await context.fetch(path, { method, data, headers: { [csrf.headerName]: csrf.token } });
    assert.equal(response.status(), expected, "Mutation failed: " + path); return response.json();
  }
  function fingerprints() {
    const sql = ["app_user", "board", "question", "reply", "content_report", "moderation_action", "knowledge_article"].map(table =>
      `SELECT '${table}',md5(COALESCE(jsonb_agg(to_jsonb(t) ORDER BY t.id)::text,'[]')) FROM ${table} t`).join(";");
    return compose(["exec", "-T", "db", "psql", "-U", "persistence", "-d", "commonbeacon_persistence", "-At", "-v", "ON_ERROR_STOP=1", "-c", sql], true).trim();
  }
  try {
    compose(["up", "-d", "--build", "--wait", "--wait-timeout", "180"]);
    let admin = await session("avery.admin@example.test");
    const member = await session("alex.member@example.test");
    const visitor = await request.newContext({ baseURL: origin }); contexts.push(visitor);
    const marker = "Storage" + randomUUID().replaceAll("-", "");
    const board = await mutate(admin, "/api/v1/boards", { slug: "storage-" + randomUUID(), name: "Storage verification", description: "Isolated persistence verification." }, 201);
    const question = await mutate(member, `/api/v1/boards/${board.id}/questions`, { title: "Does saved moderation survive a restart?", body: "Check the stored state across service restarts." }, 201);
    const reply = await mutate(admin, `/api/v1/questions/${question.id}/replies`, { body: "An answer to exercise accepted-reply moderation." }, 201);
    await mutate(member, `/api/v1/questions/${question.id}/accepted-reply`, { replyId: reply.id, expectedVersion: question.version }, 200, "PUT");
    const report = await mutate(member, "/api/v1/reports", { replyId: reply.id, reason: "Review this persistence test answer." }, 201);
    const review = await get(admin, `/api/v1/moderation/reports/${report.id}`);
    await mutate(admin, `/api/v1/moderation/reports/${report.id}/resolve`, { decision: "HIDE", resolutionNote: "Retain this hide decision across restarts.", expectedVersion: review.report.version, expectedTargetVersion: review.context.reply.version, expectedQuestionVersion: review.context.question.version });
    const open = await mutate(member, "/api/v1/reports", { questionId: question.id, reason: "Keep this report open across restarts." }, 201);
    const articles = [];
    for (const state of ["DRAFT", "PUBLISHED", "ARCHIVED"]) {
      let article = await mutate(admin, "/api/v1/admin/articles", { slug: "storage-" + randomUUID(), title: "Storage verification guide " + state, body: marker + " Instructions remain stored across restarts." }, 201);
      if (state !== "DRAFT") article = await mutate(admin, `/api/v1/admin/articles/${article.id}/publish`, { expectedVersion: article.version });
      if (state === "ARCHIVED") article = await mutate(admin, `/api/v1/admin/articles/${article.id}/archive`, { expectedVersion: article.version });
      articles.push(article);
    }
    const summary = await get(admin, "/api/v1/moderation/summary");
    const expectedRows = fingerprints();
    async function assertPersisted() {
      assert.equal(fingerprints(), expectedRows, "Stored rows changed across restart");
      assert.deepEqual(await get(admin, "/api/v1/moderation/summary"), summary);
      assert.equal((await get(admin, `/api/v1/moderation/reports/${report.id}`)).report.status, "RESOLVED");
      assert.equal((await get(admin, `/api/v1/moderation/reports/${open.id}`)).report.status, "OPEN");
      assert.equal((await get(visitor, `/api/v1/questions/${question.id}`)).acceptedReply, null);
      assert.equal((await visitor.get(`/api/v1/replies/${reply.id}`)).status(), 404);
      for (const article of articles) {
        assert.equal((await get(admin, `/api/v1/admin/articles/${article.id}`)).status, article.status);
        assert.equal((await visitor.get("/api/v1/articles/" + article.slug)).status(), article.status === "PUBLISHED" ? 200 : 404);
      }
      const search = await get(visitor, "/api/v1/search?q=" + marker);
      assert.equal(search.totalElements, 1);
      assert.equal(search.items[0].id, articles[1].id);
    }
    await assertPersisted();
    if (process.argv.includes("--inject-failure")) throw new Error("Injected persistence failure after durable fixtures were created");
    compose(["restart", "backend"]); compose(["up", "-d", "--wait", "--wait-timeout", "180"]);
    assert.equal((await admin.get("/api/v1/auth/me")).status(), 401, "Backend restart must expire in-memory sessions");
    admin = await session("avery.admin@example.test"); await assertPersisted();
    console.log("Backend restart preserved all stored rows, visibility, article states, summary, and search; old session expired.");
    compose(["restart", "db"]); compose(["up", "-d", "--wait", "--wait-timeout", "180"]);
    await assertPersisted();
    console.log("Database restart preserved all stored rows and public/private behavior.");
    compose(["down", "--remove-orphans"]); // Deliberately keep only this project's test volume.
    compose(["up", "-d", "--wait", "--wait-timeout", "180"]);
    assert.equal((await admin.get("/api/v1/auth/me")).status(), 401);
    admin = await session("avery.admin@example.test"); await assertPersisted();
    console.log("Compose down/up preserved reports, audit history, articles, vectors, and decisions; verification passed.");
  } catch (error) {
    console.error(error.message); process.exitCode = 1;
  } finally {
    const disposed = await Promise.allSettled(contexts.map(context => context.dispose()));
    if (disposed.some(result => result.status === "rejected")) process.exitCode = 1;
    try {
      const logs = compose(["logs", "--no-color"], true);
      mkdirSync(new URL("../test-results/", import.meta.url), { recursive: true });
      writeFileSync(new URL(process.argv.includes("--inject-failure") ? "../test-results/persistence-failure-compose.log" : "../test-results/persistence-compose.log", import.meta.url), logs);
    } catch { process.exitCode = 1; }
    try {
      compose(["down", "--volumes", "--remove-orphans"]);
      assertClean();
    } catch (error) { console.error(error.message); process.exitCode = 1; }
  }
}
