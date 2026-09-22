package com.lawrencenno.commonbeacon.transfer.archive;

import java.util.List;
import tools.jackson.databind.JsonNode;

/** Portable data only: no persistence entities, credentials, or authority restoration. */
public final class ArchiveFormat {
    private ArchiveFormat() {}
    public enum Profile { company, personal }
    public enum Entity {
        users(2000), boards(100), questions(5000), replies(20000), acceptances(5000),
        articles(1000), contacts(2000), reports(5000), actions(5000);
        public final int limit;
        Entity(int limit) { this.limit = limit; }
        public String file() { return name() + ".jsonl"; }
    }
    public record Row(Entity entity, long line, JsonNode data) {}
    public record Issue(String file, long line, String code) {}
    public record Result(JsonNode manifest, List<Issue> issues, long totalErrors, long rows) {
        public Result { issues = List.copyOf(issues); }
        public boolean valid() { return totalErrors == 0; }
    }
}
