package com.lawrencenno.commonbeacon.transfer.archive;

import static org.assertj.core.api.Assertions.assertThat;
import static com.lawrencenno.commonbeacon.transfer.archive.ArchiveFormat.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class ArchiveCodecTest {
    final ArchiveCodec codec = new ArchiveCodec();
    final JsonMapper json = JsonMapper.builder().build();
    final Map<String, byte[]> files = new LinkedHashMap<>();
    ObjectNode manifest;
    void fixture(String name) throws IOException {
        files.clear();
        try (var in = getClass().getResourceAsStream("/data-transfer/v1/" + name + "/manifest.json")) {
            manifest = (ObjectNode) json.readTree(in);
        }
        for (var f : manifest.get("files")) {
            String file = f.get("name").asText();
            try (var in = getClass().getResourceAsStream("/data-transfer/v1/" + name + "/" + file)) { files.put(file, in.readAllBytes()); }
        }
    }
    Result validate() throws IOException {
        var inputs = new LinkedHashMap<String, ArchiveCodec.Input>();
        files.forEach((name, bytes) -> inputs.put(name, () -> new ByteArrayInputStream(bytes)));
        return codec.validate(json.writeValueAsBytes(manifest), inputs, row -> {});
    }
    void refresh() {
        for (var file : manifest.get("files")) {
            var bytes = files.get(file.get("name").asText());
            if (bytes == null) continue;
            ((ObjectNode) file).put("count", new String(bytes, StandardCharsets.UTF_8).lines().count());
            ((ObjectNode) file).put("uncompressedBytes", bytes.length);
            ((ObjectNode) file).put("sha256", HexFormat.of().formatHex(ArchiveCodec.sha256().digest(bytes)));
        }
    }
    void replace(String file, String before, String after) {
        files.put(file, new String(files.get(file), StandardCharsets.UTF_8).replace(before, after).getBytes(StandardCharsets.UTF_8)); refresh();
    }
    void rejects(String code) throws IOException {
        var result = validate(); assertThat(result.valid()).isFalse();
        assertThat(result.issues()).extracting(Issue::code).contains(code);
    }
    @ParameterizedTest @ValueSource(strings={"company-full", "company-default", "company-empty", "personal"})
    void fixturesRoundTrip(String name) throws IOException {
        fixture(name); assertThat(validate().issues()).isEmpty();
        var encoded = new LinkedHashMap<String, ByteArrayOutputStream>();
        var inputs = new LinkedHashMap<String, ArchiveCodec.Input>();
        files.forEach((key, value) -> { inputs.put(key, () -> new ByteArrayInputStream(value)); encoded.put(key, new ByteArrayOutputStream()); });
        var result = codec.validate(codec.encodeManifest(manifest), inputs, row ->
            encoded.get(row.entity().file()).writeBytes(codec.encodeRow(Profile.valueOf(manifest.get("profile").asText()), row.entity(), row.data())));
        assertThat(result.valid()).isTrue();
        encoded.forEach((key, value) -> assertThat(value.toByteArray()).isEqualTo(files.get(key)));
    }
    @Test void rejectsVersionsAndUnknownManifestFields() throws IOException {
        fixture("company-full"); manifest.put("formatVersion",2); rejects("INVALID_MANIFEST");
        fixture("company-full"); manifest.put("password","secret"); rejects("INVALID_MANIFEST");
    }
    @Test void verifiesActualBytesAndCounts() throws IOException {
        fixture("company-full"); files.put("users.jsonl",new byte[0]); rejects("INTEGRITY_MISMATCH");
        fixture("company-full"); ((ObjectNode)manifest.get("files").get(0)).put("count",999); rejects("INTEGRITY_MISMATCH");
    }
    @Test void rejectsUnknownMissingAndDuplicateFiles() throws IOException {
        fixture("company-full"); files.put("../secret",new byte[0]); rejects("FILE_SET_MISMATCH");
        fixture("company-full"); files.remove("contacts.jsonl"); rejects("FILE_SET_MISMATCH");
        fixture("company-full"); ((ObjectNode)manifest.get("files").get(1)).put("name","users.jsonl"); rejects("DUPLICATE_FILE");
    }
    @Test void rejectsDanglingAndCrossQuestionAcceptance() throws IOException {
        fixture("company-full"); replace("questions.jsonl","000000000010","000000000999"); rejects("MISSING_REFERENCE");
        fixture("company-full"); replace("acceptances.jsonl","000000000030","000000000031"); rejects("INVALID_ACCEPTANCE");
        fixture("company-full"); replace("acceptances.jsonl","000000000030","000000000032"); rejects("INVALID_ACCEPTANCE");
    }
    @Test void rejectsForgedAuthorityAndDuplicateKeys() throws IOException {
        fixture("company-full"); replace("users.jsonl","\"displayName\":\"Alex\"","\"displayName\":\"Alex\",\"role\":\"ADMINISTRATOR\""); rejects("INVALID_ROW");
        fixture("company-full"); replace("users.jsonl","\"displayName\":\"Alex\"","\"displayName\":\"Alex\",\"displayName\":\"Sam\""); rejects("INVALID_ROW");
        fixture("company-full"); replace("users.jsonl","\"displayName\":\"Alex\"","\"displayName\":\"Alex\",\"password_hash\":\"secret\""); rejects("INVALID_ROW");
    }
    @Test void validatesStateAndUniqueness() throws IOException {
        fixture("company-full"); replace("reports.jsonl","\"status\":\"OPEN\"","\"status\":\"RESOLVED\""); rejects("INVALID_RESOLUTION");
        fixture("company-full"); replace("articles.jsonl","\"status\":\"DRAFT\"","\"status\":\"PUBLISHED\""); rejects("INVALID_PUBLICATION");
        fixture("company-full"); replace("articles.jsonl","article-41","article-40"); rejects("DUPLICATE_SLUG");
        fixture("company-full"); replace("users.jsonl","000000000002","000000000001"); rejects("DUPLICATE_ID");
    }
    @Test void validatesPersonalPrivacy() throws IOException {
        fixture("personal"); replace("questions.jsonl","000000000001","000000000002"); rejects("OTHER_AUTHOR");
        fixture("personal"); replace("reports.jsonl","\"reason\":","\"resolutionNote\":\"private\",\"reason\":"); rejects("INVALID_ROW");
        fixture("personal"); ((ObjectNode)manifest.get("options")).put("includeModerationHistory",true); rejects("INVALID_PERSONAL_OPTIONS");
    }
    @Test void rejectsInvalidEncodingUnicodeAndOversizedLines() throws IOException {
        fixture("company-full"); files.put("users.jsonl",new byte[]{(byte)0xc3,10}); refresh(); rejects("INVALID_ROW");
        fixture("company-full"); replace("users.jsonl","Alex","a".repeat(79)+new String(Character.toChars(0x1f600))); rejects("INVALID_ROW");
        fixture("company-full"); files.put("users.jsonl",new byte[262145]); refresh(); rejects("LINE_LIMIT");
    }
    @Test void errorsAreBoundedAndDoNotContainInput() throws IOException {
        fixture("company-full"); files.put("users.jsonl","secret-invalid-row\n".repeat(1500).getBytes(StandardCharsets.UTF_8)); refresh();
        var result=validate(); assertThat(result.issues()).hasSize(1000); assertThat(result.totalErrors()).isGreaterThan(1000);
        assertThat(result.issues().toString()).doesNotContain("secret-invalid-row");
    }
    @Test void omittedAndIncludedEmptyHistoryAreDifferent() throws IOException {
        fixture("company-full"); files.put("reports.jsonl",new byte[0]); files.put("actions.jsonl",new byte[0]); refresh(); assertThat(validate().valid()).isTrue();
        files.remove("reports.jsonl"); rejects("FILE_SET_MISMATCH");
    }
    @Test void contactsAndHistoryOptionsAreIndependent() throws IOException {
        for (String omitted : List.of("contacts", "history")) {
            fixture("company-full");
            var names = omitted.equals("contacts") ? Set.of("contacts.jsonl") : Set.of("reports.jsonl", "actions.jsonl");
            names.forEach(files::remove);
            var descriptions = json.createArrayNode();
            for (var f : manifest.get("files")) if (!names.contains(f.get("name").asText())) descriptions.add(f);
            manifest.set("files", descriptions);
            ((ObjectNode) manifest.get("options")).put(omitted.equals("contacts") ? "includeContacts" : "includeModerationHistory", false);
            assertThat(validate().valid()).isTrue();
        }
    }
    @Test void rejectsBlankLinesTrailingJsonBomAndDeepInput() throws IOException {
        fixture("company-full"); replace("users.jsonl", "\n", "\n\n"); rejects("INVALID_ROW");
        fixture("company-full"); replace("users.jsonl", "}\n", "} {}\n"); rejects("INVALID_ROW");
        fixture("company-full"); files.put("users.jsonl", ("\ufeff" + new String(files.get("users.jsonl"), StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8)); refresh(); rejects("INVALID_ROW");
        fixture("company-full"); files.put("users.jsonl", ("[".repeat(9) + "0" + "]".repeat(9)).getBytes(StandardCharsets.UTF_8)); refresh(); rejects("INVALID_ROW");
    }
    @Test void acceptsCrLfAndFinalLineWithoutNewline() throws IOException {
        fixture("company-full"); replace("users.jsonl", "\n", "\r\n"); assertThat(validate().valid()).isTrue();
        fixture("company-full"); var bytes = files.get("users.jsonl"); files.put("users.jsonl", Arrays.copyOf(bytes, bytes.length - 1)); refresh(); assertThat(validate().valid()).isTrue();
    }
    @Test void rejectsInvalidTimestampsTargetsAndReferencePolicy() throws IOException {
        fixture("company-full"); replace("users.jsonl", "2026-09-22T00:00:00Z", "not-a-date"); rejects("INVALID_ROW");
        fixture("company-full"); replace("reports.jsonl", "\"replyId\":\"00000000-0000-0000-0000-000000000032\"", "\"replyId\":null"); rejects("INVALID_TARGET");
        fixture("personal"); manifest.put("referencePolicy", "internal-only"); rejects("INVALID_REFERENCE_POLICY");
        fixture("company-full"); manifest.put("snapshotCompletedAt", "2026-09-21T00:00:00Z"); rejects("INVALID_SNAPSHOT");
    }
    @Test void utf16MinimumAllowsEmojiInNewerDomainFields() throws IOException {
        fixture("company-full");
        replace("articles.jsonl", "Writing a useful question", new String(Character.toChars(0x1f600)).repeat(3));
        assertThat(validate().valid()).isTrue();
    }
    @Test void rejectsMissingFieldsInvalidNullsAndUnpairedSurrogates() throws IOException {
        fixture("company-full"); replace("users.jsonl", "\"displayName\":\"Alex\",", ""); rejects("INVALID_ROW");
        fixture("company-full"); replace("users.jsonl", "\"displayName\":\"Alex\"", "\"displayName\":null"); rejects("INVALID_ROW");
        fixture("company-full"); replace("users.jsonl", "Alex", "\\uD800"); rejects("INVALID_ROW");
    }
}
