# Real exporter fixture provenance

`bundle.json` was generated on 2026-09-23 from synthetic data created in an isolated
Discourse development database. No customer/community data or real credentials
were used. The fixture JSON is the actual helper output, not a hand-written
approximation or an edited source export.

- Source: official `discourse/discourse` tag `v3.5.0`, commit
  `05a304006600f36c3e45d19c9c5919f43f5541c9`.
- Runtime: official `discourse/discourse_dev:release`, image digest
  `sha256:e320f9b75f8425384032e1d0efafe3416593cd659b832f8e92426c40c5b4e0cb`;
  Ruby 3.4.10, PostgreSQL 18. Dependencies installed from the source lockfiles.
- Generator: `scripts/discourse/create_fixture.rb`; export helper:
  `scripts/discourse/export_commonbeacon.rb`, invoking upstream CategoryExporter.
- Synthetic source category IDs: 5 and 6; users: 1 and 2; topics: 9 and 10;
  posts: 10, 11, 12 and 13. Opening posts 10 and 13 become question bodies;
  posts 11 and 12 become replies. Export intentionally omits category-description
  topics created by Discourse itself.
- Expected native counts: users 2, boards 2, questions 2, replies 2,
  acceptances/articles/contacts/reports/actions 0. One hidden question and one
  hidden reply. Both authors become inactive members.
- SHA-256: `879df972bac4a068482d0171d9df5b3edcf55c51226573b1984e203d703fa57c`.

Reproduction requires the pinned checkout, its installed dependencies and a fresh
disposable development database. Copy the helper to `/tmp/export_commonbeacon.rb`,
then run `CB_FIXTURE_ONLY=yes CB_EXPORT_PATH=/tmp/new-bundle.json bundle exec rails runner
/path/create_fixture.rb`. IDs and timestamps may differ on another fresh database;
relationships, counts and visibility must agree. The generator refuses existing
positive-ID users. It disables source rate limiting only within its fixture process.

Initial real-source runs exposed the upstream autoload entry point requirement and
Ruby timestamp serialization difference; the committed helper uses `import_export`
and explicit database UTC timestamps. Tests mutate copies in memory to cover bad
versions, private content, duplicate keys/IDs and incomplete threads.
