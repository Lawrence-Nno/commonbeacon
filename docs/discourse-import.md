# Discourse import: narrowly versioned compatibility

CommonBeacon accepts **CommonBeacon Discourse bundle v1** from **Discourse 3.5.0**,
source commit `05a304006600f36c3e45d19c9c5919f43f5541c9`. This is a selected-public-category
adapter, not a full Discourse backup restore. Bare category/topic JSON, SQL backups,
other releases and arbitrary API responses are rejected. Do not change a version
label to force compatibility or downgrade a production Discourse site to use it.

The source helper uses Discourse's actual [CategoryExporter](https://github.com/discourse/discourse/blob/v3.5.0/lib/import_export/category_exporter.rb)
and [BaseExporter](https://github.com/discourse/discourse/blob/v3.5.0/lib/import_export/base_exporter.rb).
The upstream format omits source version, topic visibility and edit timestamps.
Our helper adds those from the same read-only repeatable database snapshot and
projects away emails, groups, roles, custom fields and authentication material.
No CommonBeacon process connects to or scrapes the source site.

## Compatibility and losses

- **Categories → boards:** selected public categories and their immediate children.
  Child categories become separate boards; a missing parent is rejected. Names,
  slugs, descriptions and creation times are preserved. All boards start unarchived.
  Missing descriptions, unsupported slugs and values beyond native limits fail
  validation; nothing is silently truncated or filled in.
- **Topics → questions:** regular topics only. The opening post supplies author
  and body. Titles, creation time and the latest topic/opening-post edit time are
  preserved. Unlisted topics or hidden opening posts become hidden questions.
  Closed/archive state is excluded and requires acknowledgement; imported visible
  discussions can consequently receive new replies.
- **Posts → replies:** regular, nondeleted posts only. Body, author, timestamps and
  hidden state are preserved. Reply-to relationships must resolve to an earlier
  post in the same thread, then are flattened. Hidden parent questions still
  restrict access to their replies through CommonBeacon's normal visibility rules.
- **Authors:** only referenced exported users, using the Discourse username as the
  display name and preserving creation time. They become inactive local members
  without email, passwords, permissions or sign-in access. Missing/system authors
  and nonregular action posts fail; there is no fabricated author or account merge.
- **Text:** raw Discourse Markdown/BBCode/HTML remains literal text. It is never
  interpreted as HTML. Mentions, polls, embeds and source-relative links are not
  translated into interactive CommonBeacon features.
- **Attachments:** files are not copied or downloaded. References in raw text remain
  text; the upstream exporter can expand HTML `/uploads` references into source
  URLs. CommonBeacon preserves that emitted text. Source-hosted resources may
  disappear or require source permissions.
- **Accepted answers and other features:** solved/plugin acceptance, articles,
  reactions, votes, tags, badges, revisions, moderation history and plugin metadata
  are excluded. No accepted answer is inferred from ordering or wording.
- **Excluded source scope:** private/restricted categories, private messages,
  deleted content and category-description topics. Selected restricted categories
  cause export failure. Unselected categories are deliberately outside this export.

Every import review requires acknowledgement of these conversion losses. Expected
and created counts cover the converted dataset; they do not imply that excluded
source features were transferred.

## Produce a bundle

Run on an authorized matching Discourse installation or an isolated restored copy.
Keep a stable UUID identifying that source installation across exports. Use a new
output filename for each run; the helper creates it with owner-only permissions
and refuses to overwrite an existing file.

```sh
CB_CATEGORY_IDS=12,18 \
CB_SOURCE_INSTANCE_ID=5ce4206c-2b9b-4134-b2f0-d48fda1e5027 \
CB_EXPORT_PATH=/private/new-commonbeacon-bundle.json \
bundle exec rails runner /path/to/export_commonbeacon.rb
```

The script is [export_commonbeacon.rb](../scripts/discourse/export_commonbeacon.rb).
The upstream exporter prints topic titles: run in a private console and treat
its output and the bundle as sensitive. The helper has a 120-second SQL statement
timeout, preflight record limits, a repeatable read-only transaction, and an 8 MiB
output limit. This is intended for small migrations within CommonBeacon's existing
40,000-record / 16 MiB staged-data activation envelope, not unbounded bulk export.

In **Data management → Import company data**, select **Discourse 3.5.0 bundle v1**,
upload the JSON, run inspection and the dry run, then inspect counts, errors,
visibility and loss acknowledgements before confirming. The target must still be
an empty bootstrap company. Existing native activation authorization, target
generation, review digest, file hash, transaction and retention rules apply.

## Identity and reconciliation

Each mapping shows both the source UUID and the original numeric Discourse ID.
The UUID's high 64 bits come from Java's name UUID of UTF-8
`commonbeacon:discourse:v1:{sourceInstanceId}:{user|category|topic|post}`;
the low 64 bits are `0x8000000000000000 | positiveSourceId` (32-bit source ID).
The source instance and entity therefore scope IDs; a numeric user ID does not
collide with a post ID. Topics map to questions, posts after number 1 to replies.
Imported provenance and local IDs are retained by the normal activation ledger.

## Fixture reproduction

[create_fixture.rb](../scripts/discourse/create_fixture.rb) is only for an empty,
disposable Discourse development database, with `CB_FIXTURE_ONLY=yes`. It creates
synthetic `example.invalid` users, a category and subcategory, two topics, visible
and hidden replies, an unlisted topic, nesting, literal markup and a file reference.
It then invokes the actual export helper from `/tmp/export_commonbeacon.rb`.
Never run fixture generation against a real community.

The checked-in source fixture and its provenance record live under
`backend/src/test/resources/data-transfer/discourse-3.5.0/`. Tests must reconcile
2 users, 2 boards, 2 questions and 2 replies, with no acceptances or articles,
and reject unsupported versions, duplicate IDs and incomplete/unsafe input.
