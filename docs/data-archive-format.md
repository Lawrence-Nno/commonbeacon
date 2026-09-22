# Native data archive format v1

CommonBeacon includes schemas and a backend codec for portable community records.
The [company export API](data-transfer-access.md#company-export) produces this format
and supports protected downloads. [Personal exports](personal-export.md) use the
separate personal profile. [Quarantine upload and inspection](quarantine-upload.md)
are available; activation is not implemented. The inspector uses the company-import
codec entry point, which rejects personal manifests before visiting rows.

The [schemas](../backend/src/main/resources/data-transfer/v1/) are JSON Schema
2020-12 documents. The [readable fixtures](../backend/src/test/resources/data-transfer/v1/)
contain separated archive entries so they can be inspected without a ZIP tool.

## Manifest and entries

`manifest.json` declares `formatVersion: 1`, `profile` (`company` or `personal`),
`sourceInstanceId`, `exportId`, `productVersion`, UTC `snapshotStartedAt` and
`snapshotCompletedAt`, `options`, `exclusions`, `warnings`, `referencePolicy`,
and `files`. Version 1 governs both profile schemas. Every field is required;
unknown fields and unsupported versions fail validation.

Each file descriptor has `name`, `count`, `uncompressedBytes`, and lowercase
hexadecimal `sha256`. The codec recomputes counts, byte lengths, and SHA-256 from
the supplied streams. Metadata and checksums do not establish source authenticity.
Actual bytes include line separators, including CRLF when present.

Every profile includes `users.jsonl`, `boards.jsonl`, `questions.jsonl`,
`replies.jsonl`, `acceptances.jsonl`, and `articles.jsonl`, even when empty.
For company archives, `includeContacts` requires `contacts.jsonl` and
`includeModerationHistory` requires both `reports.jsonl` and `actions.jsonl`.
Otherwise those files must be absent. The options are independent. Personal
archives require `reports.jsonl`, forbid contacts/actions files, and set both
options false. An omitted optional section is different from an included empty file.

## Records and relationships

Each line is one JSON object matching the profile/entity schema. Fields use
camelCase. Company archives carry attribution, content, timestamps, visibility,
article states and optional contact/history data. Hidden content and drafts make
company archives private, even when the contact/history options are false.

IDs are lowercase canonical UUIDs in a source-instance namespace, not destination
account IDs. Files sort ascending by `id`; acceptance and contact files sort by
`questionId` and `userId`, respectively. Duplicate keys and unordered IDs fail.
Timestamps require valid UTC `Z` values at microsecond precision or coarser.
Nullable fields must explicitly contain null; they cannot be omitted.

Company references must resolve within the archive. Questions reference boards
and authors; replies reference questions and authors. Acceptance lives in a
separate question/reply pair file to break the question-to-reply cycle during
loading. An accepted reply must belong to that question and cannot be HIDDEN.
A hidden parent may retain an accepted visible reply. Board/article slugs and
OPEN report reporter/target pairs must be unique.

Reports and actions have exactly one question or reply target. OPEN reports have
null resolution fields; RESOLVED reports have all resolution fields populated.
DRAFT articles have no publication timestamp; PUBLISHED articles require one;
ARCHIVED articles permit either. Historical moderation records are source claims,
not proof of authenticated actions on a receiving deployment.

Company entity records, except acceptance/contact pairs, may include optional
`origin: {sourceInstanceId, sourceId}` to preserve an earlier source attribution
through subsequent exports. It is untrusted provenance, never a lookup instruction
to overwrite a destination row or grant account access. Immediate source identity
still comes from the manifest and record ID. Personal schemas exclude origin.

## Personal projection and exclusions

A personal archive contains exactly one user profile, including their own email
and informational current role. All authored records must belong to that user.
Own hidden questions/replies and own articles in any state are permitted. Board
context contains only ID, slug and name, and must exactly match boards referenced
by the exported questions. Acceptance pairs apply only to exported questions.

Personal report records contain submission ID, target ID, reason and creation
time. They exclude status, resolution, moderators and notes. Parent/target/reply
references may point outside the personal archive; it declares
`referencePolicy: "opaque-personal-context"`. Company archives instead require
`"internal-only"`. Missing personal context is not filled by fetching other users'
content. Personal archives are not company-import inputs. The later import boundary
must enforce the company profile before staging or activation.

Company user schemas contain no email, role, password or verification state.
Optional contacts are private provenance, not login identities. Local account state
and authorization revisions are excluded from archives; all imported identities
are created as inactive members. Durable source mappings and contact retention
are described in [imported author identities](data-transfer-access.md#imported-author-identities). No schema carries
password hashes, session tokens, deployment secrets, local concurrency versions,
or generated search vectors. An informational role in a personal profile never
authorizes restoring that role. Unknown properties fail in both directions.

## Text, integrity and limits

Input is strict UTF-8 without a BOM. Blank lines, duplicate JSON properties,
trailing JSON values, nonfinite numbers, NUL and unpaired surrogates are invalid.
LF and CRLF are accepted; the final line may omit its newline. Encoded rows use
compact UTF-8 JSON and LF. Field order is preserved by the tree serializer, not
normalized for cryptographic canonicalization; equivalent archives need not have
identical ZIP bytes.

Schemas specify Unicode code-point lengths and the `x-minUtf16Length` /
`x-maxUtf16Length` extensions for Java/JavaScript domain limits. `x-trimmed`
requires nonblank text with no leading/trailing Java `trim()` characters. Content
is rejected rather than silently trimmed or truncated. Newer article/moderation
fields use UTF-16 minimum lengths to match their existing database constraints.
Generic JSON Schema tools do not enforce these extensions or cross-file rules.

The codec caps manifest bytes at 64 KiB, JSON depth at 8, row bytes at 256 KiB
(excluding LF), each entry at 128 MiB, aggregate entry bytes at 256 MiB, and total
rows at 40,000. Entity caps are 2,000 users/contacts, 100 boards, 5,000 questions,
20,000 replies, 5,000 acceptance pairs, 1,000 articles, and 5,000 reports/actions.
These are validation ceilings, not measured production capacity guarantees.

Validation retains at most 1,000 error records, with a total error count. Errors
contain an allowlisted filename, line number and fixed code, never source bodies
or parser messages. Structural, integrity, reference, ownership, ordering and
state errors make the result invalid. I/O failures propagate to the caller so a
later job layer can distinguish storage failure from invalid data.

## Backend integration boundary

The separate [job and private-storage foundation](data-transfer-storage.md) supplies
durable orchestration primitives; the company-export handler uses the codec after
closing its database snapshot and before publishing a ZIP.

`ArchiveCodec.validate` accepts manifest bytes, a map of allowlisted entry names
to input-stream factories, and a row consumer. It streams rows and retains only
bounded relationship/state indexes, not content bodies. The consumer receives
**provisional** rows before whole-archive integrity and references are known: it
must use disposable staging and must never publish live data from this callback.
Only a valid final result completes archive validation. Staging cleanup, immutable
artifact binding, permissions and activation are separate responsibilities.

`encodeRow` and `encodeManifest` validate record shapes and byte bounds before
serialization. Whole-archive semantic validation still requires `validate`.
`ArchiveSchema` supports only the vocabulary in the bundled, trusted schemas;
it is not a general JSON Schema engine and never loads source-provided schemas.
JSON parsing uses Jackson's
[strict duplicate detection](https://javadoc.io/static/tools.jackson.core/jackson-core/3.2.2/tools.jackson.core/tools/jackson/core/StreamReadFeature.html).

This codec does not extract ZIPs, fetch URLs, authenticate users, create jobs,
write domain tables or enforce download retention. ZIP entry types/path safety,
compressed-size/ratio limits, disk reservations and worker deadlines are enforced
by the quarantine/job integration. Production heap/throughput measurements remain
later verification work.
The count-bounded metadata index has not been certified against the proposed
production heap budget. Do not expose this codec directly as an upload endpoint.

## Verification

`ArchiveCodecTest` reads and re-encodes the company-full, company-default,
company-empty and personal fixtures, and checks malformed/hostile inputs and
privacy boundaries. Tests mutate copies in memory and do not use development data.
Run from the repository root with the project's Java toolchain:

```powershell
./backend/mvnw.cmd -f backend/pom.xml -Dtest=ArchiveCodecTest test
```
