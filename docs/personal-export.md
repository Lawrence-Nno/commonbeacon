# Export your personal data

Sign in and select **Export my data** beside your account controls, or open
`/account/data`. Members, moderators and administrators use the same personal
privacy profile. The deployment must enable [private transfer storage](data-transfer-storage.md).

Review the scope, acknowledge that the archive is private, and choose **Confirm and
create export**. Confirm your current password. **Latest export** shows just your newest request, including running, failed,
cancelled and ready/expired states. It refreshes every five seconds when no
confirmation or download is active. **View export history** opens
`/account/data/history`, with up to 20 compact rows of your own requests per page.
Select a row's **View details** to reveal its details and available actions.
Job counts describe processing checkpoints, not invented percentages.

When ready, choose **Download archive**. Confirm your password in that job's card.
The card shows actual bytes received, progress and a Stop download control, then
the filename handed to your browser's Downloads list. This is not proof of saving
to disk; your browser may ask where to save. Keep the downloaded file private.

## What is included

- Your account ID, display name, email, creation timestamp and current role. Role
  is informational and never transfers access or privileges.
- Your authored questions and replies, including hidden text and your replies
  beneath someone else's hidden question. Other authors' bodies are not included.
- Your authored articles in every state, including drafts and archived articles.
- Your report submission IDs, target IDs, submitted reasons and submission times.
  Report statuses, decisions, notes, resolution times and moderator identities are
  excluded, even when you are the moderator or administrator.
- Board ID/slug/name only for boards containing your own questions. References to
  parent questions or accepted replies may be opaque IDs without their bodies or
  authors. Unrelated boards and other people's profiles are excluded.

The ZIP contains `manifest.json` and seven JSONL files: users, boards, questions,
replies, acceptances, articles and reports. Empty sections remain explicit. The
manifest declares `profile: personal` and `referencePolicy: opaque-personal-context`,
with per-file counts and SHA-256 checksums. It is personal portability, not a company
backup or a complete community restoration archive. It cannot be used for company
import. Credentials, sessions, authority restoration, attachments and private
moderation history are excluded.

## Retention and recovery

Downloads expire 24 hours after publication. Expiry, cancellation and downloading
never delete live community data. A failed or cancelled job remains in history;
review the reason before explicitly creating another export. Uncertain creation
responses can be retried using the same in-memory request key and fresh password
confirmation; reloading or leaving the page loses that retry context, so check
history before submitting again. No mutation is retried automatically.

The same limits and private-storage controls as company exports apply (64 MiB ZIP,
256 MiB uncompressed, 40,000 total records plus entity limits). Browser downloads
are buffered within the ZIP limit and are not saved to local/session storage.
Changing accounts or signing out clears private UI/cache state and cancels pending
requests; already saved files cannot be recalled. Other administrators cannot
browse or download your personal jobs.
