package com.lawrencenno.commonbeacon.knowledge;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.*;

public final class ArticleRequests {
    private ArticleRequests() {}
    private static String trim(String value) { return value == null ? null : value.trim(); }
    public record Create(@NotBlank @Size(min = 3, max = 100) @Pattern(regexp = "[a-z0-9]+(?:-[a-z0-9]+)*") String slug,
            @NotBlank @Size(min = 5, max = 200) String title, @NotBlank @Size(min = 10, max = 20000) String body) {
        public Create { slug = trim(slug); title = trim(title); body = trim(body); }
        @JsonAnySetter public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unknown article field"); }
    }
    public record Edit(@NotBlank @Size(min = 5, max = 200) String title,
            @NotBlank @Size(min = 10, max = 20000) String body, @NotNull @PositiveOrZero Long expectedVersion) {
        public Edit { title = trim(title); body = trim(body); }
        @JsonAnySetter public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unknown article field"); }
    }
    public record Version(@NotNull @PositiveOrZero Long expectedVersion) {
        @JsonAnySetter public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unknown article field"); }
    }
}
