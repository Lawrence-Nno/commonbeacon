package com.lawrencenno.commonbeacon.transfer.archive;

import static com.lawrencenno.commonbeacon.transfer.archive.ArchiveFormat.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.security.*;
import java.util.*;
import java.util.function.Consumer;
import tools.jackson.core.*;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;

/** Reads already-separated, untrusted archive entries. ZIP extraction belongs to the
 * quarantine layer. Visitors receive provisional rows, never permission to publish them. */
public final class ArchiveCodec {
    @FunctionalInterface public interface Input { InputStream open() throws IOException; }
    private final JsonMapper mapper = JsonMapper.builder(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(8)
                .maxStringLength(262144).maxNumberLength(20).build()).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final ArchiveSchema schemas = new ArchiveSchema(JsonMapper.builder().build());
    private static final Set<String> BASE = Set.of("users.jsonl", "boards.jsonl", "questions.jsonl",
            "replies.jsonl", "acceptances.jsonl", "articles.jsonl");

    public byte[] encodeRow(Profile profile, Entity entity, JsonNode row) {
        if (!schemas.valid(profile + "-" + entity, row)) throw new IllegalArgumentException("INVALID_ROW");
        byte[] encoded = (mapper.writeValueAsString(row) + "\n").getBytes(StandardCharsets.UTF_8);
        if (encoded.length > 262145) throw new IllegalArgumentException("LINE_LIMIT");
        return encoded;
    }
    public byte[] encodeManifest(JsonNode manifest) {
        if (!schemas.valid("manifest", manifest)) throw new IllegalArgumentException("INVALID_MANIFEST");
        byte[] encoded = mapper.writeValueAsBytes(manifest);
        if (encoded.length > 65536) throw new IllegalArgumentException("MANIFEST_LIMIT");
        return encoded;
    }
    /** Entry point for the upcoming company importer; personal portability is never activation input. */
    public Result validateCompanyImport(byte[] manifestBytes, Map<String, Input> files, Consumer<Row> visitor) throws IOException {
        if(manifestBytes.length>65536)return new Check().fail("manifest.json",0,"LIMIT_EXCEEDED");
        var manifest=parse(manifestBytes);
        if(manifest!=null && schemas.valid("manifest",manifest) && !"company".equals(manifest.path("profile").asText()))
            return new Check().fail("manifest.json",0,"COMPANY_PROFILE_REQUIRED");
        return validate(manifestBytes,files,visitor);
    }
    public Result validate(byte[] manifestBytes, Map<String, Input> files, Consumer<Row> visitor) throws IOException {
        var check = new Check();
        if (manifestBytes.length > 65536) return check.fail("manifest.json", 0, "LIMIT_EXCEEDED");
        JsonNode manifest = parse(manifestBytes);
        if (manifest == null || !schemas.valid("manifest", manifest))
            return check.fail("manifest.json", 0, "INVALID_MANIFEST");
        Profile profile = Profile.valueOf(manifest.get("profile").asText());
        boolean personal = profile == Profile.personal;
        if (!manifest.get("referencePolicy").asText().equals(personal ? "opaque-personal-context" : "internal-only"))
            check.error("manifest.json", 0, "INVALID_REFERENCE_POLICY");
        if (java.time.Instant.parse(manifest.get("snapshotCompletedAt").asText()).isBefore(
                java.time.Instant.parse(manifest.get("snapshotStartedAt").asText())))
            check.error("manifest.json", 0, "INVALID_SNAPSHOT");
        var expected = new HashSet<>(BASE);
        var options = manifest.get("options");
        if (personal) {
            expected.add("reports.jsonl");
            if (options.get("includeContacts").asBoolean() || options.get("includeModerationHistory").asBoolean())
                check.error("manifest.json", 0, "INVALID_PERSONAL_OPTIONS");
        } else {
            if (options.get("includeContacts").asBoolean()) expected.add("contacts.jsonl");
            if (options.get("includeModerationHistory").asBoolean()) expected.addAll(Set.of("reports.jsonl", "actions.jsonl"));
        }
        var descriptions = new HashMap<String, JsonNode>();
        for (var file : manifest.get("files")) {
            if (descriptions.put(file.get("name").asText(), file) != null) check.error("manifest.json", 0, "DUPLICATE_FILE");
        }
        if (!expected.equals(descriptions.keySet()) || !expected.equals(files.keySet()))
            return check.fail("manifest.json", 0, "FILE_SET_MISMATCH");
        var index = new EnumMap<Entity, Map<String, Map<String, String>>>(Entity.class);
        long totalBytes = 0;
        for (var entity : Entity.values()) {
            var records = new LinkedHashMap<String, Map<String, String>>();
            index.put(entity, records);
            if (!expected.contains(entity.file())) continue;
            long count = 0, bytes = 0;
            var digest = sha256();
            String previous = null;
            try (var stream = new BufferedInputStream(files.get(entity.file()).open())) {
                var line = new ByteArrayOutputStream();
                int next;
                while ((next = stream.read()) != -1) {
                    digest.update((byte) next); bytes++; totalBytes++;
                    if (bytes > 134217728 || totalBytes > 268435456)
                        return check.fail(entity.file(), count + 1, "LIMIT_EXCEEDED");
                    if (next != 10) {
                        if (line.size() >= 262144) return check.fail(entity.file(), count + 1, "LINE_LIMIT");
                        line.write(next); continue;
                    }
                    count++;
                    previous = consume(profile, entity, line.toByteArray(), count, previous, records, check, visitor);
                    line.reset();
                    if (count > entity.limit || check.rows > 40000) return check.fail(entity.file(), count, "ROW_LIMIT");
                }
                if (line.size() > 0) {
                    count++;
                    consume(profile, entity, line.toByteArray(), count, previous, records, check, visitor);
                }
            }
            if (count > entity.limit || check.rows > 40000) return check.fail(entity.file(), count, "ROW_LIMIT");
            var description = descriptions.get(entity.file());
            if (count != description.get("count").asLong() || bytes != description.get("uncompressedBytes").asLong()
                    || !HexFormat.of().formatHex(digest.digest()).equals(description.get("sha256").asText()))
                check.error(entity.file(), 0, "INTEGRITY_MISMATCH");
        }
        relationships(index, personal, check);
        return new Result(manifest, check.issues, check.errors, check.rows);
    }
    private String consume(Profile profile, Entity entity, byte[] bytes, long line, String previous,
            Map<String, Map<String, String>> records, Check check, Consumer<Row> visitor) {
        check.rows++;
        JsonNode row = parse(bytes);
        if (row == null || !schemas.valid(profile + "-" + entity, row)) {
            check.error(entity.file(), line, "INVALID_ROW"); return previous;
        }
        String key = row.path(entity == Entity.acceptances ? "questionId" : entity == Entity.contacts ? "userId" : "id").asText();
        if (previous != null && previous.compareTo(key) >= 0) check.error(entity.file(), line, "ID_ORDER_OR_DUPLICATE");
        var summary = new HashMap<String, String>();
        // Index only relationship/state metadata, never bodies, names, emails or notes.
        for (String field : List.of("boardId", "questionId", "replyId", "authorId", "reporterId", "resolverId",
                "actorId", "visibility", "status", "publishedAt", "resolvedAt", "resolutionDecision", "slug")) {
            if (row.hasNonNull(field)) summary.put(field, row.get(field).asText());
        }
        summary.put("line", Long.toString(line));
        if (row.hasNonNull("resolutionNote")) summary.put("resolutionNote", "present");
        if (records.putIfAbsent(key, summary) != null) check.error(entity.file(), line, "DUPLICATE_ID");
        visitor.accept(new Row(entity, line, row));
        return key;
    }
    private void relationships(EnumMap<Entity, Map<String, Map<String, String>>> all, boolean personal, Check c) {
        String subject = personal && all.get(Entity.users).size() == 1 ? all.get(Entity.users).keySet().iterator().next() : null;
        if (personal && subject == null) c.error("users.jsonl", 0, "PERSONAL_SUBJECT_REQUIRED");
        var openReports = new HashSet<String>();
        var usedBoards = new HashSet<String>();
        for (var entity : Entity.values()) {
            var slugs = new HashSet<String>();
            for (var entry : all.get(entity).entrySet()) {
                var r = entry.getValue(); long line = Long.parseLong(r.get("line"));
                if (r.containsKey("slug") && !slugs.add(r.get("slug"))) c.error(entity.file(), line, "DUPLICATE_SLUG");
                for (var ref : Map.of("boardId", Entity.boards, "questionId", Entity.questions, "replyId", Entity.replies,
                        "authorId", Entity.users, "reporterId", Entity.users, "resolverId", Entity.users, "actorId", Entity.users).entrySet()) {
                    String id = r.get(ref.getKey());
                    if (id != null && !all.get(ref.getValue()).containsKey(id) && !personal)
                        c.error(entity.file(), line, "MISSING_REFERENCE");
                }
                if (entity == Entity.contacts && !all.get(Entity.users).containsKey(entry.getKey())) c.error(entity.file(), line, "MISSING_REFERENCE");
                if (entity == Entity.questions) usedBoards.add(r.get("boardId"));
                if (personal && r.containsKey("authorId") && !Objects.equals(subject, r.get("authorId")))
                    c.error(entity.file(), line, "OTHER_AUTHOR");
                if (entity == Entity.acceptances) {
                    var reply = all.get(Entity.replies).get(r.get("replyId"));
                    boolean ownQuestion = all.get(Entity.questions).containsKey(entry.getKey());
                    if ((!personal && !ownQuestion) || (reply != null && (!entry.getKey().equals(reply.get("questionId"))
                            || "HIDDEN".equals(reply.get("visibility")))) || (personal && !ownQuestion))
                        c.error(entity.file(), line, "INVALID_ACCEPTANCE");
                }
                if (entity == Entity.articles && (("DRAFT".equals(r.get("status")) && r.containsKey("publishedAt"))
                        || ("PUBLISHED".equals(r.get("status")) && !r.containsKey("publishedAt")))) c.error(entity.file(), line, "INVALID_PUBLICATION");
                if (entity == Entity.reports || entity == Entity.actions) {
                    if (r.containsKey("questionId") == r.containsKey("replyId")) c.error(entity.file(), line, "INVALID_TARGET");
                }
                if (entity == Entity.reports && !personal) {
                    boolean resolved = "RESOLVED".equals(r.get("status"));
                    for (var field : List.of("resolverId", "resolvedAt", "resolutionDecision", "resolutionNote"))
                        if (resolved != r.containsKey(field)) c.error(entity.file(), line, "INVALID_RESOLUTION");
                    if (!resolved && !openReports.add(r.get("reporterId") + ":" + r.get("questionId") + ":" + r.get("replyId")))
                        c.error(entity.file(), line, "DUPLICATE_OPEN_REPORT");
                }
            }
        }
        if (personal && !usedBoards.equals(all.get(Entity.boards).keySet())) c.error("boards.jsonl", 0, "PERSONAL_BOARD_SCOPE");
    }
    private JsonNode parse(byte[] bytes) {
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            return mapper.readTree(text);
        } catch (RuntimeException | CharacterCodingException e) { return null; }
    }
    public static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static final class Check {
        final List<Issue> issues = new ArrayList<>(); long errors; long rows;
        void error(String file, long line, String code) {
            errors++; if (issues.size() < 1000) issues.add(new Issue(file, line, code));
        }
        Result fail(String file, long line, String code) { error(file, line, code); return new Result(null, issues, errors, rows); }
    }
}
