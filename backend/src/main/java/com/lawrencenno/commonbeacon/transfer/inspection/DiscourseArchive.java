package com.lawrencenno.commonbeacon.transfer.inspection;

import com.lawrencenno.commonbeacon.transfer.archive.*;
import java.io.*;
import java.nio.*;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.*;
import java.util.*;
import java.util.function.Consumer;
import tools.jackson.core.*;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Strict CommonBeacon Discourse bundle v1, produced by the pinned source-side exporter.
 * Bounded JSON only: no SQL restore, extraction, HTML rendering or remote resource access. */
public final class DiscourseArchive {
    public static final List<String> WARNINGS=List.of("DISCOURSE_PLAIN_TEXT", "DISCOURSE_FLATTENED_THREADS",
        "DISCOURSE_ATTACHMENTS_EXCLUDED", "DISCOURSE_FEATURES_EXCLUDED", "DISCOURSE_PUBLIC_CATEGORIES_ONLY");
    private static final JsonMapper JSON=JsonMapper.builder(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(8).maxStringLength(262144).maxNumberLength(20).build()).build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final SeekableByteChannel channel; private final QuarantineZip.Check check;
    private final Map<ArchiveFormat.Entity,List<ObjectNode>> rows=new EnumMap<>(ArchiveFormat.Entity.class);
    private String instance;
    public DiscourseArchive(SeekableByteChannel channel,QuarantineZip.Check check){this.channel=channel;this.check=check;}
    private static void require(boolean valid,String code)throws QuarantineZip.Rejected {if(!valid)throw new QuarantineZip.Rejected(code);}
    private static void fields(JsonNode n,String... names)throws IOException {
        require(n!=null && n.isObject(),"DISCOURSE_INVALID_RECORD");
        var expected=Set.of(names);require(n.size()==expected.size(),"DISCOURSE_INVALID_FIELDS");
        for(String name:n.propertyNames())require(expected.contains(name),"DISCOURSE_INVALID_FIELDS");
    }
    private static long number(JsonNode n,String key)throws IOException {
        var v=n.get(key);require(v!=null && v.isIntegralNumber() && v.canConvertToLong() && v.asLong()>0 && v.asLong()<=Integer.MAX_VALUE,"DISCOURSE_INVALID_ID");return v.asLong();
    }
    private static String text(JsonNode n,String key)throws IOException {
        var v=n.get(key);require(v!=null && v.isString(),"DISCOURSE_INVALID_FIELD");return v.asText();
    }
    private static boolean bool(JsonNode n,String key)throws IOException {
        var v=n.get(key);require(v!=null && v.isBoolean(),"DISCOURSE_INVALID_FIELD");return v.asBoolean();
    }
    private static void array(JsonNode n,int max)throws IOException {require(n!=null && n.isArray() && n.size()<=max,"DISCOURSE_RECORD_LIMIT");}
    /** Low 32 bits retain the numeric ID for operator reconciliation; namespace includes site and entity. */
    public static UUID sourceId(String site,String entity,long id) {
        long prefix=UUID.nameUUIDFromBytes(("commonbeacon:discourse:v1:"+site+":"+entity).getBytes(StandardCharsets.UTF_8)).getMostSignificantBits();
        return new UUID(prefix,Long.MIN_VALUE|id);
    }
    private String id(String entity,long id){return sourceId(instance,entity,id).toString();}
    private ObjectNode row(ArchiveFormat.Entity entity,String source,long sourceId)throws IOException {
        check.run();var list=rows.computeIfAbsent(entity,k->new ArrayList<>());
        require(list.size()<entity.limit,"DISCOURSE_RECORD_LIMIT");var row=JSON.createObjectNode();row.put("id",id(source,sourceId));list.add(row);return row;
    }
    public ArchiveFormat.Result inspect(Consumer<ArchiveFormat.Row> visitor)throws IOException {
        require(channel.size()>0 && channel.size()<=8388608,"DISCOURSE_SIZE_LIMIT");
        channel.position(0);var bytes=ByteBuffer.allocate((int)channel.size());
        while(bytes.hasRemaining()){check.run();require(channel.read(bytes)>0,"DISCOURSE_INCOMPLETE_BUNDLE");}
        JsonNode root;
        try {root=JSON.readTree(StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(bytes.flip()).toString());}
        catch(RuntimeException | CharacterCodingException e){throw new QuarantineZip.Rejected("DISCOURSE_INVALID_JSON");}
        fields(root,"bundleVersion","sourceVersion","sourceCommit","sourceInstanceId","exportId","snapshotStartedAt","snapshotCompletedAt","categories","topics","users","counts");
        require(root.path("bundleVersion").isIntegralNumber() && root.path("bundleVersion").canConvertToInt() && root.path("bundleVersion").asInt()==1
            && "3.5.0".equals(text(root,"sourceVersion")) && "05a304006600f36c3e45d19c9c5919f43f5541c9".equals(text(root,"sourceCommit")),"DISCOURSE_UNSUPPORTED_VERSION");
        instance=text(root,"sourceInstanceId");
        try{require(UUID.fromString(instance).toString().equals(instance),"DISCOURSE_INVALID_SOURCE");}
        catch(IllegalArgumentException e){throw new QuarantineZip.Rejected("DISCOURSE_INVALID_SOURCE");}
        array(root.get("categories"),100);array(root.get("topics"),5000);array(root.get("users"),2000);
        fields(root.get("counts"),"categories","topics","users","posts");
        for(String name:List.of("categories","topics","users"))require(root.get("counts").path(name).isIntegralNumber() && root.get("counts").path(name).canConvertToLong()
            && root.get("counts").path(name).asLong()==root.get(name).size(),"DISCOURSE_INCOMPLETE_BUNDLE");
        var users=new HashSet<Long>();var categories=new HashSet<Long>();var topics=new HashSet<Long>();var posts=new HashSet<Long>();
        for(var u:root.get("users")) {
            fields(u,"id","username","created_at");long uid=number(u,"id");require(users.add(uid),"DISCOURSE_DUPLICATE_ID");
            row(ArchiveFormat.Entity.users,"user",uid).put("displayName",text(u,"username")).put("createdAt",text(u,"created_at"));
        }
        for(var c:root.get("categories")) {
            fields(c,"id","name","slug","description","created_at","parent_category_id","public");long cid=number(c,"id");require(categories.add(cid),"DISCOURSE_DUPLICATE_ID");
            require(bool(c,"public"),"DISCOURSE_PRIVATE_CATEGORY");
            row(ArchiveFormat.Entity.boards,"category",cid).put("name",text(c,"name")).put("slug",text(c,"slug"))
                .put("description",text(c,"description")).put("archived",false).put("createdAt",text(c,"created_at"));
        }
        for(var c:root.get("categories"))if(!c.get("parent_category_id").isNull())require(categories.contains(number(c,"parent_category_id")),"DISCOURSE_MISSING_CATEGORY");
        for(var t:root.get("topics")) {
            fields(t,"id","title","category_id","archetype","visible","created_at","updated_at","closed","archived","posts","post_count");
            long tid=number(t,"id");require(topics.add(tid),"DISCOURSE_DUPLICATE_ID");
            require("regular".equals(text(t,"archetype")),"DISCOURSE_UNSUPPORTED_TOPIC");require(categories.contains(number(t,"category_id")),"DISCOURSE_MISSING_CATEGORY");
            bool(t,"closed");bool(t,"archived");boolean visible=bool(t,"visible");array(t.get("posts"),20001);
            require(number(t,"post_count")==t.get("posts").size(),"DISCOURSE_INCOMPLETE_THREAD");
            var byNumber=new HashMap<Long,JsonNode>();
            for(var p:t.get("posts")) {
                fields(p,"id","user_id","post_number","raw","created_at","updated_at","reply_to_post_number","hidden","post_type");
                require(posts.add(number(p,"id")),"DISCOURSE_DUPLICATE_ID");
                require(byNumber.putIfAbsent(number(p,"post_number"),p)==null,"DISCOURSE_DUPLICATE_POST_NUMBER");
                require(number(p,"post_type")==1,"DISCOURSE_UNSUPPORTED_POST");require(users.contains(number(p,"user_id")),"DISCOURSE_MISSING_AUTHOR");
                bool(p,"hidden");
            }
            var first=byNumber.get(1L);require(first!=null,"DISCOURSE_MISSING_OPENING_POST");
            var q=row(ArchiveFormat.Entity.questions,"topic",tid).put("boardId",id("category",number(t,"category_id")))
                .put("authorId",id("user",number(first,"user_id"))).put("title",text(t,"title")).put("body",text(first,"raw"))
                .put("visibility",visible && !bool(first,"hidden")?"VISIBLE":"HIDDEN").put("createdAt",text(t,"created_at")).put("updatedAt",text(t,"updated_at"));
            for(var p:t.get("posts")) {
                if(!p.get("reply_to_post_number").isNull()) {
                    long parent=number(p,"reply_to_post_number");require(byNumber.containsKey(parent) && parent<number(p,"post_number"),"DISCOURSE_MISSING_PARENT");
                }
                if(number(p,"post_number")==1)continue;
                row(ArchiveFormat.Entity.replies,"post",number(p,"id")).put("questionId",q.path("id").asText())
                    .put("authorId",id("user",number(p,"user_id"))).put("body",text(p,"raw"))
                    .put("visibility",bool(p,"hidden")?"HIDDEN":"VISIBLE").put("createdAt",text(p,"created_at")).put("updatedAt",text(p,"updated_at"));
            }
        }
        require(root.get("counts").path("posts").isIntegralNumber() && root.get("counts").path("posts").canConvertToLong() && root.get("counts").path("posts").asLong()==posts.size(),"DISCOURSE_INCOMPLETE_BUNDLE");
        return nativeValidation(root,visitor);
    }
    private ArchiveFormat.Result nativeValidation(JsonNode root,Consumer<ArchiveFormat.Row> visitor)throws IOException {
        var manifest=JSON.createObjectNode().put("formatVersion",1).put("profile","company").put("sourceInstanceId",instance)
            .put("exportId",text(root,"exportId")).put("productVersion","Discourse 3.5.0 / CommonBeacon adapter 1")
            .put("snapshotStartedAt",text(root,"snapshotStartedAt")).put("snapshotCompletedAt",text(root,"snapshotCompletedAt")).put("referencePolicy","internal-only");
        manifest.putObject("options").put("includeContacts",false).put("includeModerationHistory",false);
        manifest.putArray("exclusions").add("Private categories/messages, deleted posts, category description topics, credentials and permissions")
            .add("Attachments, accepted answers, articles, tags, votes, reactions, polls, revisions, closed/archive state and plugin data");
        var warnings=manifest.putArray("warnings");WARNINGS.forEach(warnings::add);
        var descriptions=manifest.putArray("files");var inputs=new LinkedHashMap<String,ArchiveCodec.Input>();var codec=new ArchiveCodec();
        for(var entity:List.of(ArchiveFormat.Entity.users,ArchiveFormat.Entity.boards,ArchiveFormat.Entity.questions,ArchiveFormat.Entity.replies,ArchiveFormat.Entity.acceptances,ArchiveFormat.Entity.articles)) {
            check.run();var list=rows.getOrDefault(entity,new ArrayList<>());list.sort(Comparator.comparing(n->n.path("id").asText()));var output=new ByteArrayOutputStream();
            try{for(var row:list){check.run();output.write(codec.encodeRow(ArchiveFormat.Profile.company,entity,row));}}
            catch(IllegalArgumentException e){throw new QuarantineZip.Rejected("DISCOURSE_NATIVE_FIELD_LIMIT_OR_FORMAT");}
            byte[] data=output.toByteArray();inputs.put(entity.file(),()->new ByteArrayInputStream(data));
            descriptions.addObject().put("name",entity.file()).put("count",list.size()).put("uncompressedBytes",data.length)
                .put("sha256",HexFormat.of().formatHex(ArchiveCodec.sha256().digest(data)));
        }
        return codec.validateCompanyImport(JSON.writeValueAsBytes(manifest),inputs,visitor);
    }
}
