package com.lawrencenno.commonbeacon.transfer.inspection;

import static org.assertj.core.api.Assertions.*;
import com.lawrencenno.commonbeacon.transfer.archive.ArchiveFormat;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.*;

class DiscourseArchiveTest {
    @TempDir Path temp;
    static final JsonMapper JSON=JsonMapper.builder().build();
    static byte[] fixture()throws Exception {return Files.readAllBytes(Path.of("src/test/resources/data-transfer/discourse-3.5.0/bundle.json"));}
    ArchiveFormat.Result inspect(byte[] bytes,List<ArchiveFormat.Row> rows)throws Exception {
        Path file=temp.resolve("input.json");Files.write(file,bytes);
        try(var channel=Files.newByteChannel(file)){return new DiscourseArchive(channel,()->{}).inspect(rows::add);}
    }
    @Test void realExporterFixturePreservesTextVisibilityRelationshipsAndDeterministicIds()throws Exception {
        var rows=new ArrayList<ArchiveFormat.Row>();var result=inspect(fixture(),rows);
        assertThat(result.valid()).as(result.issues().toString()).isTrue();assertThat(result.rows()).isEqualTo(8);
        assertThat(rows.stream().filter(r->r.entity()==ArchiveFormat.Entity.users)).hasSize(2);
        assertThat(rows.stream().filter(r->r.entity()==ArchiveFormat.Entity.boards)).hasSize(2);
        var questions=rows.stream().filter(r->r.entity()==ArchiveFormat.Entity.questions).toList();
        assertThat(questions).hasSize(2);assertThat(questions.stream().filter(r->r.data().path("visibility").asText().equals("HIDDEN"))).hasSize(1);
        assertThat(questions.stream().map(r->r.data().path("body").asText()).toList()).anySatisfy(body->assertThat(body).contains("**this Markdown**","<b>literal HTML</b>"));
        assertThat(rows.stream().filter(r->r.entity()==ArchiveFormat.Entity.replies && r.data().path("visibility").asText().equals("HIDDEN"))).hasSize(1);
        var again=new ArrayList<ArchiveFormat.Row>();inspect(fixture(),again);assertThat(again).isEqualTo(rows);
        assertThat(DiscourseArchive.sourceId("site-a","user",42)).isNotEqualTo(DiscourseArchive.sourceId("site-b","user",42))
            .isNotEqualTo(DiscourseArchive.sourceId("site-a","post",42));
        assertThat(DiscourseArchive.sourceId("site-a","user",42).getLeastSignificantBits() & Long.MAX_VALUE).isEqualTo(42);
    }
    @ParameterizedTest @ValueSource(strings={"version","version-overflow","count-overflow","commit","private","duplicate-user","duplicate-post","missing-user","missing-opening","missing-parent","partial","unknown-field","unsupported-post","private-topic","oversized-body"})
    void rejectsUnsupportedOrIncompleteSourceBeforeVisitingRows(String mutation)throws Exception {
        var root=(ObjectNode)JSON.readTree(fixture());var topic=(ObjectNode)root.path("topics").get(0);var posts=(ArrayNode)topic.path("posts");var first=(ObjectNode)posts.get(0);
        switch(mutation) {
            case "version"->root.put("sourceVersion","3.6.0");
            case "version-overflow"->root.put("bundleVersion",4294967297L);
            case "count-overflow"->((ObjectNode)root.path("counts")).put("users",new java.math.BigInteger("18446744073709551618"));
            case "commit"->root.put("sourceCommit","unknown");
            case "private"->((ObjectNode)root.path("categories").get(0)).put("public",false);
            case "duplicate-user"->{var users=(ArrayNode)root.path("users");users.set(1,users.get(0).deepCopy());}
            case "duplicate-post"->((ObjectNode)posts.get(1)).put("id",first.path("id").asLong());
            case "missing-user"->first.put("user_id",2147483647);
            case "missing-opening"->first.put("post_number",99);
            case "missing-parent"->first.put("reply_to_post_number",999);
            case "partial"->topic.put("post_count",999);
            case "unknown-field"->root.put("remoteUrl","https://example.invalid/private");
            case "unsupported-post"->first.put("post_type",2);
            case "private-topic"->topic.put("archetype","private_message");
            case "oversized-body"->first.put("raw","x".repeat(20001));
            default->throw new AssertionError(mutation);
        }
        var visited=new ArrayList<ArchiveFormat.Row>();assertThatThrownBy(()->inspect(JSON.writeValueAsBytes(root),visited)).isInstanceOf(QuarantineZip.Rejected.class);
        assertThat(visited).isEmpty();
    }
    @Test void rejectsDuplicateKeysTrailingJsonInvalidUtf8AndSizeBeforeConversion()throws Exception {
        for(byte[] bytes:List.of("{\"bundleVersion\":1,\"bundleVersion\":1}".getBytes(), "{}{}".getBytes(), new byte[]{(byte)0xff},new byte[8388609]))
            assertThatThrownBy(()->inspect(bytes,new ArrayList<>())).isInstanceOf(QuarantineZip.Rejected.class);
    }
    @Test void honoursCancellationWhileReading()throws Exception {
        Path file=temp.resolve("cancel.json");Files.write(file,fixture());
        try(var channel=Files.newByteChannel(file)) {
            assertThatThrownBy(()->new DiscourseArchive(channel,()->{throw new java.io.IOException("cancelled");}).inspect(r->{})).hasMessage("cancelled");
        }
    }
}
