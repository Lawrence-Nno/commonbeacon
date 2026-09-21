# Operator verification walkthrough

Use a local instance with opt-in demo seeding, started with the [README](../README.md)
commands. The four accounts initially share the configured `DEMO_PASSWORD`:
Alex (`alex.member@example.test`), Sam (`sam.member@example.test`), Morgan
(`morgan.moderator@example.test`), and Avery (`avery.admin@example.test`). Use separate
browser profiles/private sessions so switching accounts does not discard a draft.
Perform the workflow on a disposable instance if you do not want the resulting
questions, reports, and article records in your community. There is no reset button.

## Public browsing and a resolved question

1. As a visitor, open `/` (Community), `/knowledge`, and `/search?q=question`.
   Published guides and visible questions are readable without signing in.
   Open `/knowledge/demo-writing-a-helpful-question`.
2. Sign in as Alex, choose a board, and ask a question with a distinctive title.
   Its route is `/questions/{id}`. Keep this URL for later checks.
3. As Sam, open the question and post a helpful answer. As Alex, select that
   reply as the accepted answer. Reload as a visitor: the accepted panel is visible.
   The author alone controls acceptance, even when an administrator views the thread.

## Report, hide, and restore

4. As Alex, report the accepted reply with a clear reason. A receipt confirms
   submission; the reply stays visible. Submitting another report on that reply
   before resolution returns `REPORT_ALREADY_OPEN`.
5. As Morgan, open `/moderation`, select the report, and inspect the parent and
   reply context at `/moderation/reports/{id}`. Choose HIDE and enter a resolution
   note. The report resolves, one HIDE event is appended, and acceptance is cleared
   in the same transaction. Other reports on that target remain open.
6. Reload publicly: the hidden reply and accepted panel are absent. With a visible
   parent, the unanswered count rises by one; this report no longer counts as open.
   On **Reply history and restoration**, inspect the private action and reason.
7. Restore the reply with a reason. It becomes public again but is not automatically
   accepted. The history now includes RESTORE. Only Alex can choose it again.
8. To verify parent/child independence, hide that reply again through a new report,
   then report and hide the parent question. Its public URL returns not found and
   search excludes it. Follow **Question history and restoration** to restore the
   parent. The independently hidden reply remains hidden. These safety actions also
   work on archived boards; ordinary member writes remain blocked there.

## Article lifecycle and search

9. As Avery, open `/admin/articles` and choose **Create article**. Use a unique slug
   and a distinctive word in the body. Save the draft, noting its editor URL
   `/admin/articles/{id}`. A visitor cannot open `/knowledge/{slug}` or find the draft
   through search. Moderators cannot use the administrator editor.
10. Publish the draft. The visitor URL and body-term search now find it. Open the
    editor in two tabs. Save an edit in the first, then submit the second: the stale
    version conflicts and preserves the unsaved text. **Load latest article** allows
    explicit review followed by **Use server copy** or **Keep my draft**.
11. Published edits are public immediately. Archive the article, then reload its
    public URL and search: it is unavailable and no longer contributes to totals.
    The administrator can still inspect it; archival is terminal.

## Restart and retained state

12. On the instance used above, run `docker compose restart backend`, then
    `docker compose up -d --wait --wait-timeout 180`. Existing sessions expire.
    Sign in again and verify the resolved reports, action history, hidden reply,
    archived article, summary, and search. Ordinary `docker compose down` followed
    by `up` also preserves the named database volume. Do not add `--volumes` to a
    routine shutdown.

The automated, disposable counterpart is `npm run test:persistence` from frontend;
it checks backend restart, database restart, and down/up with exact stored-row
fingerprints. `npm run test:failure-cleanup` verifies cleanup after an injected
failure. Both avoid the development database; see [Compose verification](compose.md).

## Expected limits

Counts are snapshots, not a live feed. Refresh after another session's changes.
Search uses English stemming and body-prefix snippets, without typo correction or
reply search. Visibility history is append-only through the application, not a
tamper-proof database log. Sessions and login limits are single-instance, and
board locks serialize writes on one board. Local HTTP Compose is not a completed
public-hosting configuration. See [architecture](architecture.md) and
[verification evidence](evidence/milestone-b.md) for measured behavior and limits.
