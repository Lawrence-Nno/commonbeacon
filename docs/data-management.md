# Export company data

An active administrator can open **Data management** from the main navigation
(`/admin/data`). Other administrators cannot see or download your requests. Members
and moderators do not have access to this screen or its company-data APIs.

The deployment must enable [private transfer storage](data-transfer-storage.md).
The base development Compose file leaves storage disabled; add the documented
`compose.transfers.yaml` overlay when using exports. No database or CLI access is
needed by the administrator once the operator has configured storage.

## Create and download

1. Review the scope. Company archives always include hidden conversations, drafts,
   archived articles, authors, boards, and accepted-answer relationships.
2. Optionally select contact details and/or private moderation history. Both start
   unchecked. History includes sensitive reports, identities, decisions and notes.
3. Read the scope preview, acknowledge the private content, then choose **Confirm
   and create export**. Confirm your current password to authorize the request.
4. Watch **Your export history**. The screen refreshes every five seconds, with at
   most 20 requester-owned summaries per page. **Older requests** pages backward;
   **Newest requests** returns to the start. Counts describe the last processing
   checkpoint, not a percentage or a finalized manifest total.
5. When ready, choose **Download archive**. A password prompt opens inside that
   archive's history card; the download has not started until you confirm it.
   The card then shows connection status and actual bytes received, with a progress
   bar and **Stop download** control. Once the complete ZIP is handed to the browser,
   the card shows its filename and asks you to check the browser's Downloads list.
   This confirms browser handoff, not that a file was saved to disk. Errors appear
   in the same card and allow a fresh attempt. Keep the downloaded file private.

The preview describes selected sections, not a database snapshot or record count.
The worker takes the snapshot after claiming the queued job. The v1 archive is a
ZIP containing JSON Lines and a manifest with final counts and checksums. Passwords,
sessions, privileges, deployment settings and attachments are excluded. This is
not a database backup. Imported authors do not automatically receive login access.
See [archive format and limits](data-archive-format.md).

## Failures, cancellation and expiry

- A failed creation response preserves the selected options and a request key in
  memory. **Retry export request** requires fresh password confirmation and reuses
  that key, so a lost success response does not create another job. Mutations are
  never retried automatically. **Review different options** starts a different
  request; inspect history first in case the original one succeeded. Reloading or
  leaving the screen discards this in-memory retry context.
- Failed/cancelled jobs remain in history. Review the reason and current options
  before explicitly creating a new export. Earlier jobs' options are not copied
  from history. Refresh after conflicts or unavailable services; do not repeatedly
  submit new jobs to bypass deployment limits.
- **Cancel export** appears only when the server allows cancellation. It submits
  the displayed version; a concurrent transition can require refreshing first.
- Downloads expire 24 hours after publication. Ready jobs can show an expired or
  unavailable archive when storage is no longer accessible. Create a new export if
  necessary. A failed download can be tried again with fresh password confirmation.
- Downloading, cancellation, and archive expiry never delete live community data.

## Private browser state

Passwords, grants, tickets, archives, and job histories are not persisted in local
or session storage. Tickets travel in headers, never download URLs. Downloads are
bounded to 64 MiB and complete in memory before a browser save is offered; truncated
or oversized responses do not produce a saved archive. A temporary blob URL is
revoked after delivery or when leaving the screen. This can use more than the ZIP
size in browser memory; lower-memory devices may need to retry on another device.

Queries are scoped to the current actor. Account changes, logout, and access denial
unmount private state, cancel pending requests, and ignore late responses. A file
already saved by the browser cannot be recalled on logout. Client-side navigation
is not the authorization boundary: the API and worker recheck current permissions.

The prompt supports keyboard input and moves focus to the password field. The page
uses native labelled checkboxes, status/error announcements, and a mobile layout.
For your own account data, use [Export my data](personal-export.md). Import remains
separate future work.
